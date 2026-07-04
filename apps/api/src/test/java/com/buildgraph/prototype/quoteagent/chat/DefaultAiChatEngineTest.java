package com.buildgraph.prototype.quoteagent.chat;

import com.buildgraph.prototype.opsagent.profile.AiProfileConfigTest;
import com.buildgraph.prototype.opsagent.profile.LlmProvider;
import com.buildgraph.prototype.opsagent.profile.LlmResponseResult;
import com.buildgraph.prototype.quoteagent.llm.AiChatClient;
import com.buildgraph.prototype.quoteagent.query.AiChatSessionState;
import com.buildgraph.prototype.quoteagent.query.AiChatSessionStore;
import com.buildgraph.prototype.quoteagent.tools.PartReplacementRanker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiChatEngineTest {
    private JdbcTemplate jdbcTemplate;
    private AiChatClient openAiResponsesClient;
    private AiChatSessionStore aiChatSessionStore;
    private AiChatEngine engine;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        openAiResponsesClient = mock(AiChatClient.class);
        aiChatSessionStore = mock(AiChatSessionStore.class);
        when(aiChatSessionStore.findOrCreate(any())).thenReturn(new AiChatSessionState("session-5090", Map.of()));
        engine = new AiChatEngine(
                jdbcTemplate,
                openAiResponsesClient,
                AiProfileConfigTest.config("AS_CHAT_FAST", "BUILD_CHAT_FAST"),
                new PartReplacementRanker(),
                aiChatSessionStore
        );

        doAnswer(invocation -> {
                    Object category = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    return partRows(String.valueOf(category)).stream().limit(limit).toList();
                })
                .when(jdbcTemplate)
                .queryForList(anyString(), anyString(), anyInt());
    }

    @Test
    void llmRequiredBuildChatFailsWhenOpenAiKeyIsMissing() {
        when(openAiResponsesClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> engine.respondLlmRequired(new AiChatRequestDto(
                "200만원 QHD 게임용 PC 추천해줘",
                "session-1"
        ), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredBuildChatUsesMessageAndSessionRequestShape() {
        when(openAiResponsesClient.isConfigured()).thenReturn(true);
        when(openAiResponsesClient.createStructuredJsonResult(
                anyString(),
                anyString(),
                eq("buildgraph_ai_build_chat_plan"),
                any(),
                eq("gpt-5.5"),
                eq("low"),
                eq(900)
        ))
                .thenReturn(new LlmResponseResult("""
                        {
                          "intent": "FULL_BUILD_RECOMMEND",
                          "assistantMessage": "RTX 5090 조건을 유지해 추천 조합을 만들겠습니다.",
                          "selectedCategory": null,
                          "parsedContext": {
                            "budget": null,
                            "usageTags": ["GAMING"],
                            "resolution": null,
                            "preferredVendors": ["NVIDIA"],
                            "priority": null,
                            "performanceTier": "ENTHUSIAST",
                            "budgetPolicy": "OPEN_BUDGET",
                            "mustHave": [],
                            "requiredGpuClasses": ["RTX_5090"],
                            "requiredPartKeywords": [],
                            "hardConstraintPolicy": "MUST_INCLUDE",
                            "confidence": {
                              "usageTags": "HIGH",
                              "budget": "LOW",
                              "resolution": "LOW",
                              "preferredVendors": "HIGH",
                              "mustHave": "LOW",
                              "requiredGpuClasses": "HIGH",
                              "requiredPartKeywords": "LOW"
                            },
                            "parseNotes": "사용자가 RTX 5090을 명시했습니다."
                          },
                          "draftEdit": {
                            "operation": "NONE",
                            "category": null,
                            "priceDirection": "ANY",
                            "targetMaxPrice": null,
                            "targetQuantity": null,
                            "reason": null
                          }
                        }
                        """, LlmProvider.OPENAI, "gpt-5.5", "low", 1234, 100, 80, 180));

        AiChatResponseDto response = engine.respondLlmRequired(new AiChatRequestDto(
                "5090 글카가 들어간 PC 추천해줘",
                "session-5090"
        ), null);

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.assistantMessage()).contains("추천 PC");
        assertThat(response.evidenceIds()).isEmpty();
        assertThat(response.parsedContext().get("requiredGpuClasses")).asList().containsExactly("RTX_5090");
        assertThat(response.recommendations()).hasSize(3);
        verifyNoJdbcWrites();
    }

    private void verifyNoJdbcWrites() {
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    private static List<Map<String, Object>> partRows(String category) {
        if ("GPU".equals(category)) {
            return List.of(
                    partRow(category, "gpu-5090", "GeForce RTX 5090 32GB", 5_000_000, Map.of("toolReady", true, "gpuClass", "RTX_5090")),
                    partRow(category, "gpu-5080", "GeForce RTX 5080 16GB", 2_300_000, Map.of("toolReady", true, "gpuClass", "RTX_5080")),
                    partRow(category, "gpu-4070s", "GeForce RTX 4070 Super", 950_000, Map.of("toolReady", true, "gpuClass", "RTX_4070_SUPER"))
            );
        }
        if ("CPU".equals(category)) {
            return List.of(
                    partRow(category, "cpu-high", "CPU High", 500_000, Map.of("toolReady", true, "socket", "AM5")),
                    partRow(category, "cpu-mid", "CPU Mid", 300_000, Map.of("toolReady", true, "socket", "AM5"))
            );
        }
        if ("MOTHERBOARD".equals(category)) {
            return List.of(partRow(category, "motherboard-am5", "AM5 Board", 240_000, Map.of("toolReady", true, "socket", "AM5")));
        }
        return List.of(partRow(category, category.toLowerCase() + "-default", category + " Default", 120_000));
    }

    private static Map<String, Object> partRow(String category, String id, String name, int price) {
        return partRow(category, id, name, price, Map.of("toolReady", true));
    }

    private static Map<String, Object> partRow(String category, String id, String name, int price, Map<String, Object> attributes) {
        return Map.of(
                "public_id", id,
                "category", category,
                "name", name,
                "manufacturer", "테스트",
                "price", price,
                "attributes", attributes
        );
    }
}
