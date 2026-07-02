package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PartQueryService {
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "DISCONTINUED");
    private final JdbcTemplate jdbcTemplate;
    private final PartCompatibleCandidateService compatibilityService;

    public PartQueryService(JdbcTemplate jdbcTemplate, PartCompatibleCandidateService compatibilityService) {
        this.jdbcTemplate = jdbcTemplate;
        this.compatibilityService = compatibilityService;
    }

    public Map<String, Object> parts(
            CurrentUserService.CurrentUser user,
            String category,
            String query,
            String manufacturer,
            String status,
            Integer minPrice,
            Integer maxPrice,
            Integer page,
            Integer size,
            String sort,
            String compatibilitySource
    ) {
        PartSearch search = new PartSearch(category, query, manufacturer, status, minPrice, maxPrice, page, size, sort);
        String normalizedCompatibilitySource = normalizeCompatibilitySource(compatibilitySource);
        if ("compatibility".equals(search.sort()) && (search.category() == null || normalizedCompatibilitySource == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "호환성순 정렬에는 category와 compatibilitySource가 필요합니다.");
        }
        if (normalizedCompatibilitySource != null && search.category() != null) {
            return partsWithCompatibility(user, search, normalizedCompatibilitySource);
        }
        return MockData.map(
                "items", partRows(search),
                "page", search.page(),
                "size", search.size(),
                "total", countParts(search)
        );
    }

    public Map<String, Object> parts(
            String category,
            String query,
            String manufacturer,
            String status,
            Integer minPrice,
            Integer maxPrice,
            Integer page,
            Integer size,
            String sort
    ) {
        return parts(null, category, query, manufacturer, status, minPrice, maxPrice, page, size, sort, null);
    }

    public Map<String, Object> part(String id) {
        return jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               bs.summary AS benchmark_summary,
                               bs.score AS benchmark_score,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.source
                                 ELSE ps.source
                               END AS latest_price_source,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.refreshed_at
                                 ELSE ps.collected_at
                               END AS latest_price_collected_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT b.summary, b.score
                          FROM benchmark_summaries b
                          WHERE b.part_id = p.id
                            AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC
                          LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.source, snapshot.collected_at
                          FROM price_snapshots snapshot
                          WHERE snapshot.part_id = p.id
                            AND snapshot.collected_at <= now()
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        LEFT JOIN LATERAL (
                          SELECT offer.*
                          FROM part_external_offers offer
                          WHERE offer.part_id = p.id
                            AND offer.deleted_at IS NULL
                          ORDER BY
                            CASE offer.source
                              WHEN 'NAVER_SHOPPING_SEARCH' THEN 1
                              WHEN 'ADMIN_MANUAL' THEN 2
                              ELSE 9
                            END,
                            offer.refreshed_at DESC,
                            offer.id DESC
                          LIMIT 1
                        ) peo ON true
                        WHERE p.public_id = ?::uuid
                          AND p.deleted_at IS NULL
                        """, id)
                .stream()
                .findFirst()
                .map(this::partMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."));
    }

    public Map<String, Object> priceHistory(String id, Integer days, String source, Integer limit) {
        Map<String, Object> part = internalPart(id);
        int safeDays = days == null ? 90 : Math.min(Math.max(days, 1), 3650);
        int safeLimit = limit == null ? 120 : Math.min(Math.max(limit, 1), 500);
        String normalizedSource = blankToNull(source);
        List<Object> params = new ArrayList<>();
        params.add(part.get("internal_id"));
        params.add(safeDays);
        String sourceFilter = "";
        if (normalizedSource != null) {
            sourceFilter = " AND ps.source = ?";
            params.add(normalizedSource);
        }
        params.add(safeLimit);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT price, source, collected_at
                        FROM (
                          SELECT ps.price,
                                 ps.source,
                                 ps.collected_at
                          FROM price_snapshots ps
                          WHERE ps.part_id = ?
                            AND ps.collected_at >= now() - (? * interval '1 day')
                            AND ps.collected_at <= now()
                        """ + sourceFilter + """
                          ORDER BY ps.collected_at DESC, ps.id DESC
                          LIMIT ?
                        ) latest_history
                        ORDER BY collected_at ASC
                        """, params.toArray())
                .stream()
                .map(this::pricePointMap)
                .toList();
        return MockData.map(
                "partId", DbValueMapper.string(part, "id"),
                "partName", DbValueMapper.string(part, "name"),
                "currentPrice", DbValueMapper.integer(part, "price"),
                "days", safeDays,
                "source", normalizedSource,
                "items", items,
                "summary", priceHistorySummary(items, DbValueMapper.integer(part, "price"))
        );
    }

    public Map<String, Object> toolResult(String toolName) {
        Map<String, Object> rule = ruleFor(toolName);
        String category = categoryForTool(toolName);
        String status = rule == null ? defaultStatus(toolName) : DbValueMapper.string(rule, "status");
        String summary = rule == null ? "DB seed result for " + toolName : DbValueMapper.string(rule, "summary");
        return MockData.map(
                "status", status,
                "confidence", "MEDIUM",
                "summary", summary,
                "details", MockData.map(
                        "checkedPartIds", toolReadyPartIds(category, 3),
                        "candidateCategory", category,
                        "source", "db-seed",
                        "toolName", toolName
                )
        );
    }

    private List<Map<String, Object>> partRows(PartSearch search) {
        SqlWhere where = whereClause(search);
        List<Object> params = new ArrayList<>(where.params());
        params.add(search.size());
        params.add(search.offset());
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               bs.summary AS benchmark_summary,
                               bs.score AS benchmark_score,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.source
                                 ELSE ps.source
                               END AS latest_price_source,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.refreshed_at
                                 ELSE ps.collected_at
                               END AS latest_price_collected_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT b.summary, b.score
                          FROM benchmark_summaries b
                          WHERE b.part_id = p.id
                            AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC
                          LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.source, snapshot.collected_at
                          FROM price_snapshots snapshot
                          WHERE snapshot.part_id = p.id
                            AND snapshot.collected_at <= now()
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        LEFT JOIN LATERAL (
                          SELECT offer.*
                          FROM part_external_offers offer
                          WHERE offer.part_id = p.id
                            AND offer.deleted_at IS NULL
                          ORDER BY
                            CASE offer.source
                              WHEN 'NAVER_SHOPPING_SEARCH' THEN 1
                              WHEN 'ADMIN_MANUAL' THEN 2
                              ELSE 9
                            END,
                            offer.refreshed_at DESC,
                            offer.id DESC
                          LIMIT 1
                        ) peo ON true
                        """ + where.sql() + " ORDER BY " + orderBy(search.sort()) + " LIMIT ? OFFSET ?",
                        params.toArray())
                .stream()
                .map(this::partMap)
                .toList();
    }

    private Map<String, Object> partsWithCompatibility(CurrentUserService.CurrentUser user, PartSearch search, String compatibilitySource) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        if ("compatibility".equals(search.sort())) {
            List<Map<String, Object>> rows = compatibilityService.partRowsWithCompatibility(
                    user,
                    compatibilitySource,
                    search.category(),
                    allPartRowsForCompatibility(search)
            ).stream()
                    .sorted(Comparator
                            .comparingInt(PartQueryService::compatibilityRank)
                            .thenComparingInt(row -> DbValueMapper.integer(row, "price") == null ? 0 : DbValueMapper.integer(row, "price")))
                    .toList();
            return MockData.map(
                    "items", paginate(rows, search).stream().map(this::stripInternalFields).toList(),
                    "page", search.page(),
                    "size", search.size(),
                    "total", rows.size()
            );
        }
        List<Map<String, Object>> rows = compatibilityService.partRowsWithCompatibility(
                user,
                compatibilitySource,
                search.category(),
                partRows(search)
        );
        return MockData.map(
                "items", rows.stream().map(this::stripInternalFields).toList(),
                "page", search.page(),
                "size", search.size(),
                "total", countParts(search)
        );
    }

    private List<Map<String, Object>> allPartRowsForCompatibility(PartSearch search) {
        SqlWhere where = whereClause(search);
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               bs.summary AS benchmark_summary,
                               bs.score AS benchmark_score,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.source
                                 ELSE ps.source
                               END AS latest_price_source,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.refreshed_at
                                 ELSE ps.collected_at
                               END AS latest_price_collected_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT b.summary, b.score
                          FROM benchmark_summaries b
                          WHERE b.part_id = p.id
                            AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC
                          LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.source, snapshot.collected_at
                          FROM price_snapshots snapshot
                          WHERE snapshot.part_id = p.id
                            AND snapshot.collected_at <= now()
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        LEFT JOIN part_external_offers peo
                          ON peo.part_id = p.id
                         AND peo.source = 'NAVER_SHOPPING_SEARCH'
                         AND peo.deleted_at IS NULL
                        """ + where.sql() + " ORDER BY p.price ASC, p.id ASC",
                        where.params().toArray());
    }

    private List<Map<String, Object>> paginate(List<Map<String, Object>> rows, PartSearch search) {
        int fromIndex = Math.min(search.offset(), rows.size());
        int toIndex = Math.min(fromIndex + search.size(), rows.size());
        return rows.subList(fromIndex, toIndex);
    }

    private Map<String, Object> stripInternalFields(Map<String, Object> row) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
        copy.remove("internal_id");
        return copy;
    }

    private Integer countParts(PartSearch search) {
        SqlWhere where = whereClause(search);
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM parts p
                """ + where.sql(), Integer.class, where.params().toArray());
    }

    private Map<String, Object> partMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "category", DbValueMapper.string(row, "category"),
                "name", DbValueMapper.string(row, "name"),
                "manufacturer", DbValueMapper.string(row, "manufacturer"),
                "price", DbValueMapper.integer(row, "price"),
                "status", DbValueMapper.string(row, "status"),
                "attributes", DbValueMapper.json(row, "attributes", Map.of()),
                "benchmarkSummary", benchmarkSummary(row),
                "latestPriceSource", DbValueMapper.string(row, "latest_price_source"),
                "latestPriceCollectedAt", DbValueMapper.timestamp(row, "latest_price_collected_at"),
                "externalOffer", externalOffer(row)
        );
    }

    private Map<String, Object> internalPart(String id) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               name,
                               price
                        FROM parts
                        WHERE public_id = ?::uuid
                          AND deleted_at IS NULL
                        """, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."));
    }

    private Map<String, Object> pricePointMap(Map<String, Object> row) {
        return MockData.map(
                "price", DbValueMapper.integer(row, "price"),
                "source", DbValueMapper.string(row, "source"),
                "collectedAt", DbValueMapper.timestamp(row, "collected_at")
        );
    }

    private static Map<String, Object> priceHistorySummary(List<Map<String, Object>> items, Integer currentPrice) {
        if (items.isEmpty()) {
            return MockData.map(
                    "sampleCount", 0,
                    "currentPrice", currentPrice,
                    "minPrice", currentPrice,
                    "maxPrice", currentPrice,
                    "firstPrice", currentPrice,
                    "lastPrice", currentPrice,
                    "changeAmount", 0,
                    "changeRatePercent", 0.0
            );
        }
        List<Integer> prices = items.stream()
                .map(item -> (Integer) item.get("price"))
                .filter(price -> price != null)
                .toList();
        Integer firstPrice = prices.get(0);
        Integer lastPrice = prices.get(prices.size() - 1);
        int changeAmount = lastPrice - firstPrice;
        double changeRatePercent = firstPrice == 0 ? 0.0 : Math.round((changeAmount * 10000.0 / firstPrice)) / 100.0;
        return MockData.map(
                "sampleCount", prices.size(),
                "currentPrice", currentPrice,
                "minPrice", prices.stream().min(Comparator.naturalOrder()).orElse(currentPrice),
                "maxPrice", prices.stream().max(Comparator.naturalOrder()).orElse(currentPrice),
                "firstPrice", firstPrice,
                "lastPrice", lastPrice,
                "changeAmount", changeAmount,
                "changeRatePercent", changeRatePercent
        );
    }

    private static Map<String, Object> benchmarkSummary(Map<String, Object> row) {
        String summary = DbValueMapper.string(row, "benchmark_summary");
        if (summary == null) {
            return null;
        }
        return MockData.map("summary", summary, "score", row.get("benchmark_score"));
    }

    private static Map<String, Object> externalOffer(Map<String, Object> row) {
        String source = DbValueMapper.string(row, "external_offer_source");
        if (source == null) {
            return null;
        }
        return MockData.map(
                "title", DbValueMapper.string(row, "external_offer_title"),
                "imageUrl", DbValueMapper.string(row, "external_offer_image_url"),
                "supplierName", DbValueMapper.string(row, "external_offer_supplier_name"),
                "offerUrl", DbValueMapper.string(row, "external_offer_url"),
                "lowPrice", DbValueMapper.integer(row, "external_offer_low_price"),
                "source", source,
                "refreshedAt", DbValueMapper.timestamp(row, "external_offer_refreshed_at")
        );
    }

    private static SqlWhere whereClause(PartSearch search) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        clauses.add("p.deleted_at IS NULL");
        if (search.category() != null) {
            clauses.add("p.category = ?");
            params.add(search.category());
        }
        if (search.status() != null) {
            clauses.add("p.status = ?");
            params.add(search.status());
        }
        if (search.manufacturer() != null) {
            clauses.add("lower(p.manufacturer) LIKE lower(concat('%', ?, '%'))");
            params.add(search.manufacturer());
        }
        if (search.query() != null) {
            clauses.add("""
                    (
                      lower(p.name) LIKE lower(concat('%', ?, '%'))
                      OR lower(coalesce(p.manufacturer, '')) LIKE lower(concat('%', ?, '%'))
                      OR lower(coalesce(p.attributes::text, '')) LIKE lower(concat('%', ?, '%'))
                    )
                    """);
            params.add(search.query());
            params.add(search.query());
            params.add(search.query());
        }
        if (search.minPrice() != null) {
            clauses.add("p.price >= ?");
            params.add(search.minPrice());
        }
        if (search.maxPrice() != null) {
            clauses.add("p.price <= ?");
            params.add(search.maxPrice());
        }
        return new SqlWhere("WHERE " + String.join(" AND ", clauses), params);
    }

    private static String orderBy(String sort) {
        return switch (sort) {
            case "price_asc" -> "p.price ASC, p.id ASC";
            case "price_desc" -> "p.price DESC, p.id ASC";
            case "name" -> "p.name ASC, p.id ASC";
            default -> """
                    CASE p.category
                      WHEN 'CPU' THEN 1
                      WHEN 'MOTHERBOARD' THEN 2
                      WHEN 'RAM' THEN 3
                      WHEN 'GPU' THEN 4
                      WHEN 'STORAGE' THEN 5
                      WHEN 'PSU' THEN 6
                      WHEN 'CASE' THEN 7
                      WHEN 'COOLER' THEN 8
                      ELSE 99
                    END,
                    p.id ASC
                    """;
        };
    }

    private Map<String, Object> ruleFor(String toolName) {
        String category = categoryForTool(toolName);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT result_status AS status, message AS summary
                FROM compatibility_rules
                WHERE category = ?
                  AND deleted_at IS NULL
                ORDER BY id
                LIMIT 1
                """, category);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<String> toolReadyPartIds(String category, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT public_id::text
                FROM parts
                WHERE category = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                  AND coalesce((attributes->>'toolReady')::boolean, false) = true
                ORDER BY price ASC, id ASC
                LIMIT ?
                """, String.class, category, limit);
    }

    private static String categoryForTool(String toolName) {
        return switch (toolName) {
            case "compatibility" -> "CPU";
            case "size" -> "GPU";
            case "power" -> "PSU";
            case "performance" -> "GPU";
            case "price" -> "GPU";
            default -> "CPU";
        };
    }

    private static String defaultStatus(String toolName) {
        return "compatibility".equals(toolName) || "size".equals(toolName) ? "PASS" : "WARN";
    }

    private record SqlWhere(String sql, List<Object> params) {
    }

    private record PartSearch(
            String category,
            String query,
            String manufacturer,
            String status,
            Integer minPrice,
            Integer maxPrice,
            int page,
            int size,
            String sort
    ) {
        private PartSearch(
                String category,
                String query,
                String manufacturer,
                String status,
                Integer minPrice,
                Integer maxPrice,
                Integer page,
                Integer size,
                String sort
        ) {
            this(
                    normalizeEnum(category, CATEGORIES, "지원하지 않는 부품 category입니다."),
                    blankToNull(query),
                    blankToNull(manufacturer),
                    normalizeStatus(status),
                    positiveOrNull(minPrice, "minPrice는 0 이상이어야 합니다."),
                    positiveOrNull(maxPrice, "maxPrice는 0 이상이어야 합니다."),
                    page == null ? 0 : Math.max(page, 0),
                    size == null ? 20 : Math.min(Math.max(size, 1), 100),
                    normalizeSort(sort)
            );
            if (this.minPrice != null && this.maxPrice != null && this.minPrice > this.maxPrice) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minPrice는 maxPrice보다 클 수 없습니다.");
            }
        }

        static PartSearch defaults() {
            return new PartSearch(null, null, null, null, null, null, Integer.valueOf(0), Integer.valueOf(20), null);
        }

        int offset() {
            return page * size;
        }
    }

    private static String normalizeStatus(String value) {
        String normalized = normalizeEnum(value, STATUSES, "지원하지 않는 부품 status입니다.");
        return normalized == null ? "ACTIVE" : normalized;
    }

    private static String normalizeEnum(String value, Set<String> allowedValues, String message) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String upper = normalized.toUpperCase();
        if (!allowedValues.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return upper;
    }

    private static String normalizeSort(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return "category";
        }
        if (Set.of("category", "price_asc", "price_desc", "name").contains(normalized)) {
            return normalized;
        }
        if ("compatibility".equals(normalized)) {
            return normalized;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 sort입니다.");
    }

    private static String normalizeCompatibilitySource(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String upper = normalized.toUpperCase();
        if ("QUOTE_DRAFT_CURRENT".equals(upper)) {
            return upper;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 compatibilitySource입니다.");
    }

    @SuppressWarnings("unchecked")
    private static int compatibilityRank(Map<String, Object> row) {
        Object value = row.get("compatibility");
        if (!(value instanceof Map<?, ?> map)) {
            return 3;
        }
        Object status = map.get("status");
        if ("PASS".equals(status)) {
            return 0;
        }
        if ("WARN".equals(status)) {
            return 1;
        }
        if ("FAIL".equals(status)) {
            return 2;
        }
        return 3;
    }

    private static Integer positiveOrNull(Integer value, String message) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
