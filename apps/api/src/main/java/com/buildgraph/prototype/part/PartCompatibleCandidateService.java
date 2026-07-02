package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PartCompatibleCandidateService {
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;

    public PartCompatibleCandidateService(JdbcTemplate jdbcTemplate, ToolCheckService toolCheckService) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCheckService = toolCheckService;
    }

    public Map<String, Object> compatibleCandidates(CurrentUserService.CurrentUser user, Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String source = firstText(text(body.get("source")), "AI_BUILD").toUpperCase(Locale.ROOT);
        String category = normalizeCategory(text(body.get("category")));
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 부품 카테고리입니다.");
        }
        int limit = Math.min(Math.max(firstNumber(body.get("limit"), 5), 1), 10);
        List<ToolBuildPart> baseParts = switch (source) {
            case "AI_BUILD" -> aiBuildParts(body);
            case "QUOTE_DRAFT_CURRENT" -> currentQuoteDraftParts(user);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 후보 source입니다.");
        };
        List<String> checkedTools = checkedTools(category);
        List<String> selectedPartIds = baseParts.stream()
                .filter(part -> category.equals(part.category()))
                .map(ToolBuildPart::publicId)
                .toList();
        List<CandidateEvaluation> evaluations = activeCandidates(category, Math.max(20, limit * 4)).stream()
                .filter(candidate -> !selectedPartIds.contains(candidate.toolPart().publicId()))
                .map(candidate -> evaluate(baseParts, candidate, category, checkedTools))
                .sorted(Comparator
                        .comparingInt((CandidateEvaluation evaluation) -> statusRank(evaluation.status()))
                        .thenComparingInt(evaluation -> firstNumber(evaluation.partMap().get("price"), 0)))
                .toList();
        List<Map<String, Object>> accepted = evaluations.stream()
                .filter(evaluation -> !"FAIL".equals(evaluation.status()))
                .limit(limit)
                .map(CandidateEvaluation::response)
                .toList();
        long rejectedCount = evaluations.stream().filter(evaluation -> "FAIL".equals(evaluation.status())).count();
        return MockData.map(
                "category", category,
                "items", accepted,
                "rejectedCount", (int) rejectedCount,
                "warnings", List.of()
        );
    }

    public List<Map<String, Object>> partRowsWithCompatibility(
            CurrentUserService.CurrentUser user,
            String source,
            String category,
            List<Map<String, Object>> rows
    ) {
        String normalizedSource = firstText(text(source), "QUOTE_DRAFT_CURRENT").toUpperCase(Locale.ROOT);
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 부품 카테고리입니다.");
        }
        List<ToolBuildPart> baseParts = switch (normalizedSource) {
            case "QUOTE_DRAFT_CURRENT" -> currentQuoteDraftParts(user);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 compatibilitySource입니다.");
        };
        List<String> checkedTools = checkedTools(normalizedCategory);
        return rows.stream()
                .map(row -> {
                    CandidateEvaluation evaluation = evaluate(baseParts, new CandidatePart(toolPart(row), responsePart(row)), normalizedCategory, checkedTools);
                    Map<String, Object> part = new LinkedHashMap<>(evaluation.partMap());
                    part.put("compatibility", evaluation.partListCompatibility());
                    return part;
                })
                .toList();
    }

    private List<ToolBuildPart> aiBuildParts(Map<String, Object> body) {
        List<Map<String, Object>> items = objectList(body.get("items"));
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_BUILD 후보 계산에는 items가 필요합니다.");
        }
        List<ToolBuildPart> parts = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String partId = text(item.get("partId"));
            String requestedCategory = normalizeCategory(text(item.get("category")));
            if (partId == null || requestedCategory == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId와 category가 필요합니다.");
            }
            ToolBuildPart part = partByPublicId(partId);
            if (!requestedCategory.equals(part.category())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId와 category가 일치하지 않습니다.");
            }
            parts.add(part);
        }
        return parts;
    }

    private List<ToolBuildPart> currentQuoteDraftParts(CurrentUserService.CurrentUser user) {
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList("""
                SELECT id AS internal_id,
                       public_id::text AS id,
                       status,
                       name
                FROM quote_drafts
                WHERE user_id = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """, user.internalId());
        if (drafts.isEmpty()) {
            return List.of();
        }
        Long draftId = longValue(drafts.get(0).get("internal_id"));
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS part_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price AS current_price,
                               p.price,
                               qdi.quantity,
                               p.attributes
                        FROM quote_draft_items qdi
                        JOIN parts p ON p.id = qdi.part_id
                        WHERE qdi.quote_draft_id = ?
                          AND qdi.deleted_at IS NULL
                          AND p.deleted_at IS NULL
                        ORDER BY qdi.created_at ASC, qdi.id ASC
                        """, draftId)
                .stream()
                .map(this::toolPart)
                .toList();
    }

    private ToolBuildPart partByPublicId(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               status,
                               attributes
                        FROM parts
                        WHERE public_id = ?::uuid
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .map(this::toolPart)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."));
    }

    private List<CandidatePart> activeCandidates(String category, int queryLimit) {
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
                        WHERE p.category = ?
                          AND p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                        ORDER BY p.price ASC, p.id ASC
                        LIMIT ?
                        """, category, queryLimit)
                .stream()
                .map(row -> new CandidatePart(toolPart(row), partMap(row)))
                .toList();
    }

    private CandidateEvaluation evaluate(List<ToolBuildPart> baseParts, CandidatePart candidate, String category, List<String> checkedTools) {
        if (checkedTools.isEmpty()) {
            return new CandidateEvaluation(candidate.partMap(), "PASS", "ACTIVE 부품 후보입니다.", checkedTools);
        }
        List<ToolBuildPart> nextParts = new ArrayList<>(baseParts.stream()
                .filter(part -> !category.equals(part.category()))
                .toList());
        nextParts.add(candidate.toolPart());
        List<Map<String, Object>> toolResults = toolCheckService.checkBuild(nextParts, total(nextParts));
        List<Map<String, Object>> relevantResults = toolResults.stream()
                .filter(result -> checkedTools.contains(text(result.get("tool"))))
                .toList();
        String status = worstStatus(relevantResults);
        String summary = summary(status, relevantResults);
        return new CandidateEvaluation(candidate.partMap(), status, summary, checkedTools);
    }

    private static String summary(String status, List<Map<String, Object>> toolResults) {
        if ("PASS".equals(status)) {
            return "현재 조합 기준 호환 가능합니다.";
        }
        return toolResults.stream()
                .filter(result -> status.equals(text(result.get("status"))))
                .map(result -> text(result.get("summary")))
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse("현재 조합 기준 추가 확인이 필요합니다.");
    }

    private static String worstStatus(List<Map<String, Object>> toolResults) {
        if (toolResults.stream().anyMatch(result -> "FAIL".equals(text(result.get("status"))))) {
            return "FAIL";
        }
        if (toolResults.stream().anyMatch(result -> "WARN".equals(text(result.get("status"))))) {
            return "WARN";
        }
        return "PASS";
    }

    private static List<String> checkedTools(String category) {
        return switch (category) {
            case "CPU", "MOTHERBOARD", "RAM", "COOLER" -> List.of("compatibility");
            case "GPU" -> List.of("power", "size", "performance");
            case "PSU" -> List.of("power");
            case "CASE" -> List.of("size");
            default -> List.of();
        };
    }

    private Map<String, Object> partMap(Map<String, Object> row) {
        return MockData.map(
                "id", firstText(DbValueMapper.string(row, "id"), DbValueMapper.string(row, "part_id")),
                "category", DbValueMapper.string(row, "category"),
                "name", DbValueMapper.string(row, "name"),
                "manufacturer", DbValueMapper.string(row, "manufacturer"),
                "price", firstNumber(row.get("price"), firstNumber(row.get("current_price"), 0)),
                "status", DbValueMapper.string(row, "status"),
                "attributes", objectMap(row.get("attributes")),
                "benchmarkSummary", benchmarkSummary(row),
                "latestPriceSource", DbValueMapper.string(row, "latest_price_source"),
                "latestPriceCollectedAt", DbValueMapper.timestamp(row, "latest_price_collected_at"),
                "externalOffer", externalOffer(row)
        );
    }

    private Map<String, Object> responsePart(Map<String, Object> row) {
        if (row.containsKey("externalOffer") || row.containsKey("latestPriceSource") || row.containsKey("benchmarkSummary")) {
            return new LinkedHashMap<>(row);
        }
        return partMap(row);
    }

    private ToolBuildPart toolPart(Map<String, Object> row) {
        return new ToolBuildPart(
                longValue(row.get("internal_id")),
                firstText(DbValueMapper.string(row, "id"), DbValueMapper.string(row, "part_id")),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                firstNumber(row.get("price"), firstNumber(row.get("current_price"), 0)),
                objectMap(row.get("attributes"))
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

    private static String normalizeCategory(String category) {
        if (category == null) {
            return null;
        }
        String normalized = category.toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : null;
    }

    private static int total(List<ToolBuildPart> parts) {
        return parts.stream()
                .mapToInt(part -> firstNumber(part.price(), 0))
                .sum();
    }

    private static int statusRank(String status) {
        if ("PASS".equals(status)) {
            return 0;
        }
        if ("WARN".equals(status)) {
            return 1;
        }
        return 2;
    }

    private static String statusLabel(String status) {
        if ("FAIL".equals(status)) {
            return "장착 불가";
        }
        if ("WARN".equals(status)) {
            return "간섭 주의";
        }
        return "여유 있음";
    }

    private static String partListStatusLabel(String status) {
        if ("FAIL".equals(status)) {
            return "안 맞음";
        }
        if ("WARN".equals(status)) {
            return "간섭 주의";
        }
        return "호환됨";
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static int firstNumber(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    @SafeVarargs
    private static <T> T firstText(T... values) {
        for (T value : values) {
            if (value instanceof String text && !text.isBlank()) {
                return value;
            }
            if (value != null && !(value instanceof String)) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        Object parsed = DbValueMapper.json(Map.of("value", value), "value", Map.of());
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        ((Map<?, ?>) item).forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
                        return result;
                    })
                    .toList();
        }
        return List.of();
    }

    private record CandidatePart(ToolBuildPart toolPart, Map<String, Object> partMap) {
    }

    private record CandidateEvaluation(Map<String, Object> partMap, String status, String summary, List<String> checkedTools) {
        private Map<String, Object> response() {
            return MockData.map(
                    "part", partMap,
                    "status", status,
                    "statusLabel", statusLabel(status),
                    "summary", summary,
                    "checkedTools", checkedTools
            );
        }

        private Map<String, Object> partListCompatibility() {
            return MockData.map(
                    "status", status,
                    "statusLabel", partListStatusLabel(status),
                    "summary", summary,
                    "checkedTools", checkedTools
            );
        }
    }
}
