package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NaverShoppingOfferService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private static final Set<String> MATCH_STOPWORDS = Set.of(
            "정품", "벌크", "멀티팩", "국내", "배송", "게이밍", "컴퓨터", "PC", "부품",
            "그래픽", "그래픽카드", "지포스", "GEFORCE", "NVIDIA", "RADEON", "라데온",
            "메인보드", "마더보드", "케이스", "쿨러", "파워", "파워서플라이", "SSD", "RAM",
            "블랙", "화이트", "BLACK", "WHITE"
    );
    private static final String SOURCE = "NAVER_SHOPPING_SEARCH";
    private static final Pattern PSU_WATT_PATTERN = Pattern.compile("(?<!\\d)(\\d{3,4})\\s*(?:[Ww]|$|[^\\d])");
    private static final Pattern SPEED_PATTERN = Pattern.compile("(\\d{4})\\s*(?:MHZ|MT/S)?");

    private final String clientId;
    private final String clientSecret;
    private final RestClient restClient;
    private final JdbcTemplate jdbcTemplate;

    public NaverShoppingOfferService(
            @Value("${naver.search.client-id:}") String clientId,
            @Value("${naver.search.client-secret:}") String clientSecret,
            @Value("${naver.search.base-url:https://openapi.naver.com}") String baseUrl,
            JdbcTemplate jdbcTemplate
    ) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> refreshCatalog(String category, Integer limitPerQuery, Boolean publish, String query) {
        String normalizedCategory = requireCategory(category);
        int safeLimitPerQuery = limitPerQuery == null ? 3 : Math.min(Math.max(limitPerQuery, 1), 5);
        boolean publishToParts = Boolean.TRUE.equals(publish);
        List<String> queries = StringUtils.hasText(query) ? List.of(query.trim()) : queryPack(normalizedCategory);

        if (!configured()) {
            return MockData.map(
                    "configured", false,
                    "category", normalizedCategory,
                    "queryCount", queries.size(),
                    "limitPerQuery", safeLimitPerQuery,
                    "attempted", 0,
                    "discovered", 0,
                    "published", 0,
                    "failed", 0,
                    "message", "NAVER_SEARCH_CLIENT_ID 또는 NAVER_SEARCH_CLIENT_SECRET이 설정되지 않았습니다."
            );
        }

        Map<String, Object> job = createCatalogRefreshJob(normalizedCategory, queries);
        long jobId = ((Number) job.get("id")).longValue();
        int attempted = 0;
        int discovered = 0;
        int published = 0;
        int failed = 0;
        String errorSummary = null;

        try {
            for (String searchQuery : queries) {
                List<Map<String, Object>> offers = fetchOffers(searchQuery, safeLimitPerQuery);
                for (Map<String, Object> offer : offers) {
                    attempted += 1;
                    try {
                        if (!isAcceptableCatalogOffer(normalizedCategory, offer)) {
                            continue;
                        }
                        CatalogCandidate candidate = upsertCandidate(jobId, normalizedCategory, searchQuery, offer);
                        discovered += 1;
                        if (publishToParts && publishCandidate(candidate, normalizedCategory, searchQuery, offer)) {
                            published += 1;
                        }
                    } catch (RuntimeException ignored) {
                        failed += 1;
                    }
                }
            }
            finishCatalogRefreshJob(jobId, "SUCCEEDED", attempted, discovered, published, null);
        } catch (RuntimeException exception) {
            errorSummary = exception.getMessage();
            finishCatalogRefreshJob(jobId, "FAILED", attempted, discovered, published, errorSummary);
            throw exception;
        }

        return MockData.map(
                "configured", true,
                "jobId", job.get("public_id"),
                "category", normalizedCategory,
                "queryCount", queries.size(),
                "limitPerQuery", safeLimitPerQuery,
                "publish", publishToParts,
                "attempted", attempted,
                "discovered", discovered,
                "published", published,
                "failed", failed,
                "errorSummary", errorSummary
        );
    }

    public Map<String, Object> createManufacturerReleaseCandidate(
            long jobId,
            String category,
            String searchQuery,
            Map<String, Object> releaseContext
    ) {
        String normalizedCategory = requireCategory(category);
        String normalizedQuery = requireText(searchQuery, "searchQuery");
        if (!configured()) {
            return MockData.map(
                    "configured", false,
                    "created", false,
                    "message", "NAVER_SEARCH_CLIENT_ID 또는 NAVER_SEARCH_CLIENT_SECRET이 설정되지 않았습니다."
            );
        }

        int attempted = 0;
        for (Map<String, Object> offer : fetchOffers(normalizedQuery, 5)) {
            attempted += 1;
            if (!isAcceptableCatalogOffer(normalizedCategory, offer)) {
                continue;
            }
            Map<String, Object> enrichedOffer = withReleaseContext(offer, releaseContext);
            CatalogCandidate candidate = upsertCandidate(
                    jobId,
                    "MANUFACTURER_RELEASE_NAVER_SEARCH",
                    normalizedCategory,
                    normalizedQuery,
                    enrichedOffer
            );
            return MockData.map(
                    "configured", true,
                    "created", true,
                    "attempted", attempted,
                    "candidateId", candidate.publicId(),
                    "title", enrichedOffer.get("title"),
                    "lowPrice", enrichedOffer.get("lowPrice")
            );
        }
        return MockData.map(
                "configured", true,
                "created", false,
                "attempted", attempted,
                "message", "네이버 쇼핑에서 등록 후보로 쓸 만한 상품을 찾지 못했습니다."
        );
    }

    public Map<String, Object> approveCatalogCandidateAsInactive(String candidateId) {
        return approveCatalogCandidateAsInactive(candidateId, null);
    }

    public Map<String, Object> approveCatalogCandidateAsInactive(String candidateId, CurrentUserService.CurrentUser admin) {
        Map<String, Object> candidate = catalogCandidate(candidateId);
        Long publishedPartId = longValue(candidate.get("published_part_id"));
        if (publishedPartId != null) {
            syncLinkedInactivePartFromCandidate(candidate, publishedPartId, admin);
            return MockData.map(
                    "candidateId", candidate.get("public_id"),
                    "publishedPartId", candidate.get("published_part_public_id"),
                    "created", false,
                    "partStatus", candidate.get("published_part_status"),
                    "status", "PUBLISHED",
                    "message", "이미 parts에 연결된 후보입니다."
            );
        }

        Integer lowPrice = integerValue(candidate.get("low_price"));
        if (lowPrice == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "가격이 없는 후보는 내부 자산 초안으로 승인할 수 없습니다.");
        }
        String category = stringValue(candidate.get("category"));
        String title = stringValue(candidate.get("title"));
        String query = stringValue(candidate.get("search_query"));
        Map<String, Object> offer = offerFromCandidate(candidate);
        Long existingPartId = findExistingPartId(category, title);
        long partId = existingPartId == null
                ? insertPartWithStatus(category, title, stringValue(candidate.get("manufacturer_guess")), lowPrice, query, offer, "INACTIVE")
                : existingPartId;
        if (existingPartId != null) {
            syncLinkedInactivePartFromCandidate(candidate, partId, admin);
        }
        jdbcTemplate.update("""
                UPDATE part_catalog_candidates
                SET candidate_status = 'PUBLISHED',
                    published_part_id = ?,
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, partId, candidateId);
        upsertOffer(partId, query, offer);
        String publicPartId = jdbcTemplate.queryForObject(
                "SELECT public_id::text FROM parts WHERE id = ?",
                String.class,
                partId
        );
        audit(admin, "PART_CATALOG_CANDIDATE_APPROVED", "part_catalog_candidates", candidateId, MockData.map(
                "publishedPartId", publicPartId,
                "partStatus", existingPartId == null ? "INACTIVE" : candidate.get("published_part_status")
        ));
        return MockData.map(
                "candidateId", candidate.get("public_id"),
                "publishedPartId", publicPartId,
                "created", existingPartId == null,
                "partStatus", existingPartId == null ? "INACTIVE" : candidate.get("published_part_status"),
                "status", "PUBLISHED"
        );
    }

    public Map<String, Object> getCatalogCandidate(String candidateId, Boolean includeDeleted) {
        return catalogCandidateMap(catalogCandidate(candidateId, Boolean.TRUE.equals(includeDeleted)));
    }

    public Map<String, Object> updateCatalogCandidate(String candidateId, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> existing = catalogCandidate(candidateId);
        String category = request.containsKey("category")
                ? requireCategory(stringValue(request.get("category")))
                : stringValue(existing.get("category"));
        String searchQuery = request.containsKey("searchQuery")
                ? requireText(stringValue(request.get("searchQuery")), "searchQuery")
                : stringValue(existing.get("search_query"));
        String title = request.containsKey("title")
                ? requireText(stringValue(request.get("title")), "title")
                : stringValue(existing.get("title"));
        String sourceProductKey = stableCandidateSourceProductKey(
                stringValue(existing.get("source_product_key")),
                stringValue(existing.get("source")),
                category,
                title,
                request.containsKey("offerUrl") ? stringValue(request.get("offerUrl")) : stringValue(existing.get("offer_url")),
                searchQuery
        );
        Integer lowPrice = request.containsKey("lowPrice")
                ? integerValue(request.get("lowPrice"))
                : integerValue(existing.get("low_price"));
        if (lowPrice != null && lowPrice < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lowPrice는 0 이상이어야 합니다.");
        }
        String manufacturerGuess = request.containsKey("manufacturerGuess")
                ? stringValue(request.get("manufacturerGuess"))
                : stringValue(existing.get("manufacturer_guess"));
        String imageUrl = request.containsKey("imageUrl")
                ? stringValue(request.get("imageUrl"))
                : stringValue(existing.get("image_url"));
        String supplierName = request.containsKey("supplierName")
                ? stringValue(request.get("supplierName"))
                : stringValue(existing.get("supplier_name"));
        String offerUrl = request.containsKey("offerUrl")
                ? stringValue(request.get("offerUrl"))
                : stringValue(existing.get("offer_url"));

        jdbcTemplate.update("""
                UPDATE part_catalog_candidates
                SET category = ?,
                    source_product_key = ?,
                    search_query = ?,
                    title = ?,
                    manufacturer_guess = ?,
                    image_url = ?,
                    supplier_name = ?,
                    offer_url = ?,
                    low_price = ?,
                    raw_payload = coalesce(raw_payload, '{}'::jsonb) || ?::jsonb,
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """,
                category,
                limited(sourceProductKey, 500),
                limited(searchQuery, 255),
                limited(title, 500),
                limited(manufacturerGuess, 100),
                imageUrl,
                limited(supplierName, 255),
                offerUrl,
                lowPrice,
                json(MockData.map(
                        "adminEdit", MockData.map(
                                "updatedBy", admin == null ? null : admin.email(),
                                "updatedAt", MockData.now()
                        )
                )),
                candidateId
        );
        audit(admin, "PART_CATALOG_CANDIDATE_UPDATED", "part_catalog_candidates", candidateId, MockData.map(
                "category", category,
                "title", title,
                "lowPrice", lowPrice
        ));
        Map<String, Object> updatedCandidate = catalogCandidate(candidateId);
        Long linkedPartId = longValue(updatedCandidate.get("published_part_id"));
        if (linkedPartId != null) {
            syncLinkedInactivePartFromCandidate(updatedCandidate, linkedPartId, admin);
        }
        return getCatalogCandidate(candidateId, false);
    }

    public Map<String, Object> softDeleteCatalogCandidate(String candidateId, CurrentUserService.CurrentUser admin) {
        catalogCandidate(candidateId);
        jdbcTemplate.update("""
                UPDATE part_catalog_candidates
                SET deleted_at = now(),
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """, candidateId);
        audit(admin, "PART_CATALOG_CANDIDATE_SOFT_DELETED", "part_catalog_candidates", candidateId, Map.of());
        return MockData.map("id", candidateId, "deleted", true);
    }

    public Map<String, Object> restoreCatalogCandidate(String candidateId, CurrentUserService.CurrentUser admin) {
        catalogCandidate(candidateId, true);
        jdbcTemplate.update("""
                UPDATE part_catalog_candidates
                SET deleted_at = NULL,
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, candidateId);
        audit(admin, "PART_CATALOG_CANDIDATE_RESTORED", "part_catalog_candidates", candidateId, Map.of());
        return getCatalogCandidate(candidateId, false);
    }

    public Map<String, Object> rejectCatalogCandidate(String candidateId, Map<String, Object> request) {
        return rejectCatalogCandidate(candidateId, request, null);
    }

    public Map<String, Object> rejectCatalogCandidate(String candidateId, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        String reason = request == null ? null : stringValue(request.get("reason"));
        Map<String, Object> candidate = catalogCandidate(candidateId);
        jdbcTemplate.update("""
                UPDATE part_catalog_candidates
                SET candidate_status = 'REJECTED',
                    raw_payload = coalesce(raw_payload, '{}'::jsonb) || ?::jsonb,
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """,
                json(MockData.map(
                        "adminReview", MockData.map(
                                "decision", "REJECTED",
                                "reason", reason,
                                "reviewedAt", MockData.now()
                        )
                )),
                candidateId
        );
        audit(admin, "PART_CATALOG_CANDIDATE_REJECTED", "part_catalog_candidates", candidateId, MockData.map("reason", reason));
        return MockData.map(
                "candidateId", candidate.get("public_id"),
                "status", "REJECTED",
                "reason", reason
        );
    }

    public Map<String, Object> refreshCatalogCandidateOffer(String candidateId) {
        Map<String, Object> candidate = catalogCandidate(candidateId);
        if (!configured()) {
            return MockData.map(
                    "configured", false,
                    "candidateId", candidate.get("public_id"),
                    "updated", false,
                    "message", "NAVER_SEARCH_CLIENT_ID 또는 NAVER_SEARCH_CLIENT_SECRET이 설정되지 않았습니다."
            );
        }
        String category = stringValue(candidate.get("category"));
        String searchQuery = stringValue(candidate.get("search_query"));
        int attempted = 0;
        for (Map<String, Object> offer : fetchOffers(searchQuery, 5)) {
            attempted += 1;
            if (!isAcceptableCatalogOffer(category, offer)) {
                continue;
            }
            Map<String, Object> enrichedOffer = withReleaseContext(offer, releaseContextFromCandidate(candidate));
            String sourceProductKey = stableCandidateSourceProductKey(
                    stringValue(enrichedOffer.get("sourceProductKey")),
                    stringValue(candidate.get("source")),
                    category,
                    stringValue(enrichedOffer.get("title")),
                    stringValue(enrichedOffer.get("offerUrl")),
                    searchQuery
            );
            jdbcTemplate.update("""
                    UPDATE part_catalog_candidates
                    SET source_product_key = ?,
                        title = ?,
                        manufacturer_guess = ?,
                        image_url = ?,
                        supplier_name = ?,
                        offer_url = ?,
                        low_price = ?,
                        raw_payload = ?::jsonb,
                        last_seen_at = now(),
                        updated_at = now()
                    WHERE public_id = ?::uuid
                      AND deleted_at IS NULL
                    """,
                    sourceProductKey,
                    limited(stringValue(enrichedOffer.get("title")), 500),
                    limited(stringValue(enrichedOffer.get("manufacturerGuess")), 100),
                    enrichedOffer.get("imageUrl"),
                    limited(stringValue(enrichedOffer.get("supplierName")), 255),
                    enrichedOffer.get("offerUrl"),
                    enrichedOffer.get("lowPrice"),
                    json(enrichedOffer.get("rawPayload")),
                    candidateId
            );
            return MockData.map(
                    "configured", true,
                    "candidateId", candidate.get("public_id"),
                    "updated", true,
                    "attempted", attempted,
                    "title", enrichedOffer.get("title"),
                    "lowPrice", enrichedOffer.get("lowPrice")
            );
        }
        return MockData.map(
                "configured", true,
                "candidateId", candidate.get("public_id"),
                "updated", false,
                "attempted", attempted,
                "message", "네이버 쇼핑에서 갱신 가능한 후보를 찾지 못했습니다."
        );
    }

    private static Map<String, Object> withReleaseContext(Map<String, Object> offer, Map<String, Object> releaseContext) {
        Map<String, Object> enriched = new LinkedHashMap<>(offer);
        enriched.put("rawPayload", MockData.map(
                "offer", offer.get("rawPayload"),
                "manufacturerRelease", releaseContext == null ? Map.of() : releaseContext
        ));
        return enriched;
    }

    private static Map<String, Object> releaseContextFromCandidate(Map<String, Object> candidate) {
        return MockData.map(
                "candidateId", candidate.get("public_id"),
                "candidateSource", candidate.get("source"),
                "previousRawPayload", stringValue(candidate.get("raw_payload"))
        );
    }

    private static Map<String, Object> offerFromCandidate(Map<String, Object> candidate) {
        return MockData.map(
                "sourceProductKey", candidate.get("source_product_key"),
                "title", candidate.get("title"),
                "manufacturerGuess", candidate.get("manufacturer_guess"),
                "imageUrl", candidate.get("image_url"),
                "supplierName", candidate.get("supplier_name"),
                "offerUrl", candidate.get("offer_url"),
                "lowPrice", candidate.get("low_price"),
                "rawPayload", candidate.get("raw_payload")
        );
    }

    public Map<String, Object> refreshOffers(String category, Integer limit, Boolean force) {
        String normalizedCategory = normalizeCategory(category);
        int safeLimit = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        boolean forceRefresh = Boolean.TRUE.equals(force);

        if (!configured()) {
            return MockData.map(
                    "configured", false,
                    "category", normalizedCategory,
                    "limit", safeLimit,
                    "attempted", 0,
                    "updated", 0,
                    "skipped", 0,
                    "failed", 0,
                    "message", "NAVER_SEARCH_CLIENT_ID 또는 NAVER_SEARCH_CLIENT_SECRET이 설정되지 않았습니다."
            );
        }

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.public_id::text AS public_id, p.category, p.name, p.manufacturer
                FROM parts p
                LEFT JOIN part_external_offers peo
                  ON peo.part_id = p.id
                 AND peo.source = 'NAVER_SHOPPING_SEARCH'
                 AND peo.deleted_at IS NULL
                WHERE p.deleted_at IS NULL
                  AND p.status = 'ACTIVE'
                """);
        if (normalizedCategory != null) {
            sql.append(" AND p.category = ?");
            params.add(normalizedCategory);
        }
        if (!forceRefresh) {
            sql.append(" AND (peo.id IS NULL OR peo.refreshed_at < now() - interval '1 day')");
        }
        sql.append(" ORDER BY p.category, p.id LIMIT ?");
        params.add(safeLimit);

        List<Map<String, Object>> parts = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        int attempted = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;

        for (Map<String, Object> part : parts) {
            attempted += 1;
            String name = stringValue(part.get("name"));
            String manufacturer = stringValue(part.get("manufacturer"));
            String query = searchQuery(name, manufacturer);
            Optional<Map<String, Object>> offer = fetchOffer(normalizedCategory, name, manufacturer, query);
            if (offer.isEmpty()) {
                skipped += 1;
                continue;
            }
            try {
                upsertOffer(((Number) part.get("id")).longValue(), query, offer.get());
                updated += 1;
            } catch (RuntimeException ignored) {
                failed += 1;
            }
        }

        return MockData.map(
                "configured", true,
                "category", normalizedCategory,
                "limit", safeLimit,
                "force", forceRefresh,
                "attempted", attempted,
                "updated", updated,
                "skipped", skipped,
                "failed", failed
        );
    }

    public Map<String, Object> refreshDailyOffers() {
        if (!configured()) {
            return MockData.map(
                    "configured", false,
                    "categories", CATEGORIES.size(),
                    "attempted", 0,
                    "updated", 0,
                    "skipped", 0,
                    "failed", 0,
                    "message", "NAVER_SEARCH_CLIENT_ID 또는 NAVER_SEARCH_CLIENT_SECRET이 설정되지 않았습니다."
            );
        }

        int attempted = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        List<Map<String, Object>> categoryResults = new ArrayList<>();
        for (String category : CATEGORIES.stream().sorted().toList()) {
            Map<String, Object> result = refreshOffers(category, 100, false);
            categoryResults.add(result);
            attempted += numberValue(result.get("attempted"));
            updated += numberValue(result.get("updated"));
            skipped += numberValue(result.get("skipped"));
            failed += numberValue(result.get("failed"));
        }

        return MockData.map(
                "configured", true,
                "categories", categoryResults,
                "attempted", attempted,
                "updated", updated,
                "skipped", skipped,
                "failed", failed
        );
    }

    private Optional<Map<String, Object>> fetchOffer(String category, String name, String manufacturer, String query) {
        List<Map<String, Object>> offers = fetchOffers(query, 10);
        Map<String, Object> bestOffer = null;
        int bestScore = Integer.MIN_VALUE;
        for (Map<String, Object> offer : offers) {
            if (!isAcceptableCatalogOffer(category, offer) || !isReasonableOfferMatch(category, name, offer)) {
                continue;
            }
            int score = offerMatchScore(name, manufacturer, offer);
            if (score > bestScore) {
                bestOffer = offer;
                bestScore = score;
            }
        }
        return Optional.ofNullable(bestOffer);
    }

    private List<Map<String, Object>> fetchOffers(String query, int display) {
        try {
            Map<?, ?> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/search/shop.json")
                            .queryParam("query", query)
                            .queryParam("display", display)
                            .queryParam("start", 1)
                            .queryParam("sort", "sim")
                            .queryParam("exclude", "used:rental:cbshop")
                            .build())
                    .header("X-Naver-Client-Id", clientId)
                    .header("X-Naver-Client-Secret", clientSecret)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !(response.get("items") instanceof List<?> items) || items.isEmpty()) {
                return List.of();
            }

            return items.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(item -> MockData.map(
                            "title", cleanText(stringValue(item.get("title"))),
                            "imageUrl", stringValue(item.get("image")),
                            "supplierName", stringValue(item.get("mallName")),
                            "offerUrl", stringValue(item.get("link")),
                            "lowPrice", integerValue(item.get("lprice")),
                            "source", SOURCE,
                            "manufacturerGuess", manufacturerGuess(item),
                            "sourceProductKey", sourceProductKey(item),
                            "rawPayload", item
                    ))
                    .filter(offer -> StringUtils.hasText(stringValue(offer.get("title"))))
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Map<String, Object> createCatalogRefreshJob(String category, List<String> queries) {
        return jdbcTemplate.queryForMap("""
                INSERT INTO part_catalog_refresh_jobs (
                  source,
                  category,
                  search_query,
                  status
                )
                VALUES (?, ?, ?, 'RUNNING')
                RETURNING id, public_id::text
                """,
                SOURCE,
                category,
                joinQueries(queries)
        );
    }

    private void finishCatalogRefreshJob(long jobId, String status, int attempted, int discovered, int published, String errorSummary) {
        jdbcTemplate.update("""
                UPDATE part_catalog_refresh_jobs
                SET status = ?,
                    attempted_count = ?,
                    discovered_count = ?,
                    published_count = ?,
                    error_summary = ?,
                    finished_at = now()
                WHERE id = ?
                """,
                status,
                attempted,
                discovered,
                published,
                errorSummary,
                jobId
        );
    }

    private CatalogCandidate upsertCandidate(long jobId, String category, String query, Map<String, Object> offer) {
        return upsertCandidate(jobId, SOURCE, category, query, offer);
    }

    private CatalogCandidate upsertCandidate(long jobId, String source, String category, String query, Map<String, Object> offer) {
        String title = limited(stringValue(offer.get("title")), 500);
        String sourceProductKey = stableCandidateSourceProductKey(
                stringValue(offer.get("sourceProductKey")),
                source,
                category,
                title,
                stringValue(offer.get("offerUrl")),
                query
        );
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO part_catalog_candidates (
                  refresh_job_id,
                  source,
                  category,
                  source_product_key,
                  search_query,
                  title,
                  manufacturer_guess,
                  image_url,
                  supplier_name,
                  offer_url,
                  low_price,
                  raw_payload,
                  discovered_at,
                  last_seen_at,
                  created_at,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), now(), now(), now())
                ON CONFLICT (source, category, source_product_key) WHERE deleted_at IS NULL
                DO UPDATE SET
                  refresh_job_id = EXCLUDED.refresh_job_id,
                  search_query = EXCLUDED.search_query,
                  title = EXCLUDED.title,
                  manufacturer_guess = EXCLUDED.manufacturer_guess,
                  image_url = EXCLUDED.image_url,
                  supplier_name = EXCLUDED.supplier_name,
                  offer_url = EXCLUDED.offer_url,
                  low_price = EXCLUDED.low_price,
                  raw_payload = EXCLUDED.raw_payload,
                  last_seen_at = now(),
                  updated_at = now()
                RETURNING id, public_id::text AS public_id, published_part_id
                """,
                jobId,
                source,
                category,
                sourceProductKey,
                query,
                title,
                limited(stringValue(offer.get("manufacturerGuess")), 100),
                offer.get("imageUrl"),
                limited(stringValue(offer.get("supplierName")), 255),
                offer.get("offerUrl"),
                offer.get("lowPrice"),
                json(offer.get("rawPayload"))
        );
        Long publishedPartId = row.get("published_part_id") instanceof Number number ? number.longValue() : null;
        return new CatalogCandidate(((Number) row.get("id")).longValue(), stringValue(row.get("public_id")), publishedPartId);
    }

    private Map<String, Object> catalogCandidate(String candidateId) {
        return catalogCandidate(candidateId, false);
    }

    private Map<String, Object> catalogCandidate(String candidateId, boolean includeDeleted) {
        requireUuid(candidateId, "candidateId");
        String deletedClause = includeDeleted ? "" : " AND c.deleted_at IS NULL\n";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT c.id,
                       c.public_id::text AS public_id,
                       c.published_part_id,
                       p.public_id::text AS published_part_public_id,
                       p.status AS published_part_status,
                       c.source,
                       c.category,
                       c.source_product_key,
                       c.search_query,
                       c.title,
                       c.manufacturer_guess,
                       c.image_url,
                       c.supplier_name,
                       c.offer_url,
                       c.low_price,
                       c.candidate_status,
                       c.raw_payload,
                       c.discovered_at,
                       c.last_seen_at,
                       c.created_at,
                       c.updated_at,
                       c.deleted_at
                FROM part_catalog_candidates c
                LEFT JOIN parts p ON p.id = c.published_part_id
                WHERE c.public_id = ?::uuid
                """ + deletedClause + """
                LIMIT 1
                """, candidateId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "카탈로그 후보를 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    private Map<String, Object> catalogCandidateMap(Map<String, Object> row) {
        return MockData.map(
                "id", row.get("public_id"),
                "source", row.get("source"),
                "category", row.get("category"),
                "sourceProductKey", row.get("source_product_key"),
                "searchQuery", row.get("search_query"),
                "title", row.get("title"),
                "manufacturerGuess", row.get("manufacturer_guess"),
                "imageUrl", row.get("image_url"),
                "supplierName", row.get("supplier_name"),
                "offerUrl", row.get("offer_url"),
                "lowPrice", row.get("low_price"),
                "candidateStatus", row.get("candidate_status"),
                "publishedPartId", row.get("published_part_public_id"),
                "publishedPartStatus", row.get("published_part_status"),
                "rawPayload", row.get("raw_payload"),
                "discoveredAt", row.get("discovered_at"),
                "lastSeenAt", row.get("last_seen_at"),
                "createdAt", row.get("created_at"),
                "updatedAt", row.get("updated_at"),
                "deletedAt", row.get("deleted_at")
        );
    }

    private boolean publishCandidate(CatalogCandidate candidate, String category, String query, Map<String, Object> offer) {
        if (candidate.publishedPartId() != null) {
            upsertOffer(candidate.publishedPartId(), query, offer);
            return false;
        }
        Integer lowPrice = integerValue(offer.get("lowPrice"));
        if (lowPrice == null) {
            return false;
        }
        String title = stringValue(offer.get("title"));
        Long existingPartId = findExistingPartId(category, title);
        long partId = existingPartId == null
                ? insertPart(category, title, stringValue(offer.get("manufacturerGuess")), lowPrice, query, offer)
                : existingPartId;
        jdbcTemplate.update("""
                UPDATE part_catalog_candidates
                SET candidate_status = 'PUBLISHED',
                    published_part_id = ?,
                    updated_at = now()
                WHERE id = ?
                """, partId, candidate.id());
        upsertOffer(partId, query, offer);
        return existingPartId == null;
    }

    private Long findExistingPartId(String category, String title) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id
                FROM parts
                WHERE category = ?
                  AND lower(name) = lower(?)
                  AND deleted_at IS NULL
                LIMIT 1
                """, category, title);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private long insertPart(String category, String title, String manufacturer, int price, String query, Map<String, Object> offer) {
        return insertPartWithStatus(category, title, manufacturer, price, query, offer, "ACTIVE");
    }

    private long insertPartWithStatus(String category, String title, String manufacturer, int price, String query, Map<String, Object> offer, String status) {
        Map<String, Object> attributes = attributesFor(category, title, query, offer);
        attributes.put("catalogApprovalStatus", "INACTIVE".equals(status) ? "ADMIN_SPEC_REVIEW_REQUIRED" : "PUBLISHED");
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO parts (
                  category,
                  name,
                  manufacturer,
                  price,
                  status,
                  attributes,
                  created_at,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, now(), now())
                RETURNING id
                """,
                category,
                limited(title, 255),
                blankToNull(limited(manufacturer, 100)),
                price,
                status,
                json(attributes)
        );
        return ((Number) row.get("id")).longValue();
    }

    private void syncLinkedInactivePartFromCandidate(Map<String, Object> candidate, long partId, CurrentUserService.CurrentUser admin) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT public_id::text AS public_id, status
                FROM parts
                WHERE id = ?
                  AND deleted_at IS NULL
                LIMIT 1
                """, partId);
        if (rows.isEmpty() || !"INACTIVE".equals(stringValue(rows.get(0).get("status")))) {
            return;
        }

        String category = requireCategory(stringValue(candidate.get("category")));
        String title = requireText(stringValue(candidate.get("title")), "title");
        String query = stringValue(candidate.get("search_query"));
        String manufacturer = stringValue(candidate.get("manufacturer_guess"));
        Integer lowPrice = integerValue(candidate.get("low_price"));
        Map<String, Object> offer = offerFromCandidate(candidate);
        Map<String, Object> attributes = attributesFor(category, title, query, offer);
        attributes.put("catalogApprovalStatus", "ADMIN_SPEC_REVIEW_REQUIRED");
        jdbcTemplate.update("""
                UPDATE parts
                SET category = ?,
                    name = ?,
                    manufacturer = ?,
                    price = coalesce(?, price),
                    attributes = ?::jsonb,
                    updated_at = now()
                WHERE id = ?
                  AND status = 'INACTIVE'
                  AND deleted_at IS NULL
                """,
                category,
                limited(title, 255),
                blankToNull(limited(manufacturer, 100)),
                lowPrice,
                json(attributes),
                partId
        );
        audit(admin, "PART_CATALOG_CANDIDATE_PART_SYNCED", "parts", stringValue(rows.get(0).get("public_id")), MockData.map(
                "candidateId", candidate.get("public_id"),
                "category", category,
                "title", title,
                "toolReady", attributes.get("toolReady")
        ));
    }

    private void upsertOffer(long partId, String query, Map<String, Object> offer) {
        Integer lowPrice = integerValue(offer.get("lowPrice"));
        String rawPayload = json(offer.get("rawPayload"));
        jdbcTemplate.update("""
                INSERT INTO part_external_offers (
                  part_id,
                  source,
                  search_query,
                  title,
                  image_url,
                  supplier_name,
                  offer_url,
                  low_price,
                  raw_payload,
                  refreshed_at,
                  created_at,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(), now(), now())
                ON CONFLICT (part_id, source) WHERE deleted_at IS NULL
                DO UPDATE SET
                  search_query = EXCLUDED.search_query,
                  title = EXCLUDED.title,
                  image_url = EXCLUDED.image_url,
                  supplier_name = EXCLUDED.supplier_name,
                  offer_url = EXCLUDED.offer_url,
                  low_price = EXCLUDED.low_price,
                  raw_payload = EXCLUDED.raw_payload,
                  refreshed_at = now(),
                  updated_at = now()
                """,
                partId,
                SOURCE,
                query,
                limited(stringValue(offer.get("title")), 500),
                offer.get("imageUrl"),
                limited(stringValue(offer.get("supplierName")), 255),
                offer.get("offerUrl"),
                lowPrice,
                rawPayload
        );
        syncPartPrice(partId, lowPrice, rawPayload);
    }

    private void syncPartPrice(long partId, Integer lowPrice, String rawPayload) {
        if (lowPrice == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE parts
                SET price = ?,
                    updated_at = now()
                WHERE id = ?
                  AND deleted_at IS NULL
                """,
                lowPrice,
                partId
        );
        jdbcTemplate.update("""
                INSERT INTO price_snapshots (
                  part_id,
                  price,
                  source,
                  collected_at,
                  raw_payload
                )
                VALUES (?, ?, ?, now(), ?::jsonb)
                """,
                partId,
                lowPrice,
                SOURCE,
                rawPayload
        );
    }

    private boolean configured() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    private static String searchQuery(String name, String manufacturer) {
        if (!StringUtils.hasText(manufacturer) || manufacturer.endsWith("Partner")) {
            return name;
        }
        return manufacturer + " " + name;
    }

    private static String normalizeCategory(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String upper = value.trim().toUpperCase();
        if (!CATEGORIES.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 부품 category입니다.");
        }
        return upper;
    }

    private static String requireCategory(String value) {
        String normalized = normalizeCategory(value);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category는 필수입니다.");
        }
        return normalized;
    }

    private static List<String> queryPack(String category) {
        return switch (category) {
            case "GPU" -> List.of(
                    "ASUS RTX 5090", "MSI RTX 5090", "GIGABYTE RTX 5090", "ZOTAC RTX 5090", "PNY RTX 5090",
                    "ASUS RTX 5080", "MSI RTX 5080", "GIGABYTE RTX 5080", "ZOTAC RTX 5080",
                    "ASUS RTX 5070 Ti", "MSI RTX 5070 Ti", "GIGABYTE RTX 5070 Ti", "ZOTAC RTX 5070 Ti",
                    "ASUS RTX 5070", "MSI RTX 5070", "GIGABYTE RTX 5070", "ZOTAC RTX 5070",
                    "ASUS RTX 5060 Ti", "MSI RTX 5060 Ti", "GIGABYTE RTX 5060 Ti",
                    "ASUS RTX 5060", "MSI RTX 5060", "GIGABYTE RTX 5060", "ZOTAC RTX 5060"
            );
            case "MOTHERBOARD" -> List.of(
                    "ASUS Z890 DDR5", "MSI Z890 DDR5", "GIGABYTE Z890 DDR5", "ASRock Z890 DDR5",
                    "ASUS B860 DDR5", "MSI B860 DDR5", "GIGABYTE B860 DDR5", "ASRock B860 DDR5",
                    "ASUS X870E DDR5", "MSI X870E DDR5", "GIGABYTE X870E DDR5", "ASRock X870E DDR5",
                    "ASUS X870 DDR5", "MSI X870 DDR5", "GIGABYTE X870 DDR5",
                    "ASUS B850 DDR5", "MSI B850 DDR5", "GIGABYTE B850 DDR5", "ASRock B850 DDR5"
            );
            case "PSU" -> List.of(
                    "Corsair ATX 3.1 850W", "Corsair ATX 3.1 1000W", "Corsair ATX 3.1 1200W",
                    "Seasonic ATX 3.1 850W", "Seasonic ATX 3.1 1000W", "Seasonic ATX 3.1 1200W",
                    "FSP ATX 3.1 850W", "FSP ATX 3.1 1000W", "FSP ATX 3.1 1200W",
                    "Super Flower ATX 3.1 850W", "Super Flower ATX 3.1 1000W", "Super Flower ATX 3.1 1200W",
                    "MSI ATX 3.1 850W", "MSI ATX 3.1 1000W",
                    "Cooler Master ATX 3.1 850W", "Cooler Master ATX 3.1 1000W"
            );
            case "CPU" -> List.of(
                    "AMD Ryzen 9 9950X3D", "AMD Ryzen 9 9900X3D", "AMD Ryzen 7 9800X3D",
                    "AMD Ryzen 9 9950X", "AMD Ryzen 7 9700X", "AMD Ryzen 5 9600X",
                    "Intel Core Ultra 9 285K", "Intel Core Ultra 7 265K", "Intel Core Ultra 5 245K"
            );
            case "RAM" -> List.of(
                    "Samsung DDR5 32GB 6400", "Crucial DDR5 32GB 6000", "G.SKILL DDR5 32GB 6000",
                    "Corsair DDR5 32GB 6400", "Kingston DDR5 32GB 6000", "TeamGroup DDR5 64GB 6400"
            );
            case "STORAGE" -> List.of(
                    "Samsung 9100 PRO 2TB", "WD BLACK SN8100 2TB", "Crucial T705 2TB",
                    "SK hynix Platinum P51 2TB", "Kingston FURY Renegade G5 2TB", "Seagate FireCuda 540 2TB"
            );
            case "CASE" -> List.of(
                    "Fractal Design Meshify 3", "Lian Li LANCOOL 217", "NZXT H9 Flow 2025",
                    "Corsair FRAME 4000D", "Phanteks Evolv X2", "be quiet Light Base 900 DX"
            );
            case "COOLER" -> List.of(
                    "ARCTIC Liquid Freezer III 360", "DeepCool ASSASSIN IV", "Noctua NH-D15 G2",
                    "Corsair iCUE LINK TITAN 360", "NZXT Kraken Elite 360", "Lian Li HydroShift LCD 360R",
                    "be quiet Dark Rock Pro 5", "Thermalright Phantom Spirit 120 EVO",
                    "Cooler Master MasterLiquid 360 Atmos", "ASUS ROG Ryujin III 360 ARGB"
            );
            default -> List.of(category);
        };
    }

    private static Map<String, Object> attributesFor(String category, String title, String query, Map<String, Object> offer) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("shortSpec", title);
        attributes.put("catalogGeneration", "EXTERNAL_REFRESH");
        attributes.put("currentLineupOnly", true);
        attributes.put("specSource", "NAVER_SHOPPING_SEARCH");
        attributes.put("specConfidence", "ESTIMATED_FROM_TITLE");
        attributes.put("metadataVersion", 4);
        attributes.put("externalSources", MockData.map(
                "naver", MockData.map(
                        "keyword", query,
                        "sourceProductKey", offer.get("sourceProductKey"),
                        "catalogRefresh", true
                )
        ));

        String upperTitle = title.toUpperCase(Locale.ROOT);
        switch (category) {
            case "GPU" -> applyGpuAttributes(attributes, upperTitle);
            case "MOTHERBOARD" -> applyMotherboardAttributes(attributes, upperTitle);
            case "PSU" -> applyPsuAttributes(attributes, upperTitle);
            case "CPU" -> applyCpuAttributes(attributes, upperTitle);
            case "CASE" -> applyCaseAttributes(attributes, upperTitle);
            case "COOLER" -> applyCoolerAttributes(attributes, upperTitle);
            case "RAM" -> {
                attributes.put("memoryType", "DDR5");
                attributes.put("capacityGb", upperTitle.contains("64GB") ? 64 : 32);
                attributes.put("moduleCount", upperTitle.contains("X2") || upperTitle.contains("2X") ? 2 : 2);
                attributes.put("formFactor", "UDIMM");
                applyMemorySpeed(attributes, upperTitle);
            }
            case "STORAGE" -> {
                attributes.put("interface", upperTitle.contains("PCIE 5") || upperTitle.contains("GEN5") || upperTitle.contains("G5") ? "PCIe 5.0 x4 NVMe" : "M.2 NVMe");
                attributes.put("capacityGb", upperTitle.contains("4TB") ? 4000 : upperTitle.contains("1TB") ? 1000 : 2000);
                attributes.put("formFactor", "M.2 2280");
            }
            default -> {
            }
        }
        applyManualSpecOverrides(category, attributes, upperTitle);
        applyAiSpecAttributes(attributes, offer);
        attributes.put("toolReady", toolReadyFor(category, attributes));
        return attributes;
    }

    private static void applyGpuAttributes(Map<String, Object> attributes, String upperTitle) {
        attributes.put("architecture", "Blackwell");
        attributes.put("series", "GeForce RTX 50");
        if (upperTitle.contains("5090")) {
            attributes.put("wattage", 575);
            attributes.put("requiredSystemPowerW", 1000);
            attributes.put("vramGb", 32);
        } else if (upperTitle.contains("5080")) {
            attributes.put("wattage", 360);
            attributes.put("requiredSystemPowerW", 850);
            attributes.put("vramGb", 16);
        } else if (upperTitle.contains("5070 TI")) {
            attributes.put("wattage", 300);
            attributes.put("requiredSystemPowerW", 750);
            attributes.put("vramGb", 16);
        } else if (upperTitle.contains("5070")) {
            attributes.put("wattage", 250);
            attributes.put("requiredSystemPowerW", 650);
            attributes.put("vramGb", 12);
        } else if (upperTitle.contains("5060 TI")) {
            attributes.put("wattage", 180);
            attributes.put("requiredSystemPowerW", 600);
            attributes.put("vramGb", upperTitle.contains("16GB") ? 16 : 8);
        } else if (upperTitle.contains("5060")) {
            attributes.put("wattage", 145);
            attributes.put("requiredSystemPowerW", 550);
            attributes.put("vramGb", 8);
        }
        attributes.put("memoryType", "GDDR7");
        attributes.put("lengthMm", inferredGpuLengthMm(upperTitle));
        attributes.put("slotWidth", inferredGpuSlotWidth(upperTitle));
        attributes.put("powerConnector", "12V-2x6");
    }

    private static void applyMotherboardAttributes(Map<String, Object> attributes, String upperTitle) {
        if (upperTitle.contains("Z890") || upperTitle.contains("B860")) {
            attributes.put("socket", "LGA1851");
        } else if (upperTitle.contains("X870") || upperTitle.contains("B850")) {
            attributes.put("socket", "AM5");
        }
        if (upperTitle.contains("Z890")) {
            attributes.put("chipset", "Z890");
        } else if (upperTitle.contains("B860")) {
            attributes.put("chipset", "B860");
        } else if (upperTitle.contains("X870E")) {
            attributes.put("chipset", "X870E");
        } else if (upperTitle.contains("X870")) {
            attributes.put("chipset", "X870");
        } else if (upperTitle.contains("B850")) {
            attributes.put("chipset", "B850");
        }
        attributes.put("memoryType", "DDR5");
        if (upperTitle.contains("ITX")) {
            attributes.put("formFactor", "MINI_ITX");
            attributes.put("widthMm", 170);
            attributes.put("depthMm", 170);
        } else if (upperTitle.contains("M-ATX") || upperTitle.contains("MATX") || upperTitle.contains("M ATX")) {
            attributes.put("formFactor", "MATX");
            attributes.put("widthMm", 244);
            attributes.put("depthMm", 244);
        } else if (upperTitle.contains("E-ATX") || upperTitle.contains("EATX")) {
            attributes.put("formFactor", "EATX");
            attributes.put("widthMm", 305);
            attributes.put("depthMm", 330);
        } else {
            attributes.put("formFactor", "ATX");
            attributes.put("widthMm", 305);
            attributes.put("depthMm", 244);
        }
    }

    private static void applyPsuAttributes(Map<String, Object> attributes, String upperTitle) {
        Matcher matcher = PSU_WATT_PATTERN.matcher(upperTitle);
        if (matcher.find()) {
            attributes.put("capacityW", Integer.parseInt(matcher.group(1)));
        }
        if (upperTitle.contains("ATX 3.1")) {
            attributes.put("atxSpec", "ATX 3.1");
        } else if (upperTitle.contains("ATX 3.0") || upperTitle.contains("PCIE5") || upperTitle.contains("PCI-E 5")) {
            attributes.put("atxSpec", "ATX 3.0");
        }
        attributes.put("gpuConnector", "12V-2x6");
        attributes.put("modular", true);
        attributes.put("depthMm", upperTitle.contains("1200W") ? 180 : 160);
    }

    private static void applyCpuAttributes(Map<String, Object> attributes, String upperTitle) {
        if (upperTitle.contains("RYZEN")) {
            attributes.put("socket", "AM5");
            attributes.put("architecture", "Zen 5");
        } else if (upperTitle.contains("CORE ULTRA")) {
            attributes.put("socket", "LGA1851");
            attributes.put("architecture", "Arrow Lake");
        }
        applyCpuCoreAndPower(attributes, upperTitle);
    }

    private static void applyCaseAttributes(Map<String, Object> attributes, String upperTitle) {
        attributes.put("formFactor", upperTitle.contains("EATX") || upperTitle.contains("E-ATX") || upperTitle.contains("XL") || upperTitle.contains("900")
                ? "EATX_ATX_MATX_ITX"
                : "ATX_MATX_ITX");
        attributes.put("airflowFocus", upperTitle.contains("MESH") || upperTitle.contains("FLOW") || upperTitle.contains("AIR"));
        if (upperTitle.contains("MESHIFY 3 XL")) {
            attributes.put("maxGpuLengthMm", 512);
            attributes.put("maxCpuCoolerHeightMm", 185);
            attributes.put("radiatorSupportMm", List.of(120, 240, 280, 360, 420));
            applyDimensions(attributes, 566, 245, 520);
        } else if (upperTitle.contains("MESHIFY 3")) {
            attributes.put("maxGpuLengthMm", 349);
            attributes.put("maxCpuCoolerHeightMm", 180);
            attributes.put("radiatorSupportMm", List.of(120, 240, 280, 360));
            applyDimensions(attributes, 468, 229, 474);
        } else if (upperTitle.contains("LANCOOL 217")) {
            attributes.put("maxGpuLengthMm", 380);
            attributes.put("maxCpuCoolerHeightMm", 180);
            attributes.put("radiatorSupportMm", List.of(120, 240, 280, 360));
            applyDimensions(attributes, 482, 238, 503);
        } else if (upperTitle.contains("H9 FLOW")) {
            attributes.put("maxGpuLengthMm", 435);
            attributes.put("maxCpuCoolerHeightMm", 165);
            attributes.put("radiatorSupportMm", List.of(120, 240, 280, 360));
            applyDimensions(attributes, 466, 290, 495);
        } else if (upperTitle.contains("FRAME 4000D")) {
            attributes.put("maxGpuLengthMm", 370);
            attributes.put("maxCpuCoolerHeightMm", 170);
            attributes.put("radiatorSupportMm", List.of(120, 240, 280, 360));
            applyDimensions(attributes, 486, 239, 486);
        } else if (upperTitle.contains("EVOLV X2")) {
            attributes.put("maxGpuLengthMm", 380);
            attributes.put("maxCpuCoolerHeightMm", 170);
            attributes.put("radiatorSupportMm", List.of(120, 240, 280, 360));
            applyDimensions(attributes, 490, 230, 500);
        } else if (upperTitle.contains("LIGHT BASE 900")) {
            attributes.put("maxGpuLengthMm", 495);
            attributes.put("maxCpuCoolerHeightMm", 190);
            attributes.put("radiatorSupportMm", List.of(120, 240, 280, 360, 420));
            applyDimensions(attributes, 532, 327, 484);
        }
    }

    private static void applyCoolerAttributes(Map<String, Object> attributes, String upperTitle) {
        attributes.put("socketSupport", List.of("AM5", "LGA1851", "LGA1700"));
        if (upperTitle.contains("360") || upperTitle.contains("LIQUID") || upperTitle.contains("KRAKEN") || upperTitle.contains("ICUE") || upperTitle.contains("ARCTIC")) {
            attributes.put("coolerType", "LIQUID_AIO");
            attributes.put("radiatorSizeMm", 360);
            attributes.put("tdpW", 280);
            applyDimensions(attributes, 397, 120, 27);
        } else {
            attributes.put("coolerType", "AIR");
            if (upperTitle.contains("NH-D15")) {
                attributes.put("coolerHeightMm", 168);
                attributes.put("tdpW", 250);
                applyDimensions(attributes, 150, 161, 168);
            } else if (upperTitle.contains("ASSASSIN")) {
                attributes.put("coolerHeightMm", 164);
                attributes.put("tdpW", 280);
                applyDimensions(attributes, 144, 147, 164);
            } else {
                attributes.put("coolerHeightMm", 160);
                attributes.put("tdpW", 200);
                applyDimensions(attributes, 120, 120, 160);
            }
        }
    }

    private static void applyManualSpecOverrides(String category, Map<String, Object> attributes, String upperTitle) {
        switch (category) {
            case "CASE" -> applyManualCaseSpecs(attributes, upperTitle);
            case "COOLER" -> applyManualCoolerSpecs(attributes, upperTitle);
            case "PSU" -> applyManualPsuSpecs(attributes, upperTitle);
            case "GPU" -> applyManualGpuSpecs(attributes, upperTitle);
            default -> {
            }
        }
    }

    private static void applyManualCaseSpecs(Map<String, Object> attributes, String upperTitle) {
        if (upperTitle.contains("MESHIFY 3 XL")) {
            manualSpec(attributes, "https://www.fractal-design.com/app/uploads/2025/05/Meshify-3-XL_Product_Sheet_EN.pdf");
            attributes.put("maxGpuLengthMm", 512);
            attributes.put("maxCpuCoolerHeightMm", 182);
            attributes.put("maxPsuLengthMm", 230);
            attributes.put("gpuSlotHeightMm", 189);
            attributes.put("formFactor", "EATX_ATX_MATX_ITX");
            attributes.put("radiatorSupportMm", List.of(120, 140, 240, 280, 360, 420));
            applyDimensions(attributes, 575, 245, 515);
        } else if (upperTitle.contains("MESHIFY 3")) {
            manualSpec(attributes, "https://www.fractal-design.com/app/uploads/2025/01/Meshify-3_Product_Sheet_EN.pdf");
            attributes.put("maxGpuLengthMm", 349);
            attributes.put("maxCpuCoolerHeightMm", 173);
            attributes.put("maxPsuLengthMm", 180);
            attributes.put("gpuSlotHeightMm", 176);
            attributes.put("formFactor", "ATX_MATX_ITX");
            attributes.put("radiatorSupportMm", List.of(120, 240, 280, 360));
            applyDimensions(attributes, 433, 229, 507);
        } else if (upperTitle.contains("LANCOOL 217")) {
            manualSpec(attributes, "https://lian-li.com/product/lancool-217/");
            attributes.put("maxGpuLengthMm", 380);
            attributes.put("maxCpuCoolerHeightMm", 180);
            attributes.put("maxPsuLengthMm", 220);
            attributes.put("formFactor", "SSI_EEB_EATX_ATX_MATX_ITX");
            attributes.put("radiatorSupportMm", List.of(120, 140, 240, 280, 360));
            applyDimensions(attributes, 482, 238, 503);
        } else if (upperTitle.contains("H9 FLOW")) {
            manualSpec(attributes, "https://nzxt.com/products/h9-flow");
            attributes.put("maxGpuLengthMm", 459);
            attributes.put("maxGpuLengthWithFrontRadiatorMm", 410);
            attributes.put("maxCpuCoolerHeightMm", 165);
            attributes.put("maxPsuLengthMm", 200);
            attributes.put("formFactor", "EATX_ATX_MATX_ITX");
            attributes.put("radiatorSupportMm", List.of(120, 140, 240, 280, 360, 420));
            applyDimensions(attributes, 481, 315, 506);
        } else if (upperTitle.contains("FRAME 4000D")) {
            manualSpec(attributes, "https://www.corsair.com/us/en/explorer/diy-builder/cases/corsair-frame-4000-series/");
            attributes.put("maxGpuLengthMm", 430);
            attributes.put("maxGpuLengthWithFrontFansMm", 405);
            attributes.put("maxCpuCoolerHeightMm", 170);
            attributes.put("maxPsuLengthMm", 220);
            attributes.put("formFactor", "EATX_ATX_MATX_ITX");
            attributes.put("radiatorSupportMm", List.of(120, 140, 240, 280, 360));
            applyDimensions(attributes, 487, 239, 486);
        } else if (upperTitle.contains("EVOLV X2")) {
            manualSpec(attributes, "https://phanteks.com/product/evolv-x2-black/");
            attributes.put("maxGpuLengthMm", 380);
            attributes.put("maxGpuWidthMm", 170);
            attributes.put("maxCpuCoolerHeightMm", 170);
            attributes.put("maxPsuLengthMm", 250);
            attributes.put("formFactor", "EATX_ATX_MATX_ITX");
            attributes.put("radiatorSupportMm", List.of(120, 240, 360));
            applyDimensions(attributes, 454, 228, 588);
        } else if (upperTitle.contains("LIGHT BASE 900")) {
            manualSpec(attributes, "https://www.bequiet.com/en/case/5292");
            attributes.put("maxGpuLengthMm", 495);
            attributes.put("maxCpuCoolerHeightMm", 190);
            attributes.put("maxPsuLengthMm", 225);
            attributes.put("formFactor", "EATX_ATX_MATX_ITX");
            attributes.put("radiatorSupportMm", List.of(120, 140, 240, 280, 360, 420));
            applyDimensions(attributes, 532, 327, 484);
        }
    }

    private static void applyManualCoolerSpecs(Map<String, Object> attributes, String upperTitle) {
        if (upperTitle.contains("LIQUID FREEZER III") && upperTitle.contains("360")) {
            manualSpec(attributes, "https://www.arctic.de/en/Liquid-Freezer-III-Pro-360-A-RGB/ACFRE00184A");
            attributes.put("coolerType", "LIQUID_AIO");
            attributes.put("radiatorSizeMm", 360);
            attributes.put("radiatorLengthMm", 398);
            attributes.put("radiatorWidthMm", 120);
            attributes.put("radiatorThicknessMm", 38);
            attributes.put("socketSupport", List.of("AM5", "AM4", "LGA1851", "LGA1700"));
            applyDimensions(attributes, 398, 120, 38);
        } else if (upperTitle.contains("ASSASSIN IV")) {
            manualSpec(attributes, "https://www.deepcool.com/company/pressroom/newsrelease/2023/17380.shtml");
            attributes.put("coolerType", "AIR");
            attributes.put("coolerHeightMm", 164);
            attributes.put("heatpipeCount", 7);
            attributes.put("socketSupport", List.of("AM5", "AM4", "LGA1851", "LGA1700", "LGA1200", "LGA115X"));
            applyDimensions(attributes, 144, 147, 164);
        } else if (upperTitle.contains("NH-D15 G2")) {
            manualSpec(attributes, "https://www.noctua.at/en/products/nh-d15-g2-chromax-black/specifications");
            attributes.put("coolerType", "AIR");
            attributes.put("coolerHeightMm", 168);
            attributes.put("socketSupport", List.of("AM5", "AM4", "LGA1851", "LGA1700", "LGA1200", "LGA115X"));
            applyDimensions(attributes, 152, 150, 168);
        } else if (upperTitle.contains("TITAN 360")) {
            manualSpec(attributes, "https://www.corsair.com/us/en/p/cpu-coolers/cw-9061018-ww/icue-link-titan-360-rx-rgb-aio-liquid-cpu-cooler-cw-9061018-ww");
            attributes.put("coolerType", "LIQUID_AIO");
            attributes.put("radiatorSizeMm", 360);
            attributes.put("radiatorLengthMm", 396);
            attributes.put("radiatorWidthMm", 120);
            attributes.put("radiatorThicknessMm", 27);
            attributes.put("socketSupport", List.of("AM5", "AM4", "LGA1851", "LGA1700"));
            applyDimensions(attributes, 396, 120, 27);
        } else if (upperTitle.contains("KRAKEN ELITE") && upperTitle.contains("360")) {
            manualSpec(attributes, "https://nzxt.com/products/kraken-360-elite-rgb-1");
            attributes.put("coolerType", "LIQUID_AIO");
            attributes.put("radiatorSizeMm", 360);
            attributes.put("radiatorLengthMm", 401);
            attributes.put("radiatorWidthMm", 120);
            attributes.put("radiatorThicknessMm", 27);
            attributes.put("socketSupport", List.of("AM5", "AM4", "LGA1851", "LGA1700"));
            applyDimensions(attributes, 401, 120, 27);
        }
    }

    private static void applyManualPsuSpecs(Map<String, Object> attributes, String upperTitle) {
        if (upperTitle.contains("RM850") || upperTitle.contains("RM1000") || upperTitle.contains("RMX")) {
            manualSpec(attributes, "https://www.kitguru.net/components/power-supplies/zardon/corsair-rm1000x-atx-v3-1-3rd-gen-2024-review/all/1/");
            attributes.put("atxSpec", "ATX 3.1");
            attributes.put("pcieSpec", "PCIe 5.1");
            attributes.put("gpuConnector", "12V-2x6");
            applyDimensions(attributes, 160, 150, 85);
        } else if (upperTitle.contains("FOCUS") || upperTitle.contains("VERTEX") || upperTitle.contains("GX-")) {
            manualSpec(attributes, "https://seasonic.com/focus-gx-atx-3/");
            attributes.put("atxSpec", "ATX 3.1");
            attributes.put("pcieSpec", "PCIe 5.1");
            attributes.put("gpuConnector", "12V-2x6");
            applyDimensions(attributes, 140, 150, 86);
        } else if (upperTitle.contains("HYDRO G") || upperTitle.contains("MEGA GM") || upperTitle.contains("VIC GM") || upperTitle.contains("VITA GM")) {
            manualSpec(attributes, "https://www.tomshardware.com/reviews/fsp-hydro-g-pro-1000w-atx-v30-power-supply-review");
            attributes.put("atxSpec", "ATX 3.1");
            attributes.put("pcieSpec", "PCIe 5.1");
            attributes.put("gpuConnector", "12V-2x6");
            applyDimensions(attributes, 150, 150, 86);
        } else if (upperTitle.contains("LEADEX") || upperTitle.contains("SUPERFLOWER")) {
            manualSpec(attributes, "https://hwbusters.com/psus/super-flower-leadex-vii-pro-1000w-atx-v3-1-psu-review/");
            attributes.put("atxSpec", "ATX 3.1");
            attributes.put("pcieSpec", "PCIe 5.1");
            attributes.put("gpuConnector", "12V-2x6");
            applyDimensions(attributes, 150, 150, 85);
        } else if (upperTitle.contains("A850GS") || upperTitle.contains("A850GN") || upperTitle.contains("A1000GS") || upperTitle.contains("A1000GL")) {
            manualSpec(attributes, "https://www.msi.com/Power-Supply/MPG-A850GS-PCIE5");
            attributes.put("atxSpec", "ATX 3.1");
            attributes.put("pcieSpec", "PCIe 5.1");
            attributes.put("gpuConnector", "12V-2x6");
            applyDimensions(attributes, 150, 150, 86);
        } else if (upperTitle.contains("MWE GOLD")) {
            manualSpec(attributes, "https://www.coolermaster.com/en-global/products/mwe-gold-850-v3-atx-3-1/");
            attributes.put("atxSpec", "ATX 3.1");
            attributes.put("pcieSpec", "PCIe 5.1");
            attributes.put("gpuConnector", "12V-2x6");
            applyDimensions(attributes, 160, 150, 86);
        }
    }

    private static void applyManualGpuSpecs(Map<String, Object> attributes, String upperTitle) {
        if (upperTitle.contains("ROG ASTRAL") && upperTitle.contains("5090")) {
            manualSpec(attributes, "https://rog.asus.com/graphics-cards/graphics-cards/rog-astral/rog-astral-rtx5090-o32g-gaming/spec/");
            attributes.put("lengthMm", 357.6);
            attributes.put("widthMm", 149.3);
            attributes.put("heightMm", 76);
            attributes.put("slotWidth", 3.8);
        } else if ((upperTitle.contains("SUPRIM") || upperTitle.contains("슈프림")) && upperTitle.contains("5090")) {
            manualSpec(attributes, "https://www.msi.com/Graphics-Card/GeForce-RTX-5090-32G-SUPRIM-SOC/Specification");
            attributes.put("lengthMm", 359);
            attributes.put("widthMm", 150);
            attributes.put("heightMm", 76);
            attributes.put("slotWidth", 3.8);
            attributes.put("wattage", 575);
        } else if ((upperTitle.contains("AORUS") || upperTitle.contains("MASTER")) && upperTitle.contains("5090")) {
            manualSpec(attributes, "https://www.aorus.com/graphics-cards/GV-N5090AORUS-M-32GD/Specification");
            attributes.put("lengthMm", 360);
            attributes.put("widthMm", 150);
            attributes.put("heightMm", 75);
            attributes.put("slotWidth", 3.7);
        } else if ((upperTitle.contains("ZOTAC") || upperTitle.contains("조텍")) && upperTitle.contains("5090")) {
            manualSpec(attributes, "https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5090-solid-oc");
            attributes.put("lengthMm", 329.7);
            attributes.put("widthMm", 137.8);
            attributes.put("heightMm", 67.8);
            attributes.put("slotWidth", 3.5);
        } else if ((upperTitle.contains("VANGUARD") || upperTitle.contains("뱅가드")) && upperTitle.contains("5080")) {
            manualSpec(attributes, "https://www.msi.com/Graphics-Card/GeForce-RTX-5080-16G-VANGUARD-SOC/Specification");
            attributes.put("lengthMm", 357);
            attributes.put("widthMm", 151);
            attributes.put("heightMm", 66);
            attributes.put("slotWidth", 3.3);
        } else if ((upperTitle.contains("AORUS") || upperTitle.contains("MASTER")) && upperTitle.contains("5080")) {
            manualSpec(attributes, "https://www.aorus.com/graphics-cards/GV-N5080AORUSM-ICE-16GD/Specification");
            attributes.put("lengthMm", 360);
            attributes.put("widthMm", 150);
            attributes.put("heightMm", 75);
            attributes.put("slotWidth", 3.7);
        } else if (upperTitle.contains("PRIME") && upperTitle.contains("5070 TI")) {
            manualSpec(attributes, "https://www.asus.com/us/motherboards-components/graphics-cards/prime/prime-rtx5070ti-16g/techspec/");
            attributes.put("lengthMm", 304);
            attributes.put("widthMm", 126);
            attributes.put("heightMm", 50);
            attributes.put("slotWidth", 2.5);
        } else if ((upperTitle.contains("VENTUS") || upperTitle.contains("벤투스")) && upperTitle.contains("5060 TI")) {
            manualSpec(attributes, "https://www.msi.com/Graphics-Card/GeForce-RTX-5060-Ti-16G-VENTUS-2X-OC-PLUS/Specification");
            attributes.put("lengthMm", 227);
            attributes.put("widthMm", 126);
            attributes.put("heightMm", 41);
            attributes.put("slotWidth", 2.0);
        } else if ((upperTitle.contains("ZOTAC") || upperTitle.contains("조텍")) && upperTitle.contains("5060")) {
            manualSpec(attributes, "https://www.zotac.com/us/product/graphics_card/zotac-gaming-geforce-rtx-5060-twin-edge-oc");
            attributes.put("lengthMm", 220.5);
            attributes.put("slotWidth", 2.0);
        }
        if ("MANUAL_PRODUCT_SPEC".equals(attributes.get("specSource"))) {
            attributes.put("powerConnector", "12V-2x6");
        }
    }

    private static void manualSpec(Map<String, Object> attributes, String referenceUrl) {
        attributes.put("specSource", "MANUAL_PRODUCT_SPEC");
        attributes.put("specConfidence", "VERIFIED_FIXED_SPEC");
        attributes.put("specReferenceUrl", referenceUrl);
    }

    private static void applyAiSpecAttributes(Map<String, Object> attributes, Map<String, Object> offer) {
        Map<String, Object> rawPayload = jsonMap(offer.get("rawPayload"));
        Map<String, Object> release = mapValue(rawPayload.get("manufacturerRelease"));
        Map<String, Object> aiSpecs = mapValue(release.get("aiSpecAttributes"));
        if (aiSpecs.isEmpty()) {
            return;
        }
        Set<String> allowedKeys = Set.of(
                "socket", "architecture", "coreCount", "threadCount", "tdpW",
                "gpuClass", "series", "vramGb", "memoryType", "lengthMm", "widthMm", "heightMm", "depthMm", "slotWidth",
                "wattage", "requiredSystemPowerW", "powerConnector",
                "chipset", "formFactor", "capacityGb", "moduleCount", "speedMhz",
                "interface", "generation", "readMbps", "writeMbps",
                "capacityW", "efficiency", "atxSpec", "pcieSpec", "gpuConnector", "modular",
                "maxGpuLengthMm", "maxCpuCoolerHeightMm", "maxPsuLengthMm", "radiatorSupportMm",
                "coolerType", "socketSupport", "radiatorLengthMm", "radiatorWidthMm", "radiatorThicknessMm", "coolerHeightMm",
                "specReferenceUrl"
        );
        int applied = 0;
        for (Map.Entry<String, Object> entry : aiSpecs.entrySet()) {
            if (!allowedKeys.contains(entry.getKey())) {
                continue;
            }
            Object value = normalizedSpecValue(entry.getValue());
            if (value == null) {
                continue;
            }
            attributes.put(entry.getKey(), value);
            applied += 1;
        }
        if (applied == 0) {
            return;
        }
        attributes.put("specSource", "AI_OFFICIAL_RELEASE_EXTRACTION");
        attributes.put("specConfidence", "AI_EXTRACTED_FROM_OFFICIAL_POST");
        String officialUrl = stringValue(release.get("officialUrl"));
        if (StringUtils.hasText(officialUrl)) {
            attributes.put("specReferenceUrl", officialUrl);
        }
        attributes.put("aiSpecExtraction", MockData.map(
                "officialUrl", officialUrl,
                "officialTitle", release.get("officialTitle"),
                "reason", release.get("aiSpecReason"),
                "missingSpecFields", release.get("missingSpecFields")
        ));
        attributes.put("metadataVersion", 6);
    }

    private static Object normalizedSpecValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> filtered = list.stream()
                    .map(NaverShoppingOfferService::normalizedSpecValue)
                    .filter(item -> item != null)
                    .toList();
            return filtered.isEmpty() ? null : filtered;
        }
        return null;
    }

    private static void applyCpuCoreAndPower(Map<String, Object> attributes, String upperTitle) {
        if (upperTitle.contains("9950")) {
            attributes.put("coreCount", 16);
            attributes.put("threadCount", 32);
            attributes.put("tdpW", upperTitle.contains("X3D") ? 120 : 170);
        } else if (upperTitle.contains("9900")) {
            attributes.put("coreCount", 12);
            attributes.put("threadCount", 24);
            attributes.put("tdpW", 120);
        } else if (upperTitle.contains("9800") || upperTitle.contains("9700")) {
            attributes.put("coreCount", 8);
            attributes.put("threadCount", 16);
            attributes.put("tdpW", upperTitle.contains("9700") ? 65 : 120);
        } else if (upperTitle.contains("9600")) {
            attributes.put("coreCount", 6);
            attributes.put("threadCount", 12);
            attributes.put("tdpW", 65);
        } else if (upperTitle.contains("285K")) {
            attributes.put("coreCount", 24);
            attributes.put("threadCount", 24);
            attributes.put("tdpW", 125);
        } else if (upperTitle.contains("265K")) {
            attributes.put("coreCount", 20);
            attributes.put("threadCount", 20);
            attributes.put("tdpW", 125);
        } else if (upperTitle.contains("245K")) {
            attributes.put("coreCount", 14);
            attributes.put("threadCount", 14);
            attributes.put("tdpW", 125);
        }
    }

    private static void applyMemorySpeed(Map<String, Object> attributes, String upperTitle) {
        Matcher matcher = SPEED_PATTERN.matcher(upperTitle);
        if (matcher.find()) {
            attributes.put("speedMhz", Integer.parseInt(matcher.group(1)));
        }
    }

    private static int inferredGpuLengthMm(String upperTitle) {
        if (upperTitle.contains("SFF") || upperTitle.contains("DUAL") || upperTitle.contains("2X")) {
            return upperTitle.contains("5090") ? 304 : upperTitle.contains("5080") ? 280 : 250;
        }
        if (upperTitle.contains("ASTRAL") || upperTitle.contains("SUPRIM") || upperTitle.contains("MASTER")) {
            return upperTitle.contains("5090") ? 360 : 340;
        }
        if (upperTitle.contains("5090")) {
            return 340;
        }
        if (upperTitle.contains("5080")) {
            return 330;
        }
        if (upperTitle.contains("5070 TI")) {
            return 320;
        }
        if (upperTitle.contains("5070")) {
            return 300;
        }
        return upperTitle.contains("5060") ? 245 : 304;
    }

    private static double inferredGpuSlotWidth(String upperTitle) {
        if (upperTitle.contains("SFF") || upperTitle.contains("DUAL") || upperTitle.contains("2X")) {
            return 2.5;
        }
        if (upperTitle.contains("5090") || upperTitle.contains("ASTRAL") || upperTitle.contains("SUPRIM")) {
            return 3.5;
        }
        return 3.0;
    }

    private static void applyDimensions(Map<String, Object> attributes, int depthMm, int widthMm, int heightMm) {
        attributes.put("depthMm", depthMm);
        attributes.put("widthMm", widthMm);
        attributes.put("heightMm", heightMm);
        attributes.put("dimensionsMm", MockData.map(
                "depth", depthMm,
                "width", widthMm,
                "height", heightMm
        ));
    }

    private static boolean toolReadyFor(String category, Map<String, Object> attributes) {
        return switch (category) {
            case "CPU" -> hasAll(attributes, "socket", "architecture", "coreCount", "threadCount", "tdpW");
            case "MOTHERBOARD" -> hasAll(attributes, "socket", "chipset", "memoryType", "formFactor", "widthMm", "depthMm");
            case "RAM" -> hasAll(attributes, "memoryType", "capacityGb", "speedMhz", "moduleCount", "formFactor");
            case "GPU" -> hasAll(attributes, "architecture", "wattage", "requiredSystemPowerW", "powerConnector", "vramGb", "memoryType", "lengthMm", "widthMm", "heightMm", "slotWidth");
            case "STORAGE" -> hasAll(attributes, "interface", "capacityGb", "formFactor", "readMbps", "writeMbps");
            case "PSU" -> hasAll(attributes, "capacityW", "wattage", "atxSpec", "efficiency", "gpuConnector", "widthMm", "heightMm", "depthMm");
            case "CASE" -> hasAll(attributes, "formFactor", "maxGpuLengthMm", "maxCpuCoolerHeightMm", "radiatorSupportMm", "maxPsuLengthMm", "widthMm", "heightMm", "depthMm");
            case "COOLER" -> coolerToolReady(attributes);
            default -> false;
        };
    }

    private static boolean coolerToolReady(Map<String, Object> attributes) {
        if (!hasAll(attributes, "coolerType", "socketSupport", "tdpW", "widthMm", "heightMm", "depthMm")) {
            return false;
        }
        String coolerType = String.valueOf(attributes.get("coolerType"));
        if ("AIR".equals(coolerType)) {
            return hasAll(attributes, "coolerHeightMm");
        }
        if ("LIQUID".equals(coolerType) || "LIQUID_AIO".equals(coolerType)) {
            return hasAll(attributes, "radiatorLengthMm", "radiatorWidthMm", "radiatorThicknessMm");
        }
        return false;
    }

    private static boolean hasAll(Map<String, Object> attributes, String... keys) {
        for (String key : keys) {
            if (attributes.get(key) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAcceptableCatalogOffer(String category, Map<String, Object> offer) {
        String title = stringValue(offer.get("title"));
        if (!StringUtils.hasText(title)) {
            return false;
        }
        String upperTitle = title.toUpperCase(Locale.ROOT);
        if (upperTitle.contains("중고") || upperTitle.contains("렌탈") || upperTitle.contains("대여")) {
            return false;
        }
        if ("GPU".equals(category)) {
            return !upperTitle.contains("GPU 없음")
                    && !upperTitle.contains("그래픽 카드GPU 없음")
                    && !upperTitle.contains("피규어")
                    && !upperTitle.contains("장식")
                    && !upperTitle.contains("모형")
                    && !upperTitle.contains("수집 가능한 모델");
        }
        return true;
    }

    private static boolean isReasonableOfferMatch(String category, String name, Map<String, Object> offer) {
        String title = stringValue(offer.get("title"));
        if (!StringUtils.hasText(name) || !StringUtils.hasText(title)) {
            return false;
        }
        String normalizedName = normalizeForMatch(name);
        String normalizedTitle = normalizeForMatch(title);
        if (hasOptionConflict(category, normalizedName, normalizedTitle)) {
            return false;
        }
        List<String> tokens = importantTokens(name);
        if (tokens.isEmpty()) {
            return false;
        }
        int matched = 0;
        int requiredModelTokens = 0;
        int matchedRequiredModelTokens = 0;
        for (String token : tokens) {
            boolean contains = normalizedTitle.contains(token);
            if (contains) {
                matched += 1;
            }
            if (isModelToken(token)) {
                requiredModelTokens += 1;
                if (contains) {
                    matchedRequiredModelTokens += 1;
                }
            }
        }
        if (requiredModelTokens > 0 && matchedRequiredModelTokens < requiredModelTokens) {
            return false;
        }
        double coverage = matched / (double) tokens.size();
        return coverage >= 0.6;
    }

    private static int offerMatchScore(String name, String manufacturer, Map<String, Object> offer) {
        String title = stringValue(offer.get("title"));
        String normalizedTitle = normalizeForMatch(title);
        String normalizedName = normalizeForMatch(name);
        List<String> tokens = importantTokens(name);
        int matched = 0;
        for (String token : tokens) {
            if (normalizedTitle.contains(token)) {
                matched += 1;
            }
        }
        int score = matched * 10;
        if (StringUtils.hasText(normalizedName) && normalizedTitle.contains(normalizedName)) {
            score += 40;
        }
        String normalizedManufacturer = normalizeForMatch(manufacturer);
        if (StringUtils.hasText(normalizedManufacturer) && normalizedTitle.contains(normalizedManufacturer)) {
            score += 10;
        }
        Object rawPayload = offer.get("rawPayload");
        if (rawPayload instanceof Map<?, ?> payload && "1".equals(stringValue(payload.get("productType")))) {
            score += 30;
        }
        String supplierName = normalizeForMatch(stringValue(offer.get("supplierName")));
        if ("네이버".equals(supplierName)) {
            score += 20;
        }
        return score;
    }

    private static boolean hasOptionConflict(String category, String normalizedName, String normalizedTitle) {
        if (normalizedName.contains("화이트") && normalizedTitle.contains("블랙")) {
            return true;
        }
        if (normalizedName.contains("블랙") && normalizedTitle.contains("화이트")) {
            return true;
        }
        if (!normalizedName.contains("LCD") && normalizedTitle.contains("LCD")) {
            return true;
        }
        if (!normalizedName.contains("LBC") && normalizedTitle.contains("LBC")) {
            return true;
        }
        if (!normalizedName.contains("HBC") && normalizedTitle.contains("HBC")) {
            return true;
        }
        if (!normalizedName.contains("PINK") && normalizedTitle.contains("PINK")) {
            return true;
        }
        if (!normalizedName.contains("세트") && !normalizedName.contains("패키지") && !normalizedName.contains("번들")
                && (normalizedTitle.contains("세트") || normalizedTitle.contains("패키지") || normalizedTitle.contains("번들") || normalizedTitle.contains("+"))) {
            return true;
        }
        if ("CPU".equals(category) && !normalizedName.contains("메인보드")
                && (normalizedTitle.contains("메인보드") || normalizedTitle.contains("마더보드"))) {
            return true;
        }
        return "CASE".equals(category) && !normalizedName.contains("PSU") && normalizedTitle.contains("PSU");
    }

    private static List<String> importantTokens(String value) {
        String normalized = normalizeForMatch(value);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            if (token.length() < 2 || MATCH_STOPWORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static boolean isModelToken(String token) {
        return token.length() >= 3 && token.matches(".*\\d.*");
    }

    private static String normalizeForMatch(String value) {
        String cleaned = cleanText(value);
        if (!StringUtils.hasText(cleaned)) {
            return "";
        }
        return cleaned
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^0-9A-Z가-힣]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String manufacturerGuess(Map<?, ?> item) {
        String maker = stringValue(item.get("maker"));
        if (StringUtils.hasText(maker)) {
            return cleanText(maker);
        }
        String brand = stringValue(item.get("brand"));
        if (StringUtils.hasText(brand)) {
            return cleanText(brand);
        }
        return null;
    }

    private static String sourceProductKey(Map<?, ?> item) {
        String productId = stringValue(item.get("productId"));
        if (StringUtils.hasText(productId)) {
            return limited(productId, 500);
        }
        String link = stringValue(item.get("link"));
        if (StringUtils.hasText(link)) {
            return limited(link, 500);
        }
        return limited(cleanText(stringValue(item.get("title"))) + "::" + stringValue(item.get("mallName")), 500);
    }

    private static String stableCandidateSourceProductKey(
            String existingKey,
            String source,
            String category,
            String title,
            String offerUrl,
            String searchQuery
    ) {
        if (StringUtils.hasText(existingKey)) {
            return limited(existingKey.trim(), 500);
        }
        String raw = String.join("::",
                fallbackToken(source, "UNKNOWN_SOURCE"),
                fallbackToken(category, "UNKNOWN_CATEGORY"),
                fallbackToken(title, "UNTITLED"),
                fallbackToken(offerUrl, ""),
                fallbackToken(searchQuery, "")
        );
        String uuid = UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
        return limited("SERVER:" + uuid, 500);
    }

    private static String fallbackToken(String value, String fallback) {
        String cleaned = cleanText(value);
        return StringUtils.hasText(cleaned) ? cleaned : fallback;
    }

    private static String joinQueries(List<String> queries) {
        String joined = String.join(" | ", queries);
        return joined.length() <= 255 ? joined : joined.substring(0, 255);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String limited(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String json(Object value) {
        try {
            if (value != null && value.getClass().getName().equals("org.postgresql.util.PGobject")) {
                String text = String.valueOf(value);
                if (StringUtils.hasText(text)) {
                    OBJECT_MAPPER.readTree(text);
                    return text;
                }
            }
            if (value instanceof String text) {
                if (!StringUtils.hasText(text)) {
                    return "{}";
                }
                try {
                    OBJECT_MAPPER.readTree(text);
                    return text;
                } catch (Exception ignored) {
                    return OBJECT_MAPPER.writeValueAsString(text);
                }
            }
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private static Map<String, Object> jsonMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        try {
            String text = String.valueOf(value);
            if (!StringUtils.hasText(text)) {
                return Map.of();
            }
            return OBJECT_MAPPER.readValue(text, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private void audit(CurrentUserService.CurrentUser admin, String action, String targetType, String targetId, Object metadata) {
        if (admin == null || admin.internalId() == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (actor_user_id, action, target_type, target_id, metadata, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, now())
                """,
                admin.internalId(),
                action,
                targetType,
                targetId,
                json(metadata == null ? Map.of() : metadata)
        );
    }

    private static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 필요합니다.");
        }
        return value.trim();
    }

    private static void requireUuid(String value, String fieldName) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + "는 UUID 형식이어야 합니다.");
        }
    }

    private record CatalogCandidate(long id, String publicId, Long publishedPartId) {
    }
}
