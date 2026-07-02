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
