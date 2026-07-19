package com.buildgraph.prototype.part.catalog;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ReadThroughTtlCache;
import com.buildgraph.prototype.part.query.PartDetailCachedLoader;
import com.buildgraph.prototype.part.query.PartDetailDto;
import com.buildgraph.prototype.recommendation.PartContextRecommendationService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PartQueryService {
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "DISCONTINUED");
    // 명시적 정렬 — 정렬 기준이 draft와 무관해 SQL ORDER BY+LIMIT으로 페이지 행만 뽑아 평가할 수 있는 sort들.
    private static final Set<String> PAGE_SCOPED_SORTS = Set.of("price_asc", "price_desc", "name");
    private final JdbcTemplate jdbcTemplate;
    private final PartCompatibleCandidateService compatibilityService;
    private final PartDetailCachedLoader partDetailCachedLoader;
    private final PartContextRecommendationService recommendationService;
    // 유저 무관 카탈로그 조회(순수 parts 경로)만 짧은 TTL 캐시. draft 기반 compatibility 경로는 캐시하지 않는다.
    private final ReadThroughTtlCache<PartSearch, Map<String, Object>> partsCache;
    // draft 기반 호환성 평가의 "평가·추천·정렬 완료본"(페이지 자르기 전) 단기 캐시.
    // 키에 draft 내용 서명(draftSignature)이 들어가 draft 변경은 TTL과 무관하게 즉시 새 키로 미스된다 —
    // "사용자 draft에 따라 응답이 달라진다"는 무캐시 사유를 지키면서, 같은 draft로 페이지를 넘길 때마다
    // 카테고리 전량을 재평가하던 중복 작업(부하 실측 트래픽 55% 경로)만 제거한다.
    private final ReadThroughTtlCache<CompatibilityRowsKey, List<Map<String, Object>>> compatibilityRowsCache;
    // 페이지-스코프 평가(명시적 정렬) 응답 캐시 — 값에 total까지 담아 히트 시 DB 왕복이 draft 서명 1쿼리로 끝난다.
    private final ReadThroughTtlCache<CompatibilityRowsKey, Map<String, Object>> pagedCompatibilityCache;

    @Autowired
    public PartQueryService(
            JdbcTemplate jdbcTemplate,
            PartCompatibleCandidateService compatibilityService,
            PartContextRecommendationService recommendationService,
            PartDetailCachedLoader partDetailCachedLoader,
            @Value("${part.query-cache.ttl-seconds:30}") long cacheTtlSeconds,
            @Value("${part.compatibility-cache.ttl-seconds:15}") long compatibilityCacheTtlSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.compatibilityService = compatibilityService;
        this.recommendationService = recommendationService;
        this.partDetailCachedLoader = partDetailCachedLoader;
        this.partsCache = new ReadThroughTtlCache<>(Duration.ofSeconds(cacheTtlSeconds), 512);
        this.compatibilityRowsCache = new ReadThroughTtlCache<>(Duration.ofSeconds(compatibilityCacheTtlSeconds), 1024);
        this.pagedCompatibilityCache = new ReadThroughTtlCache<>(Duration.ofSeconds(compatibilityCacheTtlSeconds), 2048);
    }

    // 편의 생성자(테스트/내부용) — 캐시 기본 TTL(카탈로그 30초, 호환성 15초).
    public PartQueryService(
            JdbcTemplate jdbcTemplate,
            PartCompatibleCandidateService compatibilityService,
            PartContextRecommendationService recommendationService,
            PartDetailCachedLoader partDetailCachedLoader
    ) {
        this(jdbcTemplate, compatibilityService, recommendationService, partDetailCachedLoader, 30L, 15L);
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
            String compatibilitySource,
            String compatibilityMode,
            String replaceTargetPartId
    ) {
        PartSearch search = new PartSearch(category, query, manufacturer, status, minPrice, maxPrice, page, size, sort);
        String normalizedCompatibilitySource = normalizeCompatibilitySource(compatibilitySource);
        if ("compatibility".equals(search.sort()) && (search.category() == null || normalizedCompatibilitySource == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "호환성순 정렬에는 category와 compatibilitySource가 필요합니다.");
        }
        if (normalizedCompatibilitySource != null && search.category() != null) {
            // compatibilityMode/replaceTargetPartId는 호환 평가가 켜졌을 때만 의미가 있다 —
            // compatibilitySource 단독 무시 규칙과 같은 원칙으로, 평가 없는 조회에서는 무시한다.
            // 이 경로는 사용자 draft에 따라 응답이 달라지므로 캐시하지 않는다.
            return partsWithCompatibility(user, search, normalizedCompatibilitySource, compatibilityMode, replaceTargetPartId);
        }
        // 순수 카탈로그 조회는 사용자 무관이라 검색 조건(PartSearch)만으로 캐시된다 — 반복 목록·홈 조회 가속.
        return partsCache.get(search, () -> MockData.map(
                "items", partRows(search).stream().map(this::stripInternalFields).toList(),
                "page", search.page(),
                "size", search.size(),
                "total", countParts(search)
        ));
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
        return parts(null, category, query, manufacturer, status, minPrice, maxPrice, page, size, sort, null, null, null);
    }

    public Map<String, Object> topActivePartsByCategoryPriceDesc(List<String> categories, int sizePerCategory) {
        List<String> safeCategories = new ArrayList<>();
        for (String category : categories) {
            String normalized = normalizeEnum(category, CATEGORIES, "吏?먰븯吏 ?딅뒗 遺??category?낅땲??");
            if (normalized != null && !safeCategories.contains(normalized)) {
                safeCategories.add(normalized);
            }
        }
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (String category : safeCategories) {
            grouped.put(category, new ArrayList<>());
        }
        if (safeCategories.isEmpty()) {
            return new LinkedHashMap<>();
        }

        int safeSize = Math.min(Math.max(sizePerCategory, 1), 100);
        List<Object> params = new ArrayList<>(safeCategories);
        params.add(safeSize);
        jdbcTemplate.queryForList("""
                        WITH requested_categories(category, category_order) AS (
                          VALUES """ + categoryValues(safeCategories.size()) + """
                        )
                        SELECT part.internal_id,
                               part.id,
                               part.category,
                               part.name,
                               part.manufacturer,
                               part.price,
                               part.status,
                               part.attributes,
                               bs.summary AS benchmark_summary,
                               bs.score AS benchmark_score,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = part.price THEN peo.source
                                 ELSE ps.source
                               END AS latest_price_source,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = part.price THEN peo.refreshed_at
                                 ELSE ps.collected_at
                               END AS latest_price_collected_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM requested_categories requested
                        JOIN LATERAL (
                          SELECT p.id AS internal_id,
                                 p.public_id::text AS id,
                                 p.category,
                                 p.name,
                                 p.manufacturer,
                                 p.price,
                                 p.status,
                                 p.attributes
                          FROM parts p
                          WHERE p.deleted_at IS NULL
                            AND p.status = 'ACTIVE'
                            AND p.category = requested.category
                          ORDER BY p.price DESC, p.id ASC
                          LIMIT ?
                        ) part ON true
                        LEFT JOIN LATERAL (
                          SELECT b.summary, b.score
                          FROM benchmark_summaries b
                          WHERE b.part_id = part.internal_id
                            AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC
                          LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.source, snapshot.collected_at
                          FROM price_snapshots snapshot
                          WHERE snapshot.part_id = part.internal_id
                            AND snapshot.collected_at <= now()
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        LEFT JOIN LATERAL (
                          SELECT offer.*
                          FROM part_external_offers offer
                          WHERE offer.part_id = part.internal_id
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
                        ORDER BY requested.category_order ASC, part.price DESC, part.internal_id ASC
                        """, params.toArray())
                .forEach(row -> {
                    String category = DbValueMapper.string(row, "category");
                    List<Map<String, Object>> items = grouped.get(category);
                    if (items != null) {
                        items.add(stripInternalFields(partMap(row)));
                    }
                });

        Map<String, Object> categoryParts = new LinkedHashMap<>();
        grouped.forEach(categoryParts::put);
        return categoryParts;
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
        List<String> publicIds = partPublicIds(search, orderBy(search.sort()), true);
        return detailRows(publicIds);
    }
    private Map<String, Object> partsWithCompatibility(CurrentUserService.CurrentUser user, PartSearch search, String compatibilitySource, String compatibilityMode, String replaceTargetPartId) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        if (PAGE_SCOPED_SORTS.contains(search.sort())) {
            return pagedCompatibilityParts(user, search, compatibilitySource, compatibilityMode, replaceTargetPartId);
        }
        // 키는 page/size를 제외한 전 파라미터 + draft 내용 서명 — 페이지 넘김은 같은 평가본을 재사용하고,
        // draft가 바뀌면 서명이 바뀌어 즉시 재평가된다.
        CompatibilityRowsKey key = new CompatibilityRowsKey(
                draftSignature(user),
                search.category(),
                search.query(),
                search.manufacturer(),
                search.status(),
                search.minPrice(),
                search.maxPrice(),
                search.sort(),
                compatibilitySource,
                blankToNull(compatibilityMode),
                blankToNull(replaceTargetPartId),
                null,
                null
        );
        List<Map<String, Object>> rows = compatibilityRowsCache.get(key,
                () -> evaluatedCompatibilityRows(user, search, compatibilitySource, compatibilityMode, replaceTargetPartId));
        return MockData.map(
                "items", paginate(rows, search).stream().map(this::stripInternalFields).toList(),
                "page", search.page(),
                "size", search.size(),
                "total", rows.size()
        );
    }

    /**
     * 명시적 정렬(price_asc/price_desc/name)의 호환성 후보 경로 — 정렬이 draft와 무관하므로
     * SQL ORDER BY+LIMIT으로 페이지 행만 뽑아 그 부분만 평가한다. 미스 비용이 카테고리 크기(32~87)와
     * 무관하게 페이지 크기로 고정된다 (부하 실측 55% 트래픽이 price_asc — 2026-07-19).
     * 추천 톱3는 전량 평가 없이는 "전체 기준 톱3"를 계산할 수 없어, 이 경로에서는 페이지 내 PASS 후보
     * 기준 뱃지로만 달고 상단 고정(재정렬)을 하지 않는다 — 고정은 기본·호환 정렬에서 유지(제품 결정).
     */
    private Map<String, Object> pagedCompatibilityParts(CurrentUserService.CurrentUser user, PartSearch search, String compatibilitySource, String compatibilityMode, String replaceTargetPartId) {
        CompatibilityRowsKey key = new CompatibilityRowsKey(
                draftSignature(user),
                search.category(),
                search.query(),
                search.manufacturer(),
                search.status(),
                search.minPrice(),
                search.maxPrice(),
                search.sort(),
                compatibilitySource,
                blankToNull(compatibilityMode),
                blankToNull(replaceTargetPartId),
                search.page(),
                search.size()
        );
        return pagedCompatibilityCache.get(key, () -> {
            List<Map<String, Object>> pageRows = detailRows(partPublicIds(search, orderBy(search.sort()), true));
            List<Map<String, Object>> evaluated = compatibilityService.partRowsWithCompatibility(
                    user,
                    compatibilitySource,
                    search.category(),
                    compatibilityMode,
                    replaceTargetPartId,
                    pageRows
            );
            return MockData.map(
                    "items", recommendationService.annotate(search.category(), evaluated).stream()
                            .map(this::stripInternalFields)
                            .toList(),
                    "page", search.page(),
                    "size", search.size(),
                    "total", countParts(search)
            );
        });
    }

    private List<Map<String, Object>> evaluatedCompatibilityRows(CurrentUserService.CurrentUser user, PartSearch search, String compatibilitySource, String compatibilityMode, String replaceTargetPartId) {
        // 추천 후보가 어느 페이지에 있든 목록 최상단에 고정하려면 전체 필터 결과를 같은 Tool 정책으로
        // 평가한 뒤 추천 점수와 사용자가 고른 보조 정렬을 적용하고 마지막에 페이지를 잘라야 한다.
        List<Map<String, Object>> evaluatedRows = compatibilityService.partRowsWithCompatibility(
                user,
                compatibilitySource,
                search.category(),
                compatibilityMode,
                replaceTargetPartId,
                partRowsForCompatibility(search)
        );
        return recommendationService.annotate(search.category(), evaluatedRows).stream()
                .sorted(candidateComparator(search.sort()))
                .toList();
    }

    /**
     * 활성 draft의 내용 서명 — (draft id, 항목별 part_id×quantity)만 읽는 가벼운 1쿼리.
     * 담기/교체(upsert)·AI 적용·수량 변경·삭제(soft-delete) 전부가 서명을 바꿔 캐시 키가 즉시 갈린다.
     * quote_drafts.updated_at은 수량 변경·삭제 경로에서 갱신되지 않아 버전 키로 쓸 수 없다.
     * draft가 없으면 고정 서명 — 빈 draft 사용자끼리는 같은 평가본을 공유해도 결과가 같다.
     */
    private String draftSignature(CurrentUserService.CurrentUser user) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT qd.id AS draft_id, qdi.part_id, qdi.quantity
                FROM (
                  SELECT id
                  FROM quote_drafts
                  WHERE user_id = ?
                    AND status = 'ACTIVE'
                    AND deleted_at IS NULL
                  ORDER BY updated_at DESC, id DESC
                  LIMIT 1
                ) qd
                LEFT JOIN quote_draft_items qdi
                  ON qdi.quote_draft_id = qd.id
                 AND qdi.deleted_at IS NULL
                ORDER BY qdi.part_id ASC, qdi.id ASC
                """, user.internalId());
        if (rows.isEmpty()) {
            return "no-draft";
        }
        StringBuilder signature = new StringBuilder();
        for (Map<String, Object> row : rows) {
            signature.append(row.get("draft_id")).append(':')
                    .append(row.get("part_id")).append('x')
                    .append(row.get("quantity")).append(';');
        }
        return signature.toString();
    }
    private List<Map<String, Object>> partRowsForCompatibility(PartSearch search) {
        List<String> publicIds = partPublicIds(search, "p.price ASC, p.id ASC", false);
        return detailRows(publicIds);
    }

    /* 검색·정렬은 DB가 담당하고 상세 본문은 public ID 기반 통합 캐시에서 읽는다. */
    private List<String> partPublicIds(PartSearch search, String order, boolean paginate) {
        SqlWhere where = whereClause(search);
        List<Object> params = new ArrayList<>(where.params());
        String paging = "";
        if (paginate) {
            paging = " LIMIT ? OFFSET ?";
            params.add(search.size());
            params.add(search.offset());
        }
        return jdbcTemplate.queryForList(
                "SELECT p.public_id::text FROM parts p " + where.sql() + " ORDER BY " + order + paging,
                String.class,
                params.toArray()
        );
    }

    private List<Map<String, Object>> detailRows(List<String> publicIds) {
        if (publicIds.isEmpty()) {
            return List.of();
        }
        return partDetailCachedLoader.detailsByPublicIds(publicIds).stream()
                .map(this::partMap)
                .toList();
    }
    private List<Map<String, Object>> paginate(List<Map<String, Object>> rows, PartSearch search) {
        int fromIndex = Math.min(search.offset(), rows.size());
        int toIndex = Math.min(fromIndex + search.size(), rows.size());
        return rows.subList(fromIndex, toIndex);
    }

    private Map<String, Object> stripInternalFields(Map<String, Object> row) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
        copy.remove("internal_id");
        copy.remove("_candidateToolResults");
        copy.remove("_recommendationContext");
        return copy;
    }

    private Integer countParts(PartSearch search) {
        SqlWhere where = whereClause(search);
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM parts p
                """ + where.sql(), Integer.class, where.params().toArray());
    }

    private Map<String, Object> partMap(PartDetailDto detail) {
        var part = detail.part();
        Map<String, Object> latestPrice = detail.latestPrice();
        return MockData.map(
                "internal_id", part.internalId(),
                "id", part.publicId(),
                "category", part.category(),
                "name", part.name(),
                "manufacturer", part.manufacturer(),
                "price", part.price(),
                "status", detail.status(),
                "attributes", part.attributes(),
                "benchmarkSummary", detail.benchmark(),
                "latestPriceSource", latestPrice == null ? null : latestPrice.get("source"),
                "latestPriceCollectedAt", latestPrice == null ? null : latestPrice.get("collectedAt"),
                "externalOffer", detail.externalOffer()
        );
    }

    private Map<String, Object> partMap(Map<String, Object> row) {
        return MockData.map(
                // 호환 평가(performance 벤치마크 조회)가 internal_id를 쓴다 — 응답 직전 stripInternalFields로 제거된다.
                "internal_id", row.get("internal_id"),
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
        } else {
            // 사용자용 목록 기본값: 인테이크가 INACTIVE로 만든 미공개 부품이 후보에 노출되면
            // 담기(PUT)가 404로 실패한다 — status 미지정 시 판매 중 부품만 보여준다.
            clauses.add("p.status = 'ACTIVE'");
        }
        if (search.manufacturer() != null) {
            clauses.add("lower(p.manufacturer) LIKE lower(concat('%', ?, '%')) ESCAPE '\\'");
            params.add(escapeLike(search.manufacturer()));
        }
        if (search.query() != null) {
            clauses.add("""
                    (
                      lower(p.name) LIKE lower(concat('%', ?, '%')) ESCAPE '\\'
                      OR lower(coalesce(p.manufacturer, '')) LIKE lower(concat('%', ?, '%')) ESCAPE '\\'
                      OR lower(coalesce(p.attributes::text, '')) LIKE lower(concat('%', ?, '%')) ESCAPE '\\'
                    )
                    """);
            String escapedQuery = escapeLike(search.query());
            params.add(escapedQuery);
            params.add(escapedQuery);
            params.add(escapedQuery);
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

    // 검색어의 %·_가 LIKE 와일드카드로 동작해 필터가 무력화되는 것을 막는다(리터럴 매칭).
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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

    private static String categoryValues(int count) {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add("(?, " + (index + 1) + ")");
        }
        return String.join(", ", values);
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

    // 호환성 평가본 캐시 키 — draft 내용 서명 포함(즉시 무효화).
    // 전량 평가 경로는 page/size를 null로 둬(페이지 넘김이 같은 평가본을 재사용),
    // 페이지-스코프 경로는 page/size까지 키에 넣는다(캐시 값이 해당 페이지 응답이므로).
    private record CompatibilityRowsKey(
            String draftSignature,
            String category,
            String query,
            String manufacturer,
            String status,
            Integer minPrice,
            Integer maxPrice,
            String sort,
            String source,
            String mode,
            String replaceTargetPartId,
            Integer page,
            Integer size
    ) {
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

    private static Comparator<Map<String, Object>> candidateComparator(String sort) {
        Comparator<Map<String, Object>> recommendation = Comparator.comparingInt(PartQueryService::recommendationRank);
        Comparator<Map<String, Object>> secondary = switch (sort) {
            case "price_asc" -> Comparator.comparingInt(PartQueryService::priceValue);
            case "price_desc" -> Comparator.comparingInt(PartQueryService::priceValue).reversed();
            case "name" -> Comparator.comparing(row -> String.valueOf(row.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER);
            default -> Comparator.comparingInt(PartQueryService::compatibilityRank)
                    .thenComparingInt(PartQueryService::priceValue);
        };
        return recommendation.thenComparing(secondary)
                .thenComparing(row -> String.valueOf(row.getOrDefault("id", "")));
    }

    @SuppressWarnings("unchecked")
    private static int recommendationRank(Map<String, Object> row) {
        Object value = row.get("recommendation");
        if (!(value instanceof Map<?, ?> map)) {
            return Integer.MAX_VALUE;
        }
        Object rank = map.get("rank");
        return rank instanceof Number number ? number.intValue() : Integer.MAX_VALUE;
    }

    private static int priceValue(Map<String, Object> row) {
        Integer price = DbValueMapper.integer(row, "price");
        return price == null ? 0 : price;
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
