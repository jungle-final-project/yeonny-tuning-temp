package com.buildgraph.prototype.part;

import com.buildgraph.prototype.agent.OpenAiResponsesClient;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final Set<String> POST_STATUSES = Set.of("PENDING", "PRODUCT_CANDIDATE", "IGNORED", "FAILED");
    private static final Map<String, List<String>> MANUFACTURER_DOMAINS = Map.ofEntries(
            Map.entry("ASUS", List.of("asus.com")),
            Map.entry("MSI", List.of("msi.com")),
            Map.entry("GIGABYTE", List.of("gigabyte.com", "aorus.com")),
            Map.entry("ASROCK", List.of("asrock.com")),
            Map.entry("ZOTAC", List.of("zotac.com")),
            Map.entry("CORSAIR", List.of("corsair.com")),
            Map.entry("COOLERMASTER", List.of("coolermaster.com")),
            Map.entry("COOLER MASTER", List.of("coolermaster.com")),
            Map.entry("LIANLI", List.of("lian-li.com")),
            Map.entry("LIAN LI", List.of("lian-li.com")),
            Map.entry("FRACTALDESIGN", List.of("fractal-design.com")),
            Map.entry("FRACTAL DESIGN", List.of("fractal-design.com")),
            Map.entry("ARCTIC", List.of("arctic.de")),
            Map.entry("NOCTUA", List.of("noctua.at")),
            Map.entry("DEEPCOOL", List.of("deepcool.com")),
            Map.entry("THERMALRIGHT", List.of("thermalright.com")),
            Map.entry("BE QUIET!", List.of("bequiet.com")),
            Map.entry("SAMSUNG", List.of("samsung.com", "semiconductor.samsung.com")),
            Map.entry("SK HYNIX", List.of("skhynix.com")),
            Map.entry("WESTERN DIGITAL", List.of("westerndigital.com")),
            Map.entry("WD", List.of("westerndigital.com")),
            Map.entry("SEAGATE", List.of("seagate.com")),
            Map.entry("CRUCIAL", List.of("crucial.com")),
            Map.entry("MICRON", List.of("micron.com", "crucial.com")),
            Map.entry("INTEL", List.of("intel.com")),
            Map.entry("AMD", List.of("amd.com")),
            Map.entry("NVIDIA", List.of("nvidia.com"))
    );
    private static final Pattern RSS_ITEM_PATTERN = Pattern.compile("<item\\b[^>]*>(.*?)</item>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_VALUE_PATTERN = Pattern.compile("<%s\\b[^>]*>(.*?)</%s>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_PATTERN = Pattern.compile("<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DIMENSIONS_PATTERN = Pattern.compile("\\bDIMENSIONS?\\s+(\\d+(?:\\.\\d+)?)\\s*MM\\s*[X×]\\s*(\\d+(?:\\.\\d+)?)\\s*MM\\s*[X×]\\s*(\\d+(?:\\.\\d+)?)\\s*MM", Pattern.CASE_INSENSITIVE);
    private static final Pattern GPU_CLEARANCE_PATTERN = Pattern.compile("\\bGPU\\s+LENGTH\\s+CLEARANCE\\s+(\\d+(?:\\.\\d+)?)\\s*MM", Pattern.CASE_INSENSITIVE);
    private static final Pattern CPU_COOLER_CLEARANCE_PATTERN = Pattern.compile("\\bCPU\\s+COOLER\\s+HEIGHT\\s+CLEARANCE\\s+(\\d+(?:\\.\\d+)?)\\s*MM", Pattern.CASE_INSENSITIVE);
    private static final Pattern PSU_SUPPORT_PATTERN = Pattern.compile("\\bPSU\\s+SUPPORT\\s+[^\\n\\r]{0,80}?(\\d+(?:\\.\\d+)?)\\s*MM", Pattern.CASE_INSENSITIVE);
    private static final Pattern MM_VALUE_PATTERN = Pattern.compile("(\\d{3,4})\\s*MM", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;
    private final NaverShoppingOfferService naverShoppingOfferService;
    private final OpenAiResponsesClient openAiResponsesClient;
    private final RestClient restClient;

    public ManufacturerReleaseIntakeService(
            JdbcTemplate jdbcTemplate,
            NaverShoppingOfferService naverShoppingOfferService,
            OpenAiResponsesClient openAiResponsesClient,
            @Value("${part.manufacturer-release-intake.user-agent:BuildGraphBot/0.1}") String userAgent
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.naverShoppingOfferService = naverShoppingOfferService;
        this.openAiResponsesClient = openAiResponsesClient;
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    public Map<String, Object> listSources(Boolean enabled) {
        return listSources(enabled, null, null, false);
    }

    public Map<String, Object> listSources(Boolean enabled, String status, String category, Boolean includeDeleted) {
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
                       parser_config AS "parserConfig",
                       status,
                       error_summary AS "errorSummary",
                       created_at AS "createdAt",
                       updated_at AS "updatedAt",
                       deleted_at AS "deletedAt"
                FROM manufacturer_sources
                WHERE 1 = 1
                """);
        if (!Boolean.TRUE.equals(includeDeleted)) {
            sql.append(" AND deleted_at IS NULL");
        }
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            params.add(enabled);
        }
        if (StringUtils.hasText(status)) {
            sql.append(" AND status = ?");
            params.add(status(status));
        }
        if (StringUtils.hasText(category)) {
            sql.append(" AND category_scope = ?");
            params.add(categoryScope(category));
        }
        sql.append(" ORDER BY manufacturer, source_url");
        return MockData.map("items", jdbcTemplate.queryForList(sql.toString(), params.toArray()));
    }

    public Map<String, Object> getSource(String sourceId, Boolean includeDeleted) {
        return sourceMap(sourceByPublicId(sourceId, Boolean.TRUE.equals(includeDeleted)));
    }

    public Map<String, Object> createSource(Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        String manufacturer = requireText(request, "manufacturer");
        String categoryScope = categoryScope(value(request, "categoryScope", "ALL"));
        String sourceType = sourceType(value(request, "sourceType", "NEWS"));
        String sourceUrl = requireText(request, "sourceUrl");
        boolean enabled = booleanValue(request.get("enabled"), true);
        int pollInterval = boundedInt(request.get("pollIntervalMinutes"), 1440, 30, 10080);
        String status = status(value(request, "status", "ACTIVE"));
        Map<String, Object> parserConfig = mapValue(request.get("parserConfig"));
        validateOfficialManufacturerSource(manufacturer, sourceUrl, parserConfig);

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
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, now(), now())
                ON CONFLICT (source_url) WHERE deleted_at IS NULL
                DO UPDATE SET
                  manufacturer = EXCLUDED.manufacturer,
                  category_scope = EXCLUDED.category_scope,
                  source_type = EXCLUDED.source_type,
                  enabled = EXCLUDED.enabled,
                  poll_interval_minutes = EXCLUDED.poll_interval_minutes,
                  parser_config = EXCLUDED.parser_config,
                  status = EXCLUDED.status,
                  error_summary = NULL,
                  updated_at = now()
                RETURNING public_id::text AS id,
                          manufacturer,
                          category_scope AS "categoryScope",
                          source_type AS "sourceType",
                          source_url AS "sourceUrl",
                          enabled,
                          poll_interval_minutes AS "pollIntervalMinutes",
                          parser_config AS "parserConfig",
                          status,
                          created_at AS "createdAt",
                          updated_at AS "updatedAt",
                          deleted_at AS "deletedAt"
                """,
                manufacturer,
                categoryScope,
                sourceType,
                sourceUrl,
                enabled,
                pollInterval,
                json(parserConfig),
                status
        );
        audit(admin, "MANUFACTURER_SOURCE_CREATED", "manufacturer_sources", stringValue(row.get("id")), MockData.map(
                "manufacturer", manufacturer,
                "categoryScope", categoryScope,
                "sourceType", sourceType,
                "sourceUrl", sourceUrl,
                "enabled", enabled,
                "status", status
        ));
        return row;
    }

    public Map<String, Object> createSource(Map<String, Object> request) {
        return createSource(request, null);
    }

    public Map<String, Object> updateSource(String sourceId, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> existing = sourceByPublicId(sourceId);
        String manufacturer = value(request, "manufacturer", stringValue(existing.get("manufacturer")));
        String categoryScope = categoryScope(value(request, "categoryScope", stringValue(existing.get("category_scope"))));
        String sourceType = sourceType(value(request, "sourceType", stringValue(existing.get("source_type"))));
        String sourceUrl = value(request, "sourceUrl", stringValue(existing.get("source_url")));
        boolean enabled = booleanValue(request.get("enabled"), Boolean.TRUE.equals(existing.get("enabled")));
        int pollInterval = boundedInt(request.get("pollIntervalMinutes"), intValue(existing.get("poll_interval_minutes"), 1440), 30, 10080);
        String status = status(value(request, "status", stringValue(existing.get("status"))));
        Object parserConfig = request.containsKey("parserConfig") ? mapValue(request.get("parserConfig")) : existing.get("parser_config");
        validateOfficialManufacturerSource(manufacturer, sourceUrl, parserConfig);

        Map<String, Object> updated = jdbcTemplate.queryForMap("""
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
                          parser_config AS "parserConfig",
                          status,
                          error_summary AS "errorSummary",
                          created_at AS "createdAt",
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
        audit(admin, "MANUFACTURER_SOURCE_UPDATED", "manufacturer_sources", sourceId, MockData.map(
                "manufacturer", manufacturer,
                "categoryScope", categoryScope,
                "sourceType", sourceType,
                "sourceUrl", sourceUrl,
                "enabled", enabled,
                "status", status
        ));
        if (!status.equals(stringValue(existing.get("status")))) {
            audit(admin, "MANUFACTURER_SOURCE_STATUS_CHANGED", "manufacturer_sources", sourceId, MockData.map(
                    "before", existing.get("status"),
                    "after", status
            ));
        }
        return updated;
    }

    public Map<String, Object> updateSource(String sourceId, Map<String, Object> request) {
        return updateSource(sourceId, request, null);
    }

    public Map<String, Object> softDeleteSource(String sourceId, CurrentUserService.CurrentUser admin) {
        Map<String, Object> existing = sourceByPublicId(sourceId);
        jdbcTemplate.update("""
                UPDATE manufacturer_sources
                SET deleted_at = now(),
                    enabled = false,
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """, sourceId);
        audit(admin, "MANUFACTURER_SOURCE_SOFT_DELETED", "manufacturer_sources", sourceId, MockData.map("sourceUrl", existing.get("source_url")));
        return MockData.map("id", sourceId, "deleted", true);
    }

    public Map<String, Object> restoreSource(String sourceId, CurrentUserService.CurrentUser admin) {
        sourceByPublicId(sourceId, true);
        jdbcTemplate.update("""
                UPDATE manufacturer_sources
                SET deleted_at = NULL,
                    enabled = false,
                    status = 'PAUSED',
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, sourceId);
        audit(admin, "MANUFACTURER_SOURCE_RESTORED", "manufacturer_sources", sourceId, MockData.map("enabled", false, "status", "PAUSED"));
        return getSource(sourceId, false);
    }

    public Map<String, Object> scanAll(Integer limitPerSource, Boolean createCandidates) {
        int safeLimit = boundedInt(limitPerSource, 20, 1, 100);
        List<Map<String, Object>> sources = jdbcTemplate.queryForList("""
                SELECT public_id::text AS public_id
                FROM manufacturer_sources
                WHERE enabled = true
                  AND status <> 'PAUSED'
                  AND deleted_at IS NULL
                ORDER BY coalesce(last_checked_at, '1970-01-01'::timestamptz), manufacturer
                """);
        int scanned = 0;
        int newPosts = 0;
        int candidates = 0;
        int failed = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            Map<String, Object> result = scanSource(stringValue(source.get("public_id")), safeLimit, createCandidates);
            results.add(result);
            scanned += 1;
            newPosts += intValue(result.get("newPosts"), 0);
            candidates += intValue(result.get("createdCandidates"), 0);
            if (Boolean.TRUE.equals(result.get("failed"))) {
                failed += 1;
            }
        }
        return MockData.map(
                "scannedSources", scanned,
                "newPosts", newPosts,
                "createdCandidates", candidates,
                "failedSources", failed,
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
                    "failed", false,
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
            String errorSummary = exception.getMessage();
            updateSourceScanFailure(sourceId, errorSummary);
            return MockData.map(
                    "sourceId", sourceId,
                    "manufacturer", source.get("manufacturer"),
                    "failed", true,
                    "unchanged", false,
                    "parsedPosts", 0,
                    "newPosts", 0,
                    "updatedPosts", 0,
                    "ignoredPosts", 0,
                    "productPosts", 0,
                    "createdCandidates", 0,
                    "errorSummary", limited(errorSummary, 500),
                    "posts", List.of()
            );
        }
    }

    public Map<String, Object> listPosts(String status, String category, Integer page, Integer size) {
        return listPosts(status, category, page, size, false);
    }

    public Map<String, Object> listPosts(String status, String category, Integer page, Integer size, Boolean includeDeleted) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safeSize = Math.min(Math.max(size == null ? 20 : size, 1), 100);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (!Boolean.TRUE.equals(includeDeleted)) {
            where.append(" AND p.deleted_at IS NULL");
        }
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
                       p.updated_at AS "updatedAt",
                       p.deleted_at AS "deletedAt"
                FROM manufacturer_posts p
                JOIN manufacturer_sources s ON s.id = p.manufacturer_source_id
                LEFT JOIN part_catalog_candidates c ON c.id = p.created_catalog_candidate_id
                """ + where + "\n" + """
                ORDER BY p.created_at DESC
                LIMIT ? OFFSET ?
                """, params.toArray());
        return MockData.map("items", items, "page", safePage, "size", safeSize, "total", total);
    }

    public Map<String, Object> getPost(String postId, Boolean includeDeleted) {
        return postMap(postByPublicId(postId, Boolean.TRUE.equals(includeDeleted)));
    }

    public Map<String, Object> createPost(Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        String sourceId = requireText(request, "sourceId");
        Map<String, Object> source = sourceByPublicId(sourceId);
        String externalUrl = requireText(request, "externalUrl");
        String title = requireText(request, "title");
        String excerpt = value(request, "excerpt", null);
        String status = postStatus(value(request, "classificationStatus", "PENDING"));
        String category = optionalCategory(value(request, "detectedCategory", null));
        String productName = value(request, "detectedProductName", null);
        Double confidence = confidenceValue(request.get("confidence"));
        OffsetDateTime publishedAt = parsePublishedAt(value(request, "publishedAt", null));
        String contentHash = sha256(title + "|" + externalUrl + "|" + (excerpt == null ? "" : excerpt));
        String rawPayload = json(MockData.map(
                "manualPost", true,
                "createdBy", admin == null ? null : admin.email(),
                "sourceUrl", source.get("source_url")
        ));
        try {
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
                    RETURNING public_id::text AS id
                    """,
                    source.get("id"),
                    externalUrl,
                    limited(title, 500),
                    publishedAt,
                    contentHash,
                    excerpt,
                    rawPayload,
                    status,
                    category,
                    limited(productName, 255),
                    confidence
            );
            audit(admin, "MANUFACTURER_POST_CREATED", "manufacturer_posts", stringValue(row.get("id")), MockData.map(
                    "sourceId", sourceId,
                    "externalUrl", externalUrl,
                    "classificationStatus", status
            ));
            return getPost(stringValue(row.get("id")), false);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 제조사 게시글 URL입니다.");
        }
    }

    public Map<String, Object> updatePost(String postId, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> existing = postByPublicId(postId, false);
        String externalUrl = value(request, "externalUrl", stringValue(existing.get("external_url")));
        String title = value(request, "title", stringValue(existing.get("title")));
        String excerpt = value(request, "excerpt", stringValue(existing.get("excerpt")));
        String status = postStatus(value(request, "classificationStatus", stringValue(existing.get("classification_status"))));
        String category = optionalCategory(value(request, "detectedCategory", stringValue(existing.get("detected_category"))));
        String productName = value(request, "detectedProductName", stringValue(existing.get("detected_product_name")));
        Double confidence = request.containsKey("confidence")
                ? confidenceValue(request.get("confidence"))
                : doubleValue(existing.get("confidence"));
        OffsetDateTime publishedAt = request.containsKey("publishedAt")
                ? parsePublishedAt(value(request, "publishedAt", null))
                : offsetDateTimeValue(existing.get("published_at"));
        String contentHash = sha256(title + "|" + externalUrl + "|" + (excerpt == null ? "" : excerpt));
        try {
            jdbcTemplate.update("""
                    UPDATE manufacturer_posts
                    SET external_url = ?,
                        title = ?,
                        published_at = ?,
                        content_hash = ?,
                        excerpt = ?,
                        classification_status = ?,
                        detected_category = ?,
                        detected_product_name = ?,
                        confidence = ?,
                        raw_payload = coalesce(raw_payload, '{}'::jsonb) || ?::jsonb,
                        updated_at = now()
                    WHERE public_id = ?::uuid
                      AND deleted_at IS NULL
                    """,
                    externalUrl,
                    limited(title, 500),
                    publishedAt,
                    contentHash,
                    excerpt,
                    status,
                    category,
                    limited(productName, 255),
                    confidence,
                    json(MockData.map(
                            "adminReview", MockData.map(
                                    "updatedBy", admin == null ? null : admin.email(),
                                    "updatedAt", MockData.now()
                            )
                    )),
                    postId
            );
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 제조사 게시글 URL입니다.");
        }
        audit(admin, "MANUFACTURER_POST_UPDATED", "manufacturer_posts", postId, MockData.map(
                "classificationStatus", status,
                "detectedCategory", category,
                "detectedProductName", productName
        ));
        return getPost(postId, false);
    }

    public Map<String, Object> softDeletePost(String postId, CurrentUserService.CurrentUser admin) {
        postByPublicId(postId, false);
        jdbcTemplate.update("""
                UPDATE manufacturer_posts
                SET deleted_at = now(),
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """, postId);
        audit(admin, "MANUFACTURER_POST_SOFT_DELETED", "manufacturer_posts", postId, Map.of());
        return MockData.map("id", postId, "deleted", true);
    }

    public Map<String, Object> restorePost(String postId, CurrentUserService.CurrentUser admin) {
        postByPublicId(postId, true);
        jdbcTemplate.update("""
                UPDATE manufacturer_posts
                SET deleted_at = NULL,
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, postId);
        audit(admin, "MANUFACTURER_POST_RESTORED", "manufacturer_posts", postId, Map.of());
        return getPost(postId, false);
    }

    public Map<String, Object> createCandidateForPost(String postId, CurrentUserService.CurrentUser admin) {
        Map<String, Object> post = postByPublicId(postId, false);
        String status = stringValue(post.get("classification_status"));
        String category = stringValue(post.get("detected_category"));
        String productName = stringValue(post.get("detected_product_name"));
        if (!"PRODUCT_CANDIDATE".equals(status) || !StringUtils.hasText(category) || !StringUtils.hasText(productName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PRODUCT_CANDIDATE 상태와 detectedCategory/detectedProductName이 필요합니다.");
        }
        if (post.get("created_catalog_candidate_id") != null) {
            return MockData.map(
                    "created", false,
                    "postId", postId,
                    "candidateId", post.get("catalog_candidate_public_id"),
                    "message", "이미 후보가 연결된 게시글입니다."
            );
        }
        String manufacturer = stringValue(post.get("manufacturer"));
        String searchQuery = (manufacturer + " " + productName).trim();
        long jobId = createCatalogJob(category, searchQuery);
        Map<String, Object> releaseContext = MockData.map(
                "manufacturerSourceId", post.get("manufacturer_source_public_id"),
                "manufacturerPostId", postId,
                "officialUrl", post.get("external_url"),
                "officialTitle", post.get("title"),
                "detectedProductName", productName,
                "classificationMethod", "ADMIN_REVIEW",
                "confidence", post.get("confidence")
        );
        Map<String, Object> aiRaw = aiRawFromPost(post);
        if (!aiRaw.isEmpty()) {
            releaseContext.put("classificationMethod", "OPENAI_STRUCTURED_OUTPUT");
            releaseContext.put("aiSpecAttributes", mapValue(aiRaw.get("specAttributes")));
            releaseContext.put("aiSpecReason", stringValue(aiRaw.get("specReason")));
            releaseContext.put("missingSpecFields", aiRaw.get("missingSpecFields"));
        }
        Map<String, Object> result = naverShoppingOfferService.createManufacturerReleaseCandidate(jobId, category, searchQuery, releaseContext);
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
                    WHERE public_id = ?::uuid
                    """,
                    candidateId,
                    json(MockData.map("naverCandidate", result)),
                    postId
            );
        }
        audit(admin, "MANUFACTURER_POST_CANDIDATE_CREATED", "manufacturer_posts", postId, MockData.map(
                "created", result.get("created"),
                "candidateId", result.get("candidateId")
        ));
        return result;
    }

    public Map<String, Object> createAiAssetDraftForPost(String postId, CurrentUserService.CurrentUser admin) {
        Map<String, Object> post = postByPublicId(postId, false);
        String candidateId = stringValue(post.get("catalog_candidate_public_id"));
        List<String> messages = new ArrayList<>();
        AiClassification classification = classifyPostWithAi(post);
        updatePostWithAiClassification(postId, classification, admin);
        messages.add("AI가 제조사 게시글을 신제품 후보와 스펙 초안으로 구조화했습니다.");

        if (!classification.productCandidate()) {
            return MockData.map(
                    "postId", postId,
                    "aiUsed", true,
                    "classificationStatus", "IGNORED",
                    "detectedCategory", null,
                    "detectedProductName", null,
                    "confidence", classification.confidence(),
                    "candidateId", null,
                    "candidateStatus", null,
                    "partId", null,
                    "partStatus", null,
                    "messages", messages
            );
        }

        if (!StringUtils.hasText(candidateId)) {
            Map<String, Object> candidateResult = createCandidateForPost(postId, admin);
            candidateId = stringValue(candidateResult.get("candidateId"));
            if (!Boolean.TRUE.equals(candidateResult.get("created")) && !StringUtils.hasText(candidateId)) {
                messages.add(stringValue(candidateResult.get("message")));
                return MockData.map(
                        "postId", postId,
                        "aiUsed", true,
                        "classificationStatus", "PRODUCT_CANDIDATE",
                        "detectedCategory", classification.category(),
                        "detectedProductName", classification.productName(),
                        "confidence", classification.confidence(),
                        "candidateId", null,
                        "candidateStatus", null,
                        "partId", null,
                        "partStatus", null,
                        "messages", messages
                );
            }
            messages.add("네이버 쇼핑 검색 후보를 생성했습니다.");
        } else {
            syncCandidateReleaseContext(candidateId, postId, classification, admin);
            messages.add("이미 연결된 후보에 AI 스펙 초안을 다시 반영했습니다.");
        }

        Map<String, Object> approval = naverShoppingOfferService.approveCatalogCandidateAsInactive(candidateId, admin);
        messages.add("후보를 INACTIVE 내부 자산 초안으로 연결했습니다.");
        Map<String, Object> refreshedPost = postByPublicId(postId, false);
        return MockData.map(
                "postId", postId,
                "aiUsed", true,
                "classificationStatus", refreshedPost.get("classification_status"),
                "detectedCategory", refreshedPost.get("detected_category"),
                "detectedProductName", refreshedPost.get("detected_product_name"),
                "confidence", refreshedPost.get("confidence"),
                "candidateId", approval.get("candidateId"),
                "candidateStatus", approval.get("status"),
                "partId", approval.get("publishedPartId"),
                "partStatus", approval.get("partStatus"),
                "messages", messages
        );
    }

    public Map<String, Object> listCatalogCandidates(String status, String category, String source, Integer page, Integer size) {
        return listCatalogCandidates(status, category, source, page, size, false);
    }

    public Map<String, Object> listCatalogCandidates(String status, String category, String source, Integer page, Integer size, Boolean includeDeleted) {
        int safePage = Math.max(page == null ? 0 : page, 0);
        int safeSize = Math.min(Math.max(size == null ? 20 : size, 1), 100);
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (!Boolean.TRUE.equals(includeDeleted)) {
            where.append(" AND c.deleted_at IS NULL");
        }
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
                       c.last_seen_at AS "lastSeenAt",
                       c.updated_at AS "updatedAt",
                       c.deleted_at AS "deletedAt"
                FROM part_catalog_candidates c
                LEFT JOIN parts p ON p.id = c.published_part_id
                """ + where + "\n" + """
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
        String configuredSearchQuery = parserConfigValue(source.get("parser_config"), "searchQuery");
        String query = StringUtils.hasText(configuredSearchQuery)
                ? configuredSearchQuery.trim()
                : (stringValue(source.get("manufacturer")) + " " + productName).trim();
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

    private AiClassification classifyPostWithAi(Map<String, Object> post) {
        String systemPrompt = """
                You classify official PC hardware manufacturer release posts for BuildGraph.
                Return JSON only. Do not invent product specs, prices, URLs, or unavailable facts.
                Extract product specification attributes only when they appear in the provided official post text.
                Use camelCase keys matching BuildGraph part attributes. Put unknown values as null.
                If the post is not a newly announced PC hardware product, set productCandidate=false.
                Categories are CPU, GPU, RAM, MOTHERBOARD, STORAGE, PSU, CASE, COOLER.
                """;
        String officialText = officialPostText(post);
        String userPrompt = OBJECT_MAPPER.valueToTree(MockData.map(
                "manufacturer", post.get("manufacturer"),
                "sourceUrl", post.get("source_url"),
                "postUrl", post.get("external_url"),
                "title", post.get("title"),
                "excerpt", post.get("excerpt"),
                "officialPostText", officialText
        )).toPrettyString();
        String output = openAiResponsesClient.createStructuredJson(
                systemPrompt,
                userPrompt,
                "manufacturer_release_asset_draft",
                manufacturerReleaseAiSchema()
        );
        Map<String, Object> parsed;
        try {
            parsed = OBJECT_MAPPER.readValue(output, new TypeReference<>() {
            });
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 신제품 분류 JSON을 해석할 수 없습니다.", exception);
        }
        boolean productCandidate = booleanValue(parsed.get("productCandidate"), false);
        String category = productCandidate ? optionalCategory(stringValue(parsed.get("category"))) : null;
        String productName = productCandidate ? requireAiText(parsed, "productName") : null;
        double confidence = Math.max(0.0, Math.min(1.0, doubleValue(parsed.get("confidence")) == null ? 0.0 : doubleValue(parsed.get("confidence"))));
        String searchQuery = productCandidate ? requireAiText(parsed, "searchQuery") : null;
        String reason = value(parsed, "reason", null);
        if (productCandidate && !StringUtils.hasText(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 신제품 분류에 category가 없습니다.");
        }
        if (productCandidate) {
            mergeOfficialSpecExtraction(parsed, category, officialText);
        }
        return new AiClassification(productCandidate, category, productName, confidence, searchQuery, reason, parsed);
    }

    private static void mergeOfficialSpecExtraction(Map<String, Object> parsed, String category, String officialText) {
        Map<String, Object> officialSpecs = extractOfficialSpecAttributes(category, officialText);
        if (officialSpecs.isEmpty()) {
            return;
        }
        Map<String, Object> specs = new LinkedHashMap<>(mapValue(parsed.get("specAttributes")));
        for (Map.Entry<String, Object> entry : officialSpecs.entrySet()) {
            Object current = specs.get(entry.getKey());
            if (!hasSpecValue(current)) {
                specs.put(entry.getKey(), entry.getValue());
            }
        }
        parsed.put("specAttributes", specs);
        parsed.put("specExtractionMethod", "LLM_WITH_OFFICIAL_SPEC_PATTERN_EXTRACTOR");
        parsed.put("specReason", appendReason(stringValue(parsed.get("specReason")), "공식 페이지 표 라벨에서 누락 스펙을 보강했습니다."));
        parsed.put("missingSpecFields", remainingMissingFields(category, specs));
    }

    private static Map<String, Object> extractOfficialSpecAttributes(String category, String officialText) {
        if (!"CASE".equals(category) || !StringUtils.hasText(officialText)) {
            return Map.of();
        }
        String text = officialText.replace('\n', ' ').replaceAll("\\s+", " ");
        String upper = text.toUpperCase(Locale.ROOT);
        Map<String, Object> specs = new LinkedHashMap<>();
        Matcher dimensions = DIMENSIONS_PATTERN.matcher(text);
        if (dimensions.find()) {
            specs.put("depthMm", roundedMillimeter(dimensions.group(1)));
            specs.put("widthMm", roundedMillimeter(dimensions.group(2)));
            specs.put("heightMm", roundedMillimeter(dimensions.group(3)));
        }
        putNumberIfFound(specs, "maxGpuLengthMm", GPU_CLEARANCE_PATTERN, text);
        putNumberIfFound(specs, "maxCpuCoolerHeightMm", CPU_COOLER_CLEARANCE_PATTERN, text);
        putNumberIfFound(specs, "maxPsuLengthMm", PSU_SUPPORT_PATTERN, text);
        List<Integer> radiatorSizes = radiatorSizes(text);
        if (!radiatorSizes.isEmpty()) {
            specs.put("radiatorSupportMm", radiatorSizes);
        }
        if (upper.contains("MOTHERBOARD SUPPORT")) {
            if (upper.contains("E-ATX") || upper.contains("EATX")) {
                specs.put("formFactor", "EATX_ATX_MATX_ITX");
            } else if (upper.contains("ATX")) {
                specs.put("formFactor", "ATX_MATX_ITX");
            } else if (upper.contains("M-ATX") || upper.contains("MICRO-ATX") || upper.contains("MATX")) {
                specs.put("formFactor", "MATX_ITX");
            } else if (upper.contains("MINI-ITX") || upper.contains("ITX")) {
                specs.put("formFactor", "ITX");
            }
        }
        return specs;
    }

    private static void putNumberIfFound(Map<String, Object> specs, String key, Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            specs.put(key, roundedMillimeter(matcher.group(1)));
        }
    }

    private static int roundedMillimeter(String value) {
        return (int) Math.round(Double.parseDouble(value));
    }

    private static List<Integer> radiatorSizes(String text) {
        int index = text.toUpperCase(Locale.ROOT).indexOf("RADIATOR SUPPORT");
        if (index < 0) {
            return List.of();
        }
        String section = text.substring(index, Math.min(text.length(), index + 220));
        Matcher matcher = MM_VALUE_PATTERN.matcher(section);
        List<Integer> sizes = new ArrayList<>();
        while (matcher.find()) {
            int size = Integer.parseInt(matcher.group(1));
            if (size >= 120 && size <= 560 && !sizes.contains(size)) {
                sizes.add(size);
            }
        }
        return sizes;
    }

    private static boolean hasSpecValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text);
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private static String appendReason(String existing, String addition) {
        if (!StringUtils.hasText(existing)) {
            return addition;
        }
        return existing + " " + addition;
    }

    private static List<String> remainingMissingFields(String category, Map<String, Object> specs) {
        List<String> required = switch (category) {
            case "CPU" -> List.of("socket", "coreCount", "threadCount", "tdpW");
            case "GPU" -> List.of("vramGb", "lengthMm", "wattage", "requiredSystemPowerW", "powerConnector");
            case "MOTHERBOARD" -> List.of("socket", "chipset", "memoryType", "formFactor");
            case "RAM" -> List.of("memoryType", "capacityGb", "moduleCount", "speedMhz", "formFactor");
            case "STORAGE" -> List.of("capacityGb", "interface", "generation", "formFactor");
            case "PSU" -> List.of("capacityW", "efficiency", "atxSpec", "gpuConnector");
            case "CASE" -> List.of("formFactor", "maxGpuLengthMm", "maxCpuCoolerHeightMm", "maxPsuLengthMm");
            case "COOLER" -> List.of("coolerType", "socketSupport", "tdpW");
            default -> List.of();
        };
        return required.stream().filter(field -> !hasSpecValue(specs.get(field))).toList();
    }

    private void updatePostWithAiClassification(String postId, AiClassification classification, CurrentUserService.CurrentUser admin) {
        String status = classification.productCandidate() ? "PRODUCT_CANDIDATE" : "IGNORED";
        jdbcTemplate.update("""
                UPDATE manufacturer_posts
                SET classification_status = ?,
                    detected_category = ?,
                    detected_product_name = ?,
                    confidence = ?,
                    raw_payload = coalesce(raw_payload, '{}'::jsonb) || ?::jsonb,
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """,
                status,
                classification.category(),
                limited(classification.productName(), 255),
                classification.confidence(),
                json(MockData.map(
                        "aiAssetDraft", MockData.map(
                                "method", "OPENAI_STRUCTURED_OUTPUT",
                                "searchQuery", classification.searchQuery(),
                                "reason", classification.reason(),
                                "raw", classification.raw()
                        )
                )),
                postId
        );
        audit(admin, "MANUFACTURER_POST_AI_ASSET_CLASSIFIED", "manufacturer_posts", postId, MockData.map(
                "classificationStatus", status,
                "detectedCategory", classification.category(),
                "detectedProductName", classification.productName(),
                "confidence", classification.confidence(),
                "searchQuery", classification.searchQuery()
        ));
    }

    private void syncCandidateReleaseContext(String candidateId, String postId, AiClassification classification, CurrentUserService.CurrentUser admin) {
        Map<String, Object> post = postByPublicId(postId, false);
        jdbcTemplate.update("""
                UPDATE part_catalog_candidates
                SET raw_payload = coalesce(raw_payload, '{}'::jsonb) || ?::jsonb,
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """,
                json(MockData.map("manufacturerRelease", releaseContextForCandidate(post, classification))),
                candidateId
        );
        audit(admin, "PART_CATALOG_CANDIDATE_AI_SPEC_SYNCED", "part_catalog_candidates", candidateId, MockData.map(
                "postId", postId,
                "detectedCategory", classification.category(),
                "detectedProductName", classification.productName()
        ));
    }

    private Map<String, Object> releaseContextForCandidate(Map<String, Object> post, AiClassification classification) {
        Map<String, Object> raw = classification.raw() == null ? Map.of() : classification.raw();
        return MockData.map(
                "manufacturerSourceId", post.get("manufacturer_source_public_id"),
                "manufacturerPostId", post.get("public_id"),
                "officialUrl", post.get("external_url"),
                "officialTitle", post.get("title"),
                "detectedProductName", classification.productName(),
                "classificationMethod", "OPENAI_STRUCTURED_OUTPUT",
                "confidence", classification.confidence(),
                "aiSpecAttributes", mapValue(raw.get("specAttributes")),
                "aiSpecReason", stringValue(raw.get("specReason")),
                "missingSpecFields", raw.get("missingSpecFields")
        );
    }

    private static Map<String, Object> aiRawFromPost(Map<String, Object> post) {
        Map<String, Object> rawPayload = jsonMap(post.get("raw_payload"));
        Map<String, Object> aiAssetDraft = mapValue(rawPayload.get("aiAssetDraft"));
        return mapValue(aiAssetDraft.get("raw"));
    }

    private String officialPostText(Map<String, Object> post) {
        String postUrl = stringValue(post.get("external_url"));
        if (!StringUtils.hasText(postUrl)) {
            return null;
        }
        try {
            String body = fetch(postUrl).body();
            return limited(cleanText(body), 60000);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, Object> manufacturerReleaseAiSchema() {
        List<String> specKeys = List.of(
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
        Map<String, Object> specProperties = new LinkedHashMap<>();
        for (String key : specKeys) {
            if (List.of("radiatorSupportMm", "socketSupport").contains(key)) {
                specProperties.put(key, MockData.map(
                        "type", List.of("array", "null"),
                        "items", MockData.map("type", List.of("string", "number", "integer"))
                ));
            } else if (List.of("modular").contains(key)) {
                specProperties.put(key, MockData.map("type", List.of("boolean", "null")));
            } else if (List.of("coreCount", "threadCount", "tdpW", "vramGb", "lengthMm", "widthMm", "heightMm", "depthMm", "wattage",
                    "requiredSystemPowerW", "capacityGb", "moduleCount", "speedMhz", "readMbps", "writeMbps", "capacityW",
                    "maxGpuLengthMm", "maxCpuCoolerHeightMm", "maxPsuLengthMm", "radiatorLengthMm", "radiatorWidthMm",
                    "radiatorThicknessMm", "coolerHeightMm").contains(key)) {
                specProperties.put(key, MockData.map("type", List.of("number", "integer", "null")));
            } else {
                specProperties.put(key, MockData.map("type", List.of("string", "null")));
            }
        }
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("productCandidate", "category", "productName", "confidence", "searchQuery", "reason", "specAttributes", "specReason", "missingSpecFields"),
                "properties", MockData.map(
                        "productCandidate", MockData.map("type", "boolean"),
                        "category", MockData.map("type", List.of("string", "null"), "enum", Arrays.asList("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER", null)),
                        "productName", MockData.map("type", List.of("string", "null")),
                        "confidence", MockData.map("type", "number", "minimum", 0, "maximum", 1),
                        "searchQuery", MockData.map("type", List.of("string", "null")),
                        "reason", MockData.map("type", "string"),
                        "specAttributes", MockData.map(
                                "type", "object",
                                "additionalProperties", false,
                                "required", specKeys,
                                "properties", specProperties
                        ),
                        "specReason", MockData.map("type", "string"),
                        "missingSpecFields", MockData.map("type", "array", "items", MockData.map("type", "string"))
                )
        );
    }

    private static String requireAiText(Map<String, Object> parsed, String key) {
        String value = stringValue(parsed.get(key));
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 신제품 분류에 " + key + " 값이 없습니다.");
        }
        return value.trim();
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
        return sourceByPublicId(sourceId, false);
    }

    private Map<String, Object> sourceByPublicId(String sourceId, boolean includeDeleted) {
        requireUuid(sourceId, "sourceId");
        String deletedClause = includeDeleted ? "" : " AND deleted_at IS NULL\n";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,
                       public_id::text AS public_id,
                       manufacturer,
                       category_scope,
                       source_type,
                       source_url,
                       enabled,
                       poll_interval_minutes,
                       last_checked_at,
                       last_content_hash,
                       parser_config,
                       status,
                       error_summary,
                       created_at,
                       updated_at,
                       deleted_at
                FROM manufacturer_sources
                WHERE public_id = ?::uuid
                """ + deletedClause + """
                LIMIT 1
                """, sourceId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "제조사 source를 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    private Map<String, Object> sourceMap(Map<String, Object> row) {
        return MockData.map(
                "id", row.get("public_id"),
                "manufacturer", row.get("manufacturer"),
                "categoryScope", row.get("category_scope"),
                "sourceType", row.get("source_type"),
                "sourceUrl", row.get("source_url"),
                "enabled", row.get("enabled"),
                "pollIntervalMinutes", row.get("poll_interval_minutes"),
                "lastCheckedAt", row.get("last_checked_at"),
                "parserConfig", row.get("parser_config"),
                "status", row.get("status"),
                "errorSummary", row.get("error_summary"),
                "createdAt", row.get("created_at"),
                "updatedAt", row.get("updated_at"),
                "deletedAt", row.get("deleted_at")
        );
    }

    private Map<String, Object> postByPublicId(String postId, boolean includeDeleted) {
        requireUuid(postId, "postId");
        String deletedClause = includeDeleted ? "" : " AND p.deleted_at IS NULL\n";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT p.id,
                       p.public_id::text AS public_id,
                       p.manufacturer_source_id,
                       s.public_id::text AS manufacturer_source_public_id,
                       s.manufacturer,
                       s.source_url,
                       p.created_catalog_candidate_id,
                       c.public_id::text AS catalog_candidate_public_id,
                       p.external_url,
                       p.title,
                       p.published_at,
                       p.content_hash,
                       p.excerpt,
                       p.raw_payload,
                       p.classification_status,
                       p.detected_category,
                       p.detected_product_name,
                       p.confidence,
                       p.created_at,
                       p.updated_at,
                       p.deleted_at
                FROM manufacturer_posts p
                JOIN manufacturer_sources s ON s.id = p.manufacturer_source_id
                LEFT JOIN part_catalog_candidates c ON c.id = p.created_catalog_candidate_id
                WHERE p.public_id = ?::uuid
                """ + deletedClause + """
                LIMIT 1
                """, postId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "제조사 게시글을 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    private Map<String, Object> postMap(Map<String, Object> row) {
        return MockData.map(
                "id", row.get("public_id"),
                "sourceId", row.get("manufacturer_source_public_id"),
                "manufacturer", row.get("manufacturer"),
                "sourceUrl", row.get("source_url"),
                "externalUrl", row.get("external_url"),
                "title", row.get("title"),
                "publishedAt", row.get("published_at"),
                "excerpt", row.get("excerpt"),
                "classificationStatus", row.get("classification_status"),
                "detectedCategory", row.get("detected_category"),
                "detectedProductName", row.get("detected_product_name"),
                "confidence", row.get("confidence"),
                "catalogCandidateId", row.get("catalog_candidate_public_id"),
                "rawPayload", row.get("raw_payload"),
                "createdAt", row.get("created_at"),
                "updatedAt", row.get("updated_at"),
                "deletedAt", row.get("deleted_at")
        );
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

    private static String postStatus(String value) {
        String upper = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "PENDING";
        if (!POST_STATUSES.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 manufacturer post status입니다.");
        }
        return upper;
    }

    private static String optionalCategory(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 detectedCategory입니다.");
        }
        return upper;
    }

    private static void validateOfficialManufacturerSource(String manufacturer, String sourceUrl, Object parserConfig) {
        if (isDemoSource(manufacturer, sourceUrl, parserConfig)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl은 유효한 URL이어야 합니다.");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || !StringUtils.hasText(host)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "공식 제조사 sourceUrl은 https URL이어야 합니다.");
        }
        String normalizedManufacturer = normalizeManufacturer(manufacturer);
        List<String> domains = MANUFACTURER_DOMAINS.get(normalizedManufacturer);
        if (domains == null || domains.stream().noneMatch(domain -> host.equalsIgnoreCase(domain) || host.toLowerCase(Locale.ROOT).endsWith("." + domain))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceUrl은 등록 제조사의 공식 도메인이어야 합니다.");
        }
    }

    private static boolean isDemoSource(String manufacturer, String sourceUrl, Object parserConfig) {
        if (!"BUILDGRAPH DEMO".equals(normalizeManufacturer(manufacturer))) {
            return false;
        }
        String url = sourceUrl == null ? "" : sourceUrl.toLowerCase(Locale.ROOT);
        return (url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1"))
                && "true".equalsIgnoreCase(parserConfigValue(parserConfig, "demo"));
    }

    private static String normalizeManufacturer(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replace("&", "AND")
                .replaceAll("[^0-9A-Z가-힣!]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
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

    private static String parserConfigValue(Object parserConfig, String key) {
        if (parserConfig == null || !StringUtils.hasText(key)) {
            return null;
        }
        if (parserConfig instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value == null ? null : String.valueOf(value);
        }
        try {
            Map<?, ?> parsed = OBJECT_MAPPER.readValue(String.valueOf(parserConfig), Map.class);
            Object value = parsed.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
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

    private static Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double confidenceValue(Object value) {
        Double parsed = doubleValue(value);
        if (parsed == null) {
            return null;
        }
        if (parsed < 0 || parsed > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "confidence는 0 이상 1 이하이어야 합니다.");
        }
        return parsed;
    }

    private static OffsetDateTime offsetDateTimeValue(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        return parsePublishedAt(stringValue(value));
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

    private record AiClassification(
            boolean productCandidate,
            String category,
            String productName,
            double confidence,
            String searchQuery,
            String reason,
            Map<String, Object> raw
    ) {
    }
}
