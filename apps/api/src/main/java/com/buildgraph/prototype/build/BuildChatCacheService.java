package com.buildgraph.prototype.build;

import com.buildgraph.prototype.agent.AiProfileConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BuildChatCacheService {
    private static final Logger log = LoggerFactory.getLogger(BuildChatCacheService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long DATA_VERSION_TTL_NANOS = Duration.ofMinutes(5).toNanos();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private enum CacheScope {
        RECOMMENDATION,
        CONTEXTUAL,
        DEFAULT
    }

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final JdbcTemplate jdbcTemplate;
    private final AiProfileConfig aiProfileConfig;
    private final boolean enabled;
    private final Duration ttl;
    private volatile Map<String, Object> cachedDataVersions;
    private volatile long cachedDataVersionsAtNanos;

    @Autowired
    public BuildChatCacheService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            JdbcTemplate jdbcTemplate,
            AiProfileConfig aiProfileConfig,
            @Value("${ai.build-chat.cache.enabled:true}") boolean enabled,
            @Value("${ai.build-chat.cache.ttl-seconds:600}") long ttlSeconds
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.jdbcTemplate = jdbcTemplate;
        this.aiProfileConfig = aiProfileConfig;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        log.info("Build Chat cache initialized: enabled={}, ttlSeconds={}", enabled, this.ttl.toSeconds());
    }

    private BuildChatCacheService() {
        this.redisTemplateProvider = null;
        this.jdbcTemplate = null;
        this.aiProfileConfig = null;
        this.enabled = false;
        this.ttl = Duration.ZERO;
    }

    public static BuildChatCacheService disabled() {
        return new BuildChatCacheService();
    }

    public Optional<Map<String, Object>> lookup(Map<String, Object> request, String requestedAiProfile) {
        return lookup(request, requestedAiProfile, null);
    }

    public Optional<Map<String, Object>> lookup(Map<String, Object> request, String requestedAiProfile, Long userId) {
        log.debug("Build Chat cache lookup entered: enabled={}, userId={}, requestedAiProfile={}", enabled, userId, requestedAiProfile);
        if (!enabled) {
            log.info("Build Chat cache lookup skipped: disabled");
            return Optional.empty();
        }
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                log.warn("Build Chat cache lookup skipped: Redis template is not available while cache is enabled");
                return Optional.empty();
            }
            CacheScope scope = scopeFor(request);
            String key = cacheKey(request, requestedAiProfile, userId, scope);
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null || cached.isBlank()) {
                log.debug("Build Chat cache miss: scope={}, key={}", scope, key);
                return Optional.empty();
            }
            log.info("Build Chat cache hit: scope={}, key={}", scope, key);
            return Optional.of(cacheableResponse(OBJECT_MAPPER.readValue(cached, MAP_TYPE)));
        } catch (Exception error) {
            log.warn("Build Chat cache lookup failed; bypassing cache: {}", error.getMessage());
            return Optional.empty();
        }
    }

    public void store(Map<String, Object> request, String requestedAiProfile, Map<String, Object> response) {
        store(request, requestedAiProfile, null, response);
    }

    public void store(Map<String, Object> request, String requestedAiProfile, Long userId, Map<String, Object> response) {
        store(request, requestedAiProfile, userId, response, ttl);
    }

    public void store(Map<String, Object> request, String requestedAiProfile, Long userId, Map<String, Object> response, Duration ttlOverride) {
        log.debug("Build Chat cache store entered: enabled={}, userId={}, requestedAiProfile={}", enabled, userId, requestedAiProfile);
        if (!enabled) {
            log.info("Build Chat cache store skipped: disabled");
            return;
        }
        if (response == null || response.isEmpty()) {
            log.debug("Build Chat cache store skipped: empty response");
            return;
        }
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                log.warn("Build Chat cache store skipped: Redis template is not available while cache is enabled");
                return;
            }
            CacheScope scope = scopeFor(request);
            String key = cacheKey(request, requestedAiProfile, userId, scope);
            Duration effectiveTtl = ttlOverride == null || ttlOverride.isZero() || ttlOverride.isNegative() ? ttl : ttlOverride;
            redisTemplate.opsForValue().set(key, OBJECT_MAPPER.writeValueAsString(cacheableResponse(response)), effectiveTtl);
            log.info("Build Chat cache stored: scope={}, key={}, ttlSeconds={}", scope, key, effectiveTtl.toSeconds());
        } catch (Exception error) {
            log.warn("Build Chat cache store failed; response returned without caching: {}", error.getMessage());
        }
    }

    public void storeAsync(Map<String, Object> request, String requestedAiProfile, Long userId, Map<String, Object> response) {
        storeAsync(request, requestedAiProfile, userId, response, ttl);
    }

    public void storeAsync(Map<String, Object> request, String requestedAiProfile, Long userId, Map<String, Object> response, Duration ttlOverride) {
        if (!enabled || response == null || response.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> store(request, requestedAiProfile, userId, response, ttlOverride))
                .exceptionally(error -> {
                    log.warn("Build Chat cache async store failed; response already returned: {}", error.getMessage());
                    return null;
                });
    }

    private String cacheKey(Map<String, Object> request, String requestedAiProfile, Long userId, CacheScope scope) throws Exception {
        Map<String, Object> body = request == null ? Map.of() : request;
        boolean sharedRecommendation = scope == CacheScope.RECOMMENDATION && isStandaloneRecommendation(body);
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("scope", scope.name());
        fingerprint.put("profile", effectiveProfile(requestedAiProfile));
        fingerprint.put("userId", sharedRecommendation ? "shared" : (userId == null ? "anonymous" : userId));
        fingerprint.put("message", normalizeText(body.get("message")));
        fingerprint.put("selectedCategory", normalizeText(body.get("selectedCategory")));
        fingerprint.put("uiContext", uiContextFingerprint(body.get("uiContext")));
        fingerprint.put("cacheMode", sharedRecommendation ? "SHARED_STANDALONE_RECOMMENDATION" : scope.name());
        fingerprint.put("currentQuoteDraft", quoteDraftFingerprint(body.get("currentQuoteDraft")));
        if (scope != CacheScope.RECOMMENDATION) {
            fingerprint.put("currentBuilds", currentBuildsFingerprint(body.get("currentBuilds")));
        }
        fingerprint.put("versions", dataVersions());
        String json = OBJECT_MAPPER.writeValueAsString(fingerprint);
        return "buildgraph:build-chat:v32:" + sha256(json);
    }

    private static Map<String, Object> uiContextFingerprint(Object value) {
        Map<String, Object> uiContext = objectMap(value);
        List<String> capabilities = stringList(uiContext.get("capabilities")).stream()
                .map(BuildChatCacheService::normalizeText)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("surface", normalizeText(uiContext.get("surface")));
        normalized.put("capabilities", capabilities);
        return normalized;
    }

    private static CacheScope scopeFor(Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String message = normalizeText(body.get("message"));
        if (hasModifySignal(message)) {
            return CacheScope.CONTEXTUAL;
        }
        boolean hasSelectedCategory = normalizeText(body.get("selectedCategory")) != null;
        boolean hasQuoteDraftItems = !objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty();
        boolean hasCurrentBuilds = !objectMaps(body.get("currentBuilds")).isEmpty();
        if (hasCurrentBuilds && !hasSelectedCategory && !hasQuoteDraftItems) {
            return CacheScope.CONTEXTUAL;
        }
        boolean hasPartCategory = hasSelectedCategory
                || BuildChatService.detectPartCategory(message) != null;
        if (hasPartCategory && hasRecommendationSignal(message)) {
            return CacheScope.RECOMMENDATION;
        }
        if ((hasBuildSignal(message) || BuildChatService.parseBudgetWon(message) != null) && hasRecommendationSignal(message)) {
            return CacheScope.RECOMMENDATION;
        }
        return CacheScope.DEFAULT;
    }

    private static boolean isStandaloneRecommendation(Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String normalized = normalizeCommand(body.get("message"));
        if (normalized.isBlank()) {
            return false;
        }
        if (normalizeText(body.get("selectedCategory")) != null) {
            return false;
        }
        if (!objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()) {
            return false;
        }
        if (!objectMaps(body.get("currentBuilds")).isEmpty()) {
            return false;
        }
        if (containsAnyNormalized(
                normalized,
                "이견적", "그견적", "저견적", "현재견적", "기존견적", "방금", "최근", "아까", "위조합", "이조합", "그조합", "저조합",
                "장바구니", "담긴", "선택한", "바꿔", "교체", "빼", "삭제", "제거", "담아", "넣어", "추가", "적용", "수량", "변경",
                "더싼", "저렴", "낮춰", "더좋", "업그레이드", "비슷한가격"
        )) {
            return false;
        }
        return containsAnyNormalized(normalized, "견적", "pc", "컴퓨터", "본체", "조립pc", "조립컴", "추천상담", "추천해줘", "추천");
    }

    private String effectiveProfile(String requestedAiProfile) {
        if (aiProfileConfig == null) {
            return requestedAiProfile == null || requestedAiProfile.isBlank() ? "DEFAULT" : requestedAiProfile.trim().toUpperCase();
        }
        return aiProfileConfig.buildChatProfile(requestedAiProfile).profile().name();
    }

    private Map<String, Object> quoteDraftFingerprint(Object value) {
        Map<String, Object> draft = objectMap(value);
        List<Map<String, Object>> items = objectMaps(draft.get("items")).stream()
                .map(item -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("partId", normalizeText(item.get("partId")));
                    normalized.put("category", normalizeText(item.get("category")));
                    normalized.put("quantity", item.get("quantity"));
                    return normalized;
                })
                .sorted(Comparator
                        .comparing((Map<String, Object> item) -> String.valueOf(item.get("category")))
                        .thenComparing(item -> String.valueOf(item.get("partId"))))
                .toList();
        return Map.of("items", items);
    }

    private Map<String, Object> currentBuildsFingerprint(Object value) {
        List<Map<String, Object>> builds = objectMaps(value).stream()
                .map(build -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("id", normalizeText(build.get("id")));
                    normalized.put("tier", normalizeText(build.get("tier")));
                    normalized.put("budget", build.get("budgetWon") == null ? build.get("budget") : build.get("budgetWon"));
                    normalized.put("items", buildItemsFingerprint(build.get("items")));
                    return normalized;
                })
                .sorted(Comparator
                        .comparing((Map<String, Object> build) -> String.valueOf(build.get("id")))
                        .thenComparing(build -> String.valueOf(build.get("tier")))
                        .thenComparing(build -> String.valueOf(build.get("budget"))))
                .toList();
        return Map.of("builds", builds);
    }

    private List<Map<String, Object>> buildItemsFingerprint(Object value) {
        return objectMaps(value).stream()
                .map(item -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("partId", normalizeText(item.get("partId")));
                    normalized.put("category", normalizeText(item.get("category")));
                    normalized.put("quantity", item.get("quantity"));
                    return normalized;
                })
                .sorted(Comparator
                        .comparing((Map<String, Object> item) -> String.valueOf(item.get("category")))
                        .thenComparing(item -> String.valueOf(item.get("partId")))
                        .thenComparing(item -> String.valueOf(item.get("quantity"))))
                .toList();
    }

    private Map<String, Object> dataVersions() {
        if (jdbcTemplate == null) {
            return Map.of();
        }
        Map<String, Object> cached = cachedDataVersions;
        long now = System.nanoTime();
        if (cached != null && now - cachedDataVersionsAtNanos < DATA_VERSION_TTL_NANOS) {
            return cached;
        }
        try {
            Map<String, Object> versions = jdbcTemplate.queryForMap("""
                    SELECT
                      coalesce((SELECT max(coalesce(updated_at, created_at))::text FROM parts WHERE deleted_at IS NULL), 'none') AS parts_version,
                      coalesce((SELECT max(created_at)::text FROM benchmark_summaries WHERE deleted_at IS NULL), 'none') AS benchmark_version,
                      coalesce((SELECT max(created_at)::text FROM game_fps_benchmarks WHERE deleted_at IS NULL), 'none') AS fps_version,
                      coalesce((SELECT max(created_at)::text FROM rag_evidence WHERE agent_session_id IS NULL), 'none') AS rag_version,
                      coalesce((SELECT max(coalesce(updated_at, created_at))::text FROM part_alias_rules WHERE deleted_at IS NULL), 'none') AS alias_version
                    """);
            cachedDataVersions = versions;
            cachedDataVersionsAtNanos = now;
            return versions;
        } catch (Exception error) {
            return Map.of("versionError", error.getClass().getSimpleName());
        }
    }

    void clearDataVersionCacheForTest() {
        cachedDataVersions = null;
        cachedDataVersionsAtNanos = 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cacheableResponse(Map<String, Object> response) {
        Object stripped = stripVolatileTrace(response);
        if (stripped instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private static Object stripVolatileTrace(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                String textKey = String.valueOf(key);
                if ("agentSessionId".equals(textKey)) {
                    result.put(textKey, null);
                } else if ("evidenceIds".equals(textKey) || "toolInvocationIds".equals(textKey)) {
                    result.put(textKey, List.of());
                } else {
                    result.put(textKey, stripVolatileTrace(mapValue));
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(BuildChatCacheService::stripVolatileTrace).toList();
        }
        return value;
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private static String normalizeText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim()
                .replaceAll("(?<=\\d),(?=\\d)", "")
                .replaceAll("\\s+", " ");
        return text.isEmpty() ? null : text.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean hasModifySignal(String message) {
        return containsAnyNormalized(
                normalizeCommand(message),
                "바꿔",
                "변경",
                "교체",
                "더 싼",
                "더싼",
                "비싸",
                "낮춰",
                "줄여",
                "올려",
                "높여",
                "빼줘",
                "빼",
                "삭제",
                "제거",
                "추가",
                "현재 견적",
                "방금 견적",
                "이 견적"
        );
    }

    private static boolean hasRecommendationSignal(String message) {
        return containsAnyNormalized(normalizeCommand(message), "추천", "맞춰", "짜줘", "구성", "골라");
    }

    private static boolean hasBuildSignal(String message) {
        return containsAnyNormalized(normalizeCommand(message), "pc", "컴퓨터", "본체", "견적", "맞춰");
    }

    private static String normalizeCommand(Object value) {
        return value == null ? "" : value.toString().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    private static boolean containsAnyNormalized(String normalized, String... keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(normalizeCommand(keyword))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(objectMap(item));
        }
        return result;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? null : item.toString())
                    .filter(Objects::nonNull)
                    .toList();
        }
        return List.of();
    }
}
