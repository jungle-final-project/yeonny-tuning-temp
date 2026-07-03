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
import java.util.Optional;
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
            redisTemplate.opsForValue().set(key, OBJECT_MAPPER.writeValueAsString(cacheableResponse(response)), ttl);
            log.info("Build Chat cache stored: scope={}, key={}", scope, key);
        } catch (Exception error) {
            log.warn("Build Chat cache store failed; response returned without caching: {}", error.getMessage());
        }
    }

    private String cacheKey(Map<String, Object> request, String requestedAiProfile, Long userId, CacheScope scope) throws Exception {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("scope", scope.name());
        fingerprint.put("profile", effectiveProfile(requestedAiProfile));
        fingerprint.put("userId", userId == null ? "anonymous" : userId);
        fingerprint.put("message", normalizeText(request.get("message")));
        fingerprint.put("selectedCategory", normalizeText(request.get("selectedCategory")));
        fingerprint.put("currentQuoteDraft", quoteDraftFingerprint(request.get("currentQuoteDraft")));
        if (scope != CacheScope.RECOMMENDATION) {
            fingerprint.put("currentBuilds", currentBuildsFingerprint(request.get("currentBuilds")));
            fingerprint.put("appliedPartPreferences", appliedPartPreferencesFingerprint(request.get("appliedPartPreferences")));
        }
        fingerprint.put("versions", dataVersions());
        String json = OBJECT_MAPPER.writeValueAsString(fingerprint);
        return "buildgraph:build-chat:v8:" + sha256(json);
    }

    private static CacheScope scopeFor(Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String message = normalizeText(body.get("message"));
        if (hasModifySignal(message)) {
            return CacheScope.CONTEXTUAL;
        }
        boolean hasPartCategory = normalizeText(body.get("selectedCategory")) != null
                || BuildChatService.detectPartCategory(message) != null;
        if (hasPartCategory && hasRecommendationSignal(message)) {
            return CacheScope.RECOMMENDATION;
        }
        if ((hasBuildSignal(message) || BuildChatService.parseBudgetWon(message) != null) && hasRecommendationSignal(message)) {
            return CacheScope.RECOMMENDATION;
        }
        return CacheScope.DEFAULT;
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

    private Map<String, Object> appliedPartPreferencesFingerprint(Object value) {
        List<Map<String, Object>> preferences = objectMaps(value).stream()
                .map(preference -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("category", normalizeText(preference.get("category")));
                    normalized.put("optionPartIds", optionPartIdsFingerprint(preference.get("options")));
                    return normalized;
                })
                .sorted(Comparator
                        .comparing((Map<String, Object> preference) -> String.valueOf(preference.get("category")))
                        .thenComparing(preference -> String.valueOf(preference.get("optionPartIds"))))
                .toList();
        return Map.of("preferences", preferences);
    }

    private List<String> optionPartIdsFingerprint(Object value) {
        return objectMaps(value).stream()
                .map(option -> normalizeText(option.get("partId")))
                .filter(partId -> partId != null)
                .sorted()
                .toList();
    }

    private Map<String, Object> dataVersions() {
        if (jdbcTemplate == null) {
            return Map.of();
        }
        try {
            return jdbcTemplate.queryForMap("""
                    SELECT
                      coalesce((SELECT max(coalesce(updated_at, created_at))::text FROM parts WHERE deleted_at IS NULL), 'none') AS parts_version,
                      coalesce((SELECT max(created_at)::text FROM benchmark_summaries WHERE deleted_at IS NULL), 'none') AS benchmark_version,
                      coalesce((SELECT max(created_at)::text FROM game_fps_benchmarks WHERE deleted_at IS NULL), 'none') AS fps_version,
                      coalesce((SELECT max(created_at)::text FROM rag_evidence WHERE agent_session_id IS NULL), 'none') AS rag_version,
                      coalesce((SELECT max(coalesce(updated_at, created_at))::text FROM part_alias_rules WHERE deleted_at IS NULL), 'none') AS alias_version
                    """);
        } catch (Exception error) {
            return Map.of("versionError", error.getClass().getSimpleName());
        }
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
        String text = value.toString().trim().replaceAll("\\s+", " ");
        return text.isEmpty() ? null : text.toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean hasModifySignal(String message) {
        return containsAnySignal(
                message,
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
        return containsAnySignal(message, "추천", "맞춰", "짜줘", "구성", "골라");
    }

    private static boolean hasBuildSignal(String message) {
        return containsAnySignal(message, "pc", "컴퓨터", "본체", "견적", "맞춰");
    }

    private static boolean containsAnySignal(String message, String... keywords) {
        if (message == null) {
            return false;
        }
        String compactMessage = compact(message);
        for (String keyword : keywords) {
            String normalizedKeyword = normalizeText(keyword);
            if (normalizedKeyword != null && (message.contains(normalizedKeyword) || compactMessage.contains(compact(normalizedKeyword)))) {
                return true;
            }
        }
        return false;
    }

    private static String compact(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
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
}
