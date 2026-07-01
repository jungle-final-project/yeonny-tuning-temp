package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManufacturerReleaseIntakeService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private static final Set<String> SOURCE_TYPES = Set.of("NEWS", "PRODUCT_RELEASE", "SUPPORT_NEWS", "RSS", "SITEMAP");
    private static final Pattern RSS_ITEM_PATTERN = Pattern.compile("<item\\b[^>]*>(.*?)</item>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_VALUE_PATTERN = Pattern.compile("<%s\\b[^>]*>(.*?)</%s>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_PATTERN = Pattern.compile("<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final JdbcTemplate jdbcTemplate;
    private final NaverShoppingOfferService naverShoppingOfferService;
    private final RestClient restClient;

    public ManufacturerReleaseIntakeService(
            JdbcTemplate jdbcTemplate,
            NaverShoppingOfferService naverShoppingOfferService,
            @Value("${part.manufacturer-release-intake.user-agent:BuildGraphBot/0.1}") String userAgent
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.naverShoppingOfferService = naverShoppingOfferService;
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    public Map<String, Object> listSources(Boolean enabled) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT public_id::text AS id,
                       manufacturer,
                       category_scope AS "categoryScope",
                       source_type AS "sourceType",
                       source_url AS "sourceUrl",
                       enabled,
                       poll_interval_minutes AS "pollIntervalMinutes",
                       last_checked_at AS "lastCheckedAt",
                       status,
                       error_summary AS "errorSummary",
                       created_at AS "createdAt",
                       updated_at AS "updatedAt"
                FROM manufacturer_sources
                WHERE deleted_at IS NULL
                """);
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        sql.append(" ORDER BY manufacturer, source_url");
        return MockData.map("items", jdbcTemplate.queryForList(sql.toString(), params.toArray()));
    }

    public Map<String, Object> createSource(Map<String, Object> request) {
        String manufacturer = requireText(request, "manufacturer");
        String categoryScope = categoryScope(value(request, "categoryScope", "ALL"));
        String sourceType = sourceType(value(request, "sourceType", "NEWS"));
        String sourceUrl = requireText(request, "sourceUrl");
        boolean enabled = booleanValue(request.get("enabled"), true);
        int pollInterval = boundedInt(request.get("pollIntervalMinutes"), 1440, 30, 10080);
        Map<String, Object> parserConfig = mapValue(request.get("parserConfig"));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO manufacturer_sources (
                  manufacturer,
                  category_scope,
                  source_type,
                  source_url,
                  enabled,
                  poll_interval_minutes,
                  parser_config,
                  status,
                  created_at,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'ACTIVE', now(), now())
                ON CONFLICT (source_url) WHERE deleted_at IS NULL
                DO UPDATE SET
                  manufacturer = EXCLUDED.manufacturer,
                  category_scope = EXCLUDED.category_scope,
                  source_type = EXCLUDED.source_type,
                  enabled = EXCLUDED.enabled,
                  poll_interval_minutes = EXCLUDED.poll_interval_minutes,
                  parser_config = EXCLUDED.parser_config,
                  status = 'ACTIVE',
                  error_summary = NULL,
                  updated_at = now()
                RETURNING public_id::text AS id,
                          manufacturer,
                          category_scope AS "categoryScope",
                          source_type AS "sourceType",
                          source_url AS "sourceUrl",
                          enabled,
                          poll_interval_minutes AS "pollIntervalMinutes",
                          status
                """,
                manufacturer,
                categoryScope,
                sourceType,
                sourceUrl,
                enabled,
                pollInterval,
                json(parserConfig)
        );
        return row;
    }

    public Map<String, Object> updateSource(String sourceId, Map<String, Object> request) {
        Map<String, Object> existing = sourceByPublicId(sourceId);
        String manufacturer = value(request, "manufacturer", stringValue(existing.get("manufacturer")));
        String categoryScope = categoryScope(value(request, "categoryScope", stringValue(existing.get("category_scope"))));
        String sourceType = sourceType(value(request, "sourceType", stringValue(existing.get("source_type"))));
        String sourceUrl = value(request, "sourceUrl", stringValue(existing.get("source_url")));
        boolean enabled = booleanValue(request.get("enabled"), Boolean.TRUE.equals(existing.get("enabled")));
        int pollInterval = boundedInt(request.get("pollIntervalMinutes"), intValue(existing.get("poll_interval_minutes"), 1440), 30, 10080);
        String status = status(value(request, "status", stringValue(existing.get("status"))));
        Object parserConfig = request.containsKey("parserConfig") ? mapValue(request.get("parserConfig")) : existing.get("parser_config");

        return jdbcTemplate.queryForMap("""
                UPDATE manufacturer_sources
                SET manufacturer = ?,
                    category_scope = ?,
                    source_type = ?,
                    source_url = ?,
                    enabled = ?,
                    poll_interval_minutes = ?,
                    parser_config = ?::jsonb,
                    status = ?,
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                RETURNING public_id::text AS id,
                          manufacturer,
                          category_scope AS "categoryScope",
                          source_type AS "sourceType",
                          source_url AS "sourceUrl",
                          enabled,
                          poll_interval_minutes AS "pollIntervalMinutes",
                          status,
                          updated_at AS "updatedAt"
                """,
                manufacturer,
                categoryScope,
                sourceType,
                sourceUrl,
                enabled,
                pollInterval,
                json(parserConfig),
                status,
                sourceId
        );
    }

    public Map<String, Object> scanAll(Integer limitPerSource, Boolean createCandidates) {
        int safeLimit = boundedInt(limitPerSource, 20, 1, 100);
        List<Map<String, Object>> sources = jdbcTemplate.queryForList("""
                SELECT public_id::text AS public_id
                FROM manufacturer_sources
                WHERE enabled = true
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                ORDER BY coalesce(last_checked_at, '1970-01-01'::timestamptz), manufacturer
                """);
        int scanned = 0;
        int newPosts = 0;
        int candidates = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            Map<String, Object> result = scanSource(stringValue(source.get("public_id")), safeLimit, createCandidates);
            results.add(result);
            scanned += 1;
            newPosts += intValue(result.get("newPosts"), 0);
            candidates += intValue(result.get("createdCandidates"), 0);
        }
        return MockData.map(
                "scannedSources", scanned,
                "newPosts", newPosts,
                "createdCandidates", candidates,
                "results", results
        );
    }

    public Map<String, Object> scanSource(String sourceId, Integer limit, Boolean createCandidates) {
        int safeLimit = boundedInt(limit, 20, 1, 100);
        boolean shouldCreateCandidates = !Boolean.FALSE.equals(createCandidates);
        Map<String, Object> source = sourceByPublicId(sourceId);
        try {
            FetchResult fetch = fetch(stringValue(source.get("source_url")));
            String contentHash = sha256(fetch.body());
            boolean unchanged = contentHash.equals(stringValue(source.get("last_content_hash")));
            List<PostDraft> drafts = unchanged ? List.of() : extractPosts(source, fetch.body(), safeLimit);
            int newPosts = 0;
            int updatedPosts = 0;
            int ignored = 0;
            int productPosts = 0;
            int createdCandidates = 0;
            List<Map<String, Object>> posts = new ArrayList<>();
            for (PostDraft draft : drafts) {
                Classification classification = classify(source, draft);
                PostRecord post = upsertPost(source, draft, classification);
                if (post.newPost()) {
                    newPosts += 1;
                } else if (post.contentChanged()) {
                    updatedPosts += 1;
                }
                if ("IGNORED".equals(classification.status())) {
                    ignored += 1;
                }
                if ("PRODUCT_CANDIDATE".equals(classification.status())) {
                    productPosts += 1;
                    if (shouldCreateCandidates && post.createdCatalogCandidateId() == null) {
                        Map<String, Object> candidateResult = createCandidateForPost(source, post, draft, classification);
                        if (Boolean.TRUE.equals(candidateResult.get("created"))) {
                            createdCandidates += 1;
                        }
                        posts.add(MockData.map(
                                "postId", post.publicId(),
                                "title", draft.title(),
                                "classificationStatus", classification.status(),
                                "candidateResult", candidateResult
                        ));
                    } else {
                        posts.add(MockData.map(
                                "postId", post.publicId(),
                                "title", draft.title(),
                                "classificationStatus", classification.status(),
                                "candidateResult", null
                        ));
                    }
                }
            }
            updateSourceScanSuccess(sourceId, fetch, contentHash);
            return MockData.map(
                    "sourceId", sourceId,
                    "manufacturer", source.get("manufacturer"),
                    "unchanged", unchanged,
                    "parsedPosts", drafts.size(),
                    "newPosts", newPosts,
                    "updatedPosts", updatedPosts,
                    "ignoredPosts", ignored,
                    "productPosts", productPosts,
                    "createdCandidates", createdCandidates,
                    "posts", posts
            );
        } catch (RuntimeException exception) {
            updateSourceScanFailure(sourceId, exception.getMessage());
            throw exception;
        }
    }

    public Map<String, Object> listPosts(String status, String category, Integer page, Integer size) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safeSize = Math.min(Math.max(size == null ? 20 : size, 1), 100);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE p.deleted_at IS NULL");
        if (StringUtils.hasText(status)) {
            where.append(" AND p.classification_status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(category)) {
            where.append(" AND p.detected_category = ?");
            params.add(categoryScope(category));
        }
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM manufacturer_posts p " + where, Long.class, params.toArray());
        params.add(safeSize);
        params.add(safePage * safeSize);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT p.public_id::text AS id,
                       s.manufacturer,
                       s.source_url AS "sourceUrl",
                       p.external_url AS "externalUrl",
                       p.title,
                       p.published_at AS "publishedAt",
                       p.classification_status AS "classificationStatus",
                       p.detected_category AS "detectedCategory",
                       p.detected_product_name AS "detectedProductName",
                       p.confidence,
                       c.public_id::text AS "catalogCandidateId",
                       p.created_at AS "createdAt",
                       p.updated_at AS "updatedAt"
                FROM manufacturer_posts p
                JOIN manufacturer_sources s ON s.id = p.manufacturer_source_id
                LEFT JOIN part_catalog_candidates c ON c.id = p.created_catalog_candidate_id
                """ + where + """
                ORDER BY p.created_at DESC
                LIMIT ? OFFSET ?
                """, params.toArray());
        return MockData.map("items", items, "page", safePage, "size", safeSize, "total", total);
    }

    public Map<String, Object> listCatalogCandidates(String status, String category, String source, Integer page, Integer size) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safeSize = Math.min(Math.max(size == null ? 20 : size, 1), 100);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE c.deleted_at IS NULL");
        if (StringUtils.hasText(status)) {
            where.append(" AND c.candidate_status = ?");
            params.add(status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(category)) {
            where.append(" AND c.category = ?");
            params.add(categoryScope(category));
        }
        if (StringUtils.hasText(source)) {
            where.append(" AND c.source = ?");
            params.add(source.trim().toUpperCase(Locale.ROOT));
        }
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM part_catalog_candidates c " + where, Long.class, params.toArray());
        params.add(safeSize);
        params.add(safePage * safeSize);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT c.public_id::text AS id,
                       c.source,
                       c.category,
                       c.search_query AS "searchQuery",
                       c.title,
                       c.manufacturer_guess AS "manufacturerGuess",
                       c.image_url AS "imageUrl",
                       c.supplier_name AS "supplierName",
                       c.offer_url AS "offerUrl",
                       c.low_price AS "lowPrice",
                       c.candidate_status AS "candidateStatus",
                       p.public_id::text AS "publishedPartId",
                       p.status AS "publishedPartStatus",
                       c.discovered_at AS "discoveredAt",
                       c.last_seen_at AS "lastSeenAt"
                FROM part_catalog_candidates c
                LEFT JOIN parts p ON p.id = c.published_part_id
                """ + where + """
                ORDER BY c.last_seen_at DESC
                LIMIT ? OFFSET ?
                """, params.toArray());
        return MockData.map("items", items, "page", safePage, "size", safeSize, "total", total);
    }

    private Map<String, Object> createCandidateForPost(
            Map<String, Object> source,
            PostRecord post,
            PostDraft draft,
            Classification classification
    ) {
        long jobId = createCatalogJob(classification.category(), classification.searchQuery());
        Map<String, Object> releaseContext = MockData.map(
                "manufacturerSourceId", source.get("public_id"),
                "manufacturerPostId", post.publicId(),
                "officialUrl", draft.url(),
                "officialTitle", draft.title(),
                "detectedProductName", classification.productName(),
                "classificationReason", classification.reason(),
                "classificationMethod", classification.method(),
                "confidence", classification.confidence()
        );
        Map<String, Object> result = naverShoppingOfferService.createManufacturerReleaseCandidate(
                jobId,
                classification.category(),
                classification.searchQuery(),
                releaseContext
        );
        finishCatalogJob(
                jobId,
                Boolean.TRUE.equals(result.get("created")) ? "SUCCEEDED" : "FAILED",
                intValue(result.get("attempted"), 0),
                Boolean.TRUE.equals(result.get("created")) ? 1 : 0,
                0,
                Boolean.TRUE.equals(result.get("created")) ? null : stringValue(result.get("message"))
        );
        if (Boolean.TRUE.equals(result.get("created"))) {
            Long candidateId = internalCandidateId(stringValue(result.get("candidateId")));
            jdbcTemplate.update("""
                    UPDATE manufacturer_posts
                    SET created_catalog_candidate_id = ?,
                        raw_payload = coalesce(raw_payload, '{}'::jsonb) || ?::jsonb,
                        updated_at = now()
                    WHERE id = ?
                    """,
                    candidateId,
                    json(MockData.map("naverCandidate", result)),
                    post.id()
            );
        }
        return result;
    }

    private long createCatalogJob(String category, String searchQuery) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO part_catalog_refresh_jobs (
                  source,
                  category,
                  search_query,
                  status,
                  started_at,
                  created_at
                )
                VALUES ('MANUFACTURER_RELEASE_NAVER_SEARCH', ?, ?, 'RUNNING', now(), now())
                RETURNING id
                """, category, limited(searchQuery, 255));
        return ((Number) row.get("id")).longValue();
    }

    private void finishCatalogJob(long jobId, String status, int attempted, int discovered, int published, String errorSummary) {
        jdbcTemplate.update("""
                UPDATE part_catalog_refresh_jobs
                SET status = ?,
                    attempted_count = ?,
                    discovered_count = ?,
                    published_count = ?,
                    error_summary = ?,
                    finished_at = now()
                WHERE id = ?
                """, status, attempted, discovered, published, errorSummary, jobId);
    }

    private Long internalCandidateId(String publicId) {
        if (!StringUtils.hasText(publicId)) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id
                FROM part_catalog_candidates
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                LIMIT 1
                """, publicId);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private PostRecord upsertPost(Map<String, Object> source, PostDraft draft, Classification classification) {
        Long sourceId = ((Number) source.get("id")).longValue();
        List<Map<String, Object>> existingRows = jdbcTemplate.queryForList("""
                SELECT id,
                       public_id::text AS public_id,
                       content_hash,
                       created_catalog_candidate_id
                FROM manufacturer_posts
                WHERE manufacturer_source_id = ?
                  AND external_url = ?
                  AND deleted_at IS NULL
                LIMIT 1
                """, sourceId, draft.url());
        String rawPayload = json(MockData.map(
                "classification", classification.toMap(),
                "officialPost", MockData.map(
                        "url", draft.url(),
                        "title", draft.title(),
                        "excerpt", draft.excerpt()
                )
        ));
        if (existingRows.isEmpty()) {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    INSERT INTO manufacturer_posts (
                      manufacturer_source_id,
                      external_url,
                      title,
                      published_at,
                      content_hash,
                      excerpt,
                      raw_payload,
                      classification_status,
                      detected_category,
                      detected_product_name,
                      confidence,
                      created_at,
                      updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, now(), now())
                    RETURNING id, public_id::text AS public_id
                    """,
                    sourceId,
                    draft.url(),
                    limited(draft.title(), 500),
                    draft.publishedAt(),
                    draft.contentHash(),
                    draft.excerpt(),
                    rawPayload,
                    classification.status(),
                    classification.category(),
                    limited(classification.productName(), 255),
                    classification.confidence()
            );
            return new PostRecord(((Number) row.get("id")).longValue(), stringValue(row.get("public_id")), true, true, null);
        }
        Map<String, Object> existing = existingRows.get(0);
        boolean contentChanged = !draft.contentHash().equals(stringValue(existing.get("content_hash")));
        if (contentChanged) {
            jdbcTemplate.update("""
                    UPDATE manufacturer_posts
                    SET title = ?,
                        published_at = ?,
                        content_hash = ?,
                        excerpt = ?,
                        raw_payload = ?::jsonb,
                        classification_status = ?,
                        detected_category = ?,
                        detected_product_name = ?,
                        confidence = ?,
                        updated_at = now()
                    WHERE id = ?
                    """,
                    limited(draft.title(), 500),
                    draft.publishedAt(),
                    draft.contentHash(),
                    draft.excerpt(),
                    rawPayload,
                    classification.status(),
                    classification.category(),
                    limited(classification.productName(), 255),
                    classification.confidence(),
                    existing.get("id")
            );
        }
        return new PostRecord(
                ((Number) existing.get("id")).longValue(),
                stringValue(existing.get("public_id")),
                false,
                contentChanged,
                longValue(existing.get("created_catalog_candidate_id"))
        );
    }

    private Classification classify(Map<String, Object> source, PostDraft draft) {
        String sourceCategory = stringValue(source.get("category_scope"));
        String text = (draft.title() + " " + draft.excerpt()).toUpperCase(Locale.ROOT);
        if (isClearlyNonProduct(text)) {
            return Classification.ignored("비제품성 게시글 키워드가 우선 감지되었습니다.");
        }
        List<CategoryScore> scores = new ArrayList<>();
        addScore(scores, "GPU", text, "RTX", "GEFORCE", "RADEON", "그래픽카드", "GRAPHICS CARD", "GPU");
        addScore(scores, "CPU", text, "RYZEN", "CORE ULTRA", "PROCESSOR", "CPU", "프로세서");
        addScore(scores, "MOTHERBOARD", text, "Z890", "B860", "X870", "B850", "MOTHERBOARD", "메인보드");
        addScore(scores, "RAM", text, "DDR5", "MEMORY KIT", "DRAM", "메모리");
        addScore(scores, "STORAGE", text, "SSD", "NVME", "PCIE 5.0", "스토리지");
        addScore(scores, "PSU", text, "PSU", "POWER SUPPLY", "ATX 3.1", "12V-2X6", "파워");
        addScore(scores, "CASE", text, "CASE", "CHASSIS", "케이스");
        addScore(scores, "COOLER", text, "COOLER", "AIO", "LIQUID FREEZER", "HEATSINK", "쿨러");
        CategoryScore best = scores.stream()
                .filter(score -> "ALL".equals(sourceCategory) || sourceCategory.equals(score.category()))
                .max((left, right) -> Integer.compare(left.score(), right.score()))
                .orElse(new CategoryScore(null, 0));
        if (best.category() == null || best.score() <= 0) {
            return Classification.pending("제품 카테고리 근거가 부족해 AI/관리자 검토가 필요합니다.");
        }
        double confidence = Math.min(0.95, 0.55 + (best.score() * 0.1));
        String productName = productName(draft.title(), stringValue(source.get("manufacturer")));
        String query = (stringValue(source.get("manufacturer")) + " " + productName).trim();
        return new Classification(
                "PRODUCT_CANDIDATE",
                best.category(),
                productName,
                confidence,
                query,
                "RULE_BASED",
                "공식 게시글 제목/요약에서 " + best.category() + " 신제품 키워드를 감지했습니다."
        );
    }

    private FetchResult fetch(String url) {
        ResponseEntity<String> response = restClient.get()
                .uri(url)
                .retrieve()
                .toEntity(String.class);
        String body = response.getBody();
        if (!StringUtils.hasText(body)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "제조사 source 응답 본문이 비어 있습니다.");
        }
        return new FetchResult(
                body,
                firstHeader(response, "ETag"),
                firstHeader(response, "Last-Modified")
        );
    }

    private List<PostDraft> extractPosts(Map<String, Object> source, String body, int limit) {
        String sourceUrl = stringValue(source.get("source_url"));
        List<PostDraft> rssPosts = extractRssPosts(sourceUrl, body, limit);
        if (!rssPosts.isEmpty()) {
            return rssPosts;
        }
        return extractHtmlLinks(sourceUrl, body, limit);
    }

    private List<PostDraft> extractRssPosts(String sourceUrl, String body, int limit) {
        List<PostDraft> posts = new ArrayList<>();
        Matcher matcher = RSS_ITEM_PATTERN.matcher(body);
        while (matcher.find() && posts.size() < limit) {
            String item = matcher.group(1);
            String title = cleanText(tagValue(item, "title"));
            String link = resolveUrl(sourceUrl, cleanText(tagValue(item, "link")));
            if (!StringUtils.hasText(title) || !StringUtils.hasText(link)) {
                continue;
            }
            String description = cleanText(tagValue(item, "description"));
            posts.add(new PostDraft(
                    link,
                    title,
                    parsePublishedAt(cleanText(tagValue(item, "pubDate"))),
                    limited(description, 1000),
                    sha256(title + "|" + link + "|" + description)
            ));
        }
        return posts;
    }

    private List<PostDraft> extractHtmlLinks(String sourceUrl, String body, int limit) {
        List<PostDraft> posts = new ArrayList<>();
        Matcher matcher = LINK_PATTERN.matcher(body);
        Set<String> seen = new java.util.LinkedHashSet<>();
        while (matcher.find() && posts.size() < limit) {
            String url = resolveUrl(sourceUrl, matcher.group(1));
            String title = cleanText(matcher.group(2));
            if (!StringUtils.hasText(url) || !StringUtils.hasText(title) || title.length() < 6 || !seen.add(url)) {
                continue;
            }
            if (!looksLikeProductPost(title)) {
                continue;
            }
            posts.add(new PostDraft(
                    url,
                    title,
                    null,
                    null,
                    sha256(title + "|" + url)
            ));
        }
        return posts;
    }

    private Map<String, Object> sourceByPublicId(String sourceId) {
        requireUuid(sourceId, "sourceId");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,
                       public_id::text AS public_id,
                       manufacturer,
                       category_scope,
                       source_type,
                       source_url,
                       enabled,
                       poll_interval_minutes,
                       last_content_hash,
                       parser_config,
                       status
                FROM manufacturer_sources
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                LIMIT 1
                """, sourceId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "제조사 source를 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    private void updateSourceScanSuccess(String sourceId, FetchResult fetch, String contentHash) {
        jdbcTemplate.update("""
                UPDATE manufacturer_sources
                SET last_checked_at = now(),
                    last_etag = ?,
                    last_modified = ?,
                    last_content_hash = ?,
                    status = 'ACTIVE',
                    error_summary = NULL,
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, fetch.etag(), fetch.lastModified(), contentHash, sourceId);
    }

    private void updateSourceScanFailure(String sourceId, String errorSummary) {
        jdbcTemplate.update("""
                UPDATE manufacturer_sources
                SET last_checked_at = now(),
                    status = 'ERROR',
                    error_summary = ?,
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, limited(errorSummary, 2000), sourceId);
    }

    private static void addScore(List<CategoryScore> scores, String category, String text, String... keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score += 1;
            }
        }
        if (score > 0) {
            scores.add(new CategoryScore(category, score));
        }
    }

    private static boolean isClearlyNonProduct(String text) {
        return text.contains("DRIVER")
                || text.contains("FIRMWARE")
                || text.contains("SECURITY ADVISORY")
                || text.contains("EVENT")
                || text.contains("GIVEAWAY")
                || text.contains("채용")
                || text.contains("이벤트");
    }

    private static boolean looksLikeProductPost(String title) {
        String upper = title.toUpperCase(Locale.ROOT);
        return upper.contains("LAUNCH")
                || upper.contains("INTRODUC")
                || upper.contains("ANNOUNC")
                || upper.contains("UNVEIL")
                || upper.contains("NEW")
                || upper.contains("RTX")
                || upper.contains("RYZEN")
                || upper.contains("CORE ULTRA")
                || upper.contains("DDR5")
                || upper.contains("SSD")
                || upper.contains("ATX 3.1")
                || upper.contains("출시")
                || upper.contains("공개")
                || upper.contains("신제품");
    }

    private static String productName(String title, String manufacturer) {
        String cleaned = cleanText(title);
        if (!StringUtils.hasText(cleaned)) {
            return manufacturer;
        }
        cleaned = cleaned
                .replaceAll("(?i)\\b(announces|introduces|launches|unveils|presents|new)\\b", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (StringUtils.hasText(manufacturer) && cleaned.toUpperCase(Locale.ROOT).startsWith(manufacturer.toUpperCase(Locale.ROOT))) {
            return cleaned;
        }
        return cleaned;
    }

    private static String resolveUrl(String sourceUrl, String href) {
        if (!StringUtils.hasText(href)) {
            return null;
        }
        try {
            return URI.create(sourceUrl).resolve(href.trim()).toString();
        } catch (IllegalArgumentException ignored) {
            return href;
        }
    }

    private static String tagValue(String item, String tag) {
        Matcher matcher = Pattern.compile(String.format(TAG_VALUE_PATTERN.pattern(), tag, tag), Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(item);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static OffsetDateTime parsePublishedAt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String firstHeader(ResponseEntity<?> response, String name) {
        List<String> values = response.getHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 hash 생성 실패", exception);
        }
    }

    private static String categoryScope(String value) {
        if (!StringUtils.hasText(value)) {
            return "ALL";
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(upper) && !CATEGORIES.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 categoryScope입니다.");
        }
        return upper;
    }

    private static String sourceType(String value) {
        String upper = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "NEWS";
        if (!SOURCE_TYPES.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 sourceType입니다.");
        }
        return upper;
    }

    private static String status(String value) {
        String upper = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "ACTIVE";
        if (!Set.of("ACTIVE", "PAUSED", "ERROR").contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 manufacturer source status입니다.");
        }
        return upper;
    }

    private static String requireText(Map<String, Object> request, String key) {
        String value = value(request, key, null);
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 값이 필요합니다.");
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

    private static String value(Map<String, Object> request, String key, String defaultValue) {
        if (request == null || !request.containsKey(key) || request.get(key) == null) {
            return defaultValue;
        }
        return String.valueOf(request.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int boundedInt(Object value, int defaultValue, int min, int max) {
        int parsed = intValue(value, defaultValue);
        return Math.min(Math.max(parsed, min), max);
    }

    private static int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
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

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String limited(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private record FetchResult(String body, String etag, String lastModified) {
    }

    private record PostDraft(String url, String title, OffsetDateTime publishedAt, String excerpt, String contentHash) {
    }

    private record PostRecord(long id, String publicId, boolean newPost, boolean contentChanged, Long createdCatalogCandidateId) {
    }

    private record CategoryScore(String category, int score) {
    }

    private record Classification(
            String status,
            String category,
            String productName,
            double confidence,
            String searchQuery,
            String method,
            String reason
    ) {
        static Classification ignored(String reason) {
            return new Classification("IGNORED", null, null, 0.0, null, "RULE_BASED", reason);
        }

        static Classification pending(String reason) {
            return new Classification("PENDING", null, null, 0.0, null, "RULE_BASED", reason);
        }

        Map<String, Object> toMap() {
            return MockData.map(
                    "status", status,
                    "category", category,
                    "productName", productName,
                    "confidence", confidence,
                    "searchQuery", searchQuery,
                    "method", method,
                    "reason", reason
            );
        }
    }
}
