package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.buildgraph.prototype.agent.AiProfile;
import com.buildgraph.prototype.agent.AiProfileConfig;
import com.buildgraph.prototype.agent.AiProfileDefinition;
import com.buildgraph.prototype.agent.LlmProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

class BuildChatCacheServiceTest {
    @Test
    void verifiedMockRequestBypassesExactCacheLookupAndStore() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BuildChatCacheService service = new BuildChatCacheService(provider, jdbcTemplate, profileConfig(), true, 600);
        Map<String, Object> request = BuildChatTestMode.sanitizedRequest(Map.of("message", "mock"), true);

        assertThat(service.lookup(request, null, 1L)).isEmpty();
        service.store(request, null, 1L, Map.of("message", "mock"));

        verifyNoInteractions(provider, jdbcTemplate);
    }

    @Test
    void cacheHitRemovesVolatileTraceIdsAndSeparatesUserProfileDraftAndVersions() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> request = request(1);
        Map<String, Object> response = Map.of(
                "answerType", "PART",
                "message", "GPU 후보입니다.",
                "agentSessionId", "agent-session-1",
                "evidenceIds", List.of("evidence-1"),
                "builds", List.of(Map.of(
                        "title", "추천 조합",
                        "evidenceIds", List.of("nested-evidence")
                ))
        );

        cache.service.store(request, "BUILD_CHAT_54_MINI_FAST", 42L, response);

        Map<String, Object> cached = cache.service.lookup(request, "BUILD_CHAT_54_MINI_FAST", 42L).orElseThrow();
        assertThat(cached.get("agentSessionId")).isNull();
        assertThat(cached.get("evidenceIds")).asList().isEmpty();
        assertThat(cached.get("builds")).asList().first()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extracting("evidenceIds")
                .asList()
                .isEmpty();

        assertThat(cache.service.lookup(request, "BUILD_CHAT_54_MINI_FAST", 7L)).isEmpty();
        assertThat(cache.service.lookup(request, "BUILD_CHAT_FAST", 42L)).isEmpty();
        assertThat(cache.service.lookup(request(2), "BUILD_CHAT_54_MINI_FAST", 42L)).isEmpty();

        cache.versions.set(versions("parts-v2"));
        cache.service.clearDataVersionCacheForTest();
        assertThat(cache.service.lookup(request, "BUILD_CHAT_54_MINI_FAST", 42L)).isEmpty();
    }

    @Test
    void redisFailureBypassesCacheWithoutBreakingBuildChat() {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(operations);
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(versions("parts-v1"));
        when(operations.get(anyString())).thenThrow(new RuntimeException("redis down"));
        doThrow(new RuntimeException("redis down")).when(operations).set(anyString(), anyString(), any(Duration.class));

        BuildChatCacheService service = new BuildChatCacheService(provider, jdbcTemplate, profileConfig(), true, 600);

        assertThat(service.lookup(request(1), "BUILD_CHAT_54_MINI_FAST", 42L)).isEmpty();
        assertThatCode(() -> service.store(request(1), "BUILD_CHAT_54_MINI_FAST", 42L, Map.of("message", "ok")))
                .doesNotThrowAnyException();
    }

    @Test
    void recommendationScopeIgnoresCurrentBuildsAndAppliedPartPreferences() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> response = Map.of("answerType", "PART", "message", "GPU 후보입니다.");

        cache.service.store(
                recommendationRequest(1, "gpu-current-1", "gpu-option-1", "2026-07-03T10:00:00Z"),
                "BUILD_CHAT_54_MINI_FAST",
                42L,
                response
        );

        assertThat(cache.service.lookup(
                recommendationRequest(1, "gpu-current-2", "gpu-option-2", "2026-07-03T10:05:00Z"),
                "BUILD_CHAT_54_MINI_FAST",
                42L
        )).isPresent();
    }

    @Test
    void recommendationScopeStillSeparatesCurrentQuoteDraftChanges() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> response = Map.of("answerType", "PART", "message", "GPU 후보입니다.");

        cache.service.store(
                recommendationRequest(1, "gpu-current-1", "gpu-option-1", "2026-07-03T10:00:00Z"),
                "BUILD_CHAT_54_MINI_FAST",
                42L,
                response
        );

        assertThat(cache.service.lookup(
                recommendationRequest(2, "gpu-current-1", "gpu-option-1", "2026-07-03T10:00:00Z"),
                "BUILD_CHAT_54_MINI_FAST",
                42L
        )).isEmpty();
    }

    @Test
    void contextualScopeSeparatesCurrentBuildChanges() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> response = Map.of("answerType", "PART", "message", "GPU 교체 후보입니다.");

        cache.service.store(
                contextualRequest("gpu-current-1", "gpu-option-1", "2026-07-03T10:00:00Z"),
                "BUILD_CHAT_54_MINI_FAST",
                42L,
                response
        );

        assertThat(cache.service.lookup(
                contextualRequest("gpu-current-2", "gpu-option-1", "2026-07-03T10:00:00Z"),
                "BUILD_CHAT_54_MINI_FAST",
                42L
        )).isEmpty();
    }

    @Test
    void contextualScopeIgnoresAppliedPartPreferenceTimestampOnlyChanges() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> response = Map.of("answerType", "PART", "message", "GPU 교체 후보입니다.");

        cache.service.store(
                contextualRequest("gpu-current-1", "gpu-option-1", "2026-07-03T10:00:00Z"),
                "BUILD_CHAT_54_MINI_FAST",
                42L,
                response
        );

        assertThat(cache.service.lookup(
                contextualRequest("gpu-current-1", "gpu-option-1", "2026-07-03T10:05:00Z"),
                "BUILD_CHAT_54_MINI_FAST",
                42L
        )).isPresent();
    }

    @Test
    void standaloneBuildRecommendationCacheIsSharedAcrossUsers() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> firstRequest = standaloneBuildRequest();
        Map<String, Object> secondRequest = standaloneBuildRequest();
        Map<String, Object> response = Map.of(
                "answerType", "BUDGET",
                "message", "300만원 견적입니다.",
                "builds", List.of(Map.of("id", "build-result"))
        );

        cache.service.store(firstRequest, "BUILD_CHAT_54_MINI_FAST", 42L, response);

        assertThat(cache.service.lookup(secondRequest, "BUILD_CHAT_54_MINI_FAST", 42L)).isPresent();
        assertThat(cache.service.lookup(secondRequest, "BUILD_CHAT_54_MINI_FAST", 7L)).isPresent();
    }

    @Test
    void contextualPartRecommendationCacheKeepsLatestBuildContextInKey() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> firstRequest = partContextRequest("build-a", "gpu-a");
        Map<String, Object> secondRequest = partContextRequest("build-b", "gpu-b");
        Map<String, Object> response = Map.of(
                "answerType", "PART",
                "message", "GPU 후보입니다.",
                "builds", List.of(Map.of("id", "build-result"))
        );

        cache.service.store(firstRequest, "BUILD_CHAT_54_MINI_FAST", 42L, response);

        assertThat(cache.service.lookup(secondRequest, "BUILD_CHAT_54_MINI_FAST", 42L)).isEmpty();
    }

    @Test
    void standalonePartRecommendationWithoutDraftFingerprintBypassesCache() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> request = Map.of("message", "고성능 GPU 추천해줘");
        Map<String, Object> response = Map.of(
                "answerType", "PART",
                "message", "GPU 후보입니다.",
                "partRecommendation", Map.of("category", "GPU")
        );

        cache.service.store(request, "BUILD_CHAT_54_MINI_FAST", 42L, response);

        assertThat(redisStore).isEmpty();
        assertThat(cache.service.lookup(request, "BUILD_CHAT_54_MINI_FAST", 42L)).isEmpty();
        assertThat(cache.service.lookup(request, "BUILD_CHAT_54_MINI_FAST", 7L)).isEmpty();
    }

    @Test
    void standalonePartRecommendationWithExplicitDraftFingerprintCanCachePerUser() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> request = Map.of(
                "message", "고성능 GPU 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of()));
        Map<String, Object> response = Map.of(
                "answerType", "PART",
                "message", "GPU 후보입니다.",
                "partRecommendation", Map.of("category", "GPU")
        );

        cache.service.store(request, "BUILD_CHAT_54_MINI_FAST", 42L, response);

        assertThat(cache.service.lookup(request, "BUILD_CHAT_54_MINI_FAST", 42L)).isPresent();
        assertThat(cache.service.lookup(request, "BUILD_CHAT_54_MINI_FAST", 7L)).isEmpty();
    }

    @Test
    void boardFocusCapabilityIsPartOfTheCacheFingerprint() {
        Map<String, String> redisStore = new LinkedHashMap<>();
        TestCache cache = cache(redisStore);
        Map<String, Object> selfQuoteRequest = Map.of(
                "message", "CPU랑 RAM 위치 보여줘",
                "uiContext", Map.of(
                        "surface", "SELF_QUOTE",
                        "capabilities", List.of("BOARD_PART_FOCUS")
                )
        );
        Map<String, Object> homeRequest = Map.of(
                "message", "CPU랑 RAM 위치 보여줘",
                "uiContext", Map.of("surface", "HOME", "capabilities", List.of())
        );
        Map<String, Object> response = Map.of(
                "answerType", "GENERAL",
                "message", "CPU · RAM 위치를 강조했습니다.",
                "boardFocus", Map.of("type", "PART_LOCATION", "categories", List.of("CPU", "RAM"))
        );

        cache.service.store(selfQuoteRequest, "BUILD_CHAT_54_MINI_FAST", 42L, response);

        assertThat(cache.service.lookup(selfQuoteRequest, "BUILD_CHAT_54_MINI_FAST", 42L)).isPresent();
        assertThat(cache.service.lookup(homeRequest, "BUILD_CHAT_54_MINI_FAST", 42L)).isEmpty();
    }

    private static TestCache cache(Map<String, String> redisStore) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AtomicReference<Map<String, Object>> versions = new AtomicReference<>(versions("parts-v1"));
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString())).thenAnswer(invocation -> redisStore.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            redisStore.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        when(jdbcTemplate.queryForMap(anyString())).thenAnswer(invocation -> versions.get());
        return new TestCache(new BuildChatCacheService(provider, jdbcTemplate, profileConfig(), true, 600), versions);
    }

    private static AiProfileConfig profileConfig() {
        AiProfileConfig config = mock(AiProfileConfig.class);
        when(config.buildChatProfile(nullable(String.class))).thenAnswer(invocation -> {
            String name = invocation.getArgument(0, String.class);
            if ("BUILD_CHAT_FAST".equals(name)) {
                return definition(AiProfile.BUILD_CHAT_FAST);
            }
            return definition(AiProfile.BUILD_CHAT_54_MINI_FAST);
        });
        return config;
    }

    private static AiProfileDefinition definition(AiProfile profile) {
        return new AiProfileDefinition(
                profile,
                LlmProvider.OPENAI,
                "gpt-5.4-mini",
                "low",
                3,
                profile.name().toLowerCase(),
                850,
                0,
                false,
                false,
                true
        );
    }

    private static Map<String, Object> request(int quantity) {
        return Map.of(
                "message", "그래픽카드 더 싼 걸로 추천해줘",
                "selectedCategory", "GPU",
                "currentQuoteDraft", Map.of(
                        "items", List.of(Map.of(
                                "partId", "part-gpu-1",
                                "category", "GPU",
                                "quantity", quantity
                        ))
                )
        );
    }

    private static Map<String, Object> recommendationRequest(
            int draftQuantity,
            String currentGpuPartId,
            String preferencePartId,
            String appliedAt
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", "그래픽카드 추천해줘");
        request.put("selectedCategory", "GPU");
        request.put("currentQuoteDraft", quoteDraft(draftQuantity));
        request.put("currentBuilds", currentBuilds(currentGpuPartId));
        request.put("appliedPartPreferences", appliedPartPreferences(preferencePartId, appliedAt));
        return request;
    }

    private static Map<String, Object> contextualRequest(String currentGpuPartId, String preferencePartId, String appliedAt) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", "방금 견적에서 그래픽카드 더 싼 걸로 바꿔줘");
        request.put("selectedCategory", "GPU");
        request.put("currentQuoteDraft", quoteDraft(1));
        request.put("currentBuilds", currentBuilds(currentGpuPartId));
        request.put("appliedPartPreferences", appliedPartPreferences(preferencePartId, appliedAt));
        return request;
    }

    private static Map<String, Object> quoteDraft(int quantity) {
        return Map.of(
                "items", List.of(Map.of(
                        "partId", "part-gpu-1",
                        "category", "GPU",
                        "quantity", quantity
                ))
        );
    }

    private static Map<String, Object> standaloneBuildRequest() {
        return Map.of(
                "message", "300만원 견적 추천해줘"
        );
    }

    private static Map<String, Object> partContextRequest(String buildId, String gpuPartId) {
        return Map.of(
                "message", "GPU 추천해줘",
                "currentBuilds", List.of(Map.of(
                        "id", buildId,
                        "items", List.of(Map.of(
                                "partId", gpuPartId,
                                "category", "GPU",
                                "quantity", 1
                        ))
                ))
        );
    }

    private static List<Map<String, Object>> currentBuilds(String gpuPartId) {
        return List.of(Map.of(
                "id", "build-current",
                "tier", "balanced",
                "budgetWon", 2_000_000,
                "items", List.of(
                        Map.of("partId", "cpu-current", "category", "CPU", "quantity", 1),
                        Map.of("partId", gpuPartId, "category", "GPU", "quantity", 1)
                )
        ));
    }

    private static List<Map<String, Object>> appliedPartPreferences(String partId, String appliedAt) {
        return List.of(Map.of(
                "category", "GPU",
                "label", "GPU",
                "appliedAt", appliedAt,
                "options", List.of(Map.of(
                        "partId", partId,
                        "category", "GPU",
                        "quantity", 1,
                        "price", 900_000
                ))
        ));
    }

    private static Map<String, Object> versions(String partsVersion) {
        return Map.of(
                "parts_version", partsVersion,
                "benchmark_version", "benchmark-v1",
                "fps_version", "fps-v1",
                "rag_version", "rag-v1",
                "alias_version", "alias-v1"
        );
    }

    private record TestCache(BuildChatCacheService service, AtomicReference<Map<String, Object>> versions) {
    }
}
