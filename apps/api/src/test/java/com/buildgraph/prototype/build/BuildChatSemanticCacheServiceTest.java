package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.OpenAiEmbeddingClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BuildChatSemanticCacheServiceTest {
    @Test
    void lookupReturnsCachedResponseWhenDecisionIsEligibleAndSimilarityPassesThreshold() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OpenAiEmbeddingClient embeddingClient = mock(OpenAiEmbeddingClient.class);
        when(embeddingClient.isConfigured()).thenReturn(true);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(versions());
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 10L,
                "similarity", 0.96,
                "response_payload_text", """
                        {"answerType":"BUDGET","message":"cached","agentSessionId":"agent-1","evidenceIds":["ev-1"]}
                        """
        )));
        BuildChatSemanticCacheService service = new BuildChatSemanticCacheService(jdbcTemplate, embeddingClient, null, true, 0.94, 600);
        BuildChatIntentDecision decision = decision(BuildChatIntent.BUILD_RECOMMEND, "BUILD_RECOMMEND|category=ANY|part=ANY|budget=TARGET:3000000");

        Map<String, Object> cached = service.lookup(Map.of("message", "300만원 견적 추천해줘"), null, decision).orElseThrow();

        assertThat(cached).containsEntry("message", "cached");
        assertThat(cached.get("agentSessionId")).isNull();
        assertThat(cached.get("evidenceIds")).asList().isEmpty();
    }

    @Test
    void lookupMissesWhenSimilarityIsBelowThreshold() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OpenAiEmbeddingClient embeddingClient = mock(OpenAiEmbeddingClient.class);
        when(embeddingClient.isConfigured()).thenReturn(true);
        when(embeddingClient.embed(anyString())).thenReturn(List.of(0.1, 0.2, 0.3));
        when(jdbcTemplate.queryForMap(anyString())).thenReturn(versions());
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 10L,
                "similarity", 0.91,
                "response_payload_text", "{\"message\":\"too far\"}"
        )));
        BuildChatSemanticCacheService service = new BuildChatSemanticCacheService(jdbcTemplate, embeddingClient, null, true, 0.94, 600);

        assertThat(service.lookup(Map.of("message", "300만원 견적 추천해줘"), null,
                decision(BuildChatIntent.BUILD_RECOMMEND, "BUILD_RECOMMEND|budget=TARGET:3000000"))).isEmpty();
    }

    @Test
    void semanticCacheSkipsMutationAndUnconfiguredEmbedding() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        OpenAiEmbeddingClient embeddingClient = mock(OpenAiEmbeddingClient.class);
        when(embeddingClient.isConfigured()).thenReturn(false);
        BuildChatSemanticCacheService service = new BuildChatSemanticCacheService(jdbcTemplate, embeddingClient, null, true, 0.94, 600);

        assertThat(service.lookup(Map.of("message", "GPU 빼줘"), null,
                new BuildChatIntentDecision(BuildChatIntent.MUTATE_DRAFT_REMOVE, "HIGH", "LOW", "GPU", null, "FAST_DRAFT_ACTION", "NONE", null, List.of()))).isEmpty();
        assertThat(service.lookup(Map.of("message", "300만원 견적 추천해줘"), null,
                decision(BuildChatIntent.BUILD_RECOMMEND, "BUILD_RECOMMEND|budget=TARGET:3000000"))).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    private static BuildChatIntentDecision decision(BuildChatIntent intent, String signature) {
        return new BuildChatIntentDecision(intent, "HIGH", "NONE", null, null, "LLM_OR_DETERMINISTIC", "SEMANTIC_READ_ONLY", signature, List.of());
    }

    private static Map<String, Object> versions() {
        return Map.of(
                "parts_version", "parts-v1",
                "benchmark_version", "bench-v1",
                "fps_version", "fps-v1",
                "rag_version", "rag-v1",
                "alias_version", "alias-v1"
        );
    }
}
