package com.buildgraph.prototype.quoteagent.chat;

import com.buildgraph.prototype.opsagent.profile.AiProfileConfigTest;
import com.buildgraph.prototype.opsagent.profile.LlmProvider;
import com.buildgraph.prototype.opsagent.profile.LLMresponseDto;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatRequestDto;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatResponseDto;
import com.buildgraph.prototype.quoteagent.llm.AiChatClient;
import com.buildgraph.prototype.quoteagent.query.AiChatSessionState;
import com.buildgraph.prototype.quoteagent.query.AiChatSessionQuery;
import com.buildgraph.prototype.quoteagent.tools.PartReplacementRanker;
import com.buildgraph.prototype.recommender.matching.PartMatchService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiChatEngineTest {
    private AiChatClient openAiResponsesClient;
    private AiChatSessionQuery aiChatSessionStore;
    private PartMatchService partMatchService;
    private AiChatEngine engine;

    @BeforeEach
    void setUp() {
        openAiResponsesClient = mock(AiChatClient.class);
        aiChatSessionStore = mock(AiChatSessionQuery.class);
        partMatchService = mock(PartMatchService.class);
        when(partMatchService.matchFullBuild(any())).thenReturn(matchedPartRows());
        when(aiChatSessionStore.findOrCreate(any())).thenReturn(new AiChatSessionState("session-5090", Map.of()));
        engine = new AiChatEngine(
                openAiResponsesClient,
                AiProfileConfigTest.config("AS_CHAT_FAST", "BUILD_CHAT_FAST"),
                new PartReplacementRanker(),
                aiChatSessionStore,
                partMatchService
        );

    }

    @Test
    void llmRequiredBuildChatFailsWhenOpenAiKeyIsMissing() {
        when(openAiResponsesClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> engine.LLMorchestrator(new AiChatRequestDto(
                "200만원 QHD 게임용 PC 추천해줘",
                "session-1"
        ), null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void llmRequiredBuildChatUsesMessageAndSessionRequestShape() {
        when(openAiResponsesClient.isConfigured()).thenReturn(true);
        when(openAiResponsesClient.generateLLMresponse(
                anyString(),
                anyString(),
                eq("buildgraph_ai_build_chat_plan"),
                any(),
                eq("gpt-5.5"),
                eq("low"),
                eq(900)
        ))
                .thenReturn(new LLMresponseDto("""
                        {
                          "conversationMode": false,
                          "replyMessage": "RTX 5090 조건을 유지해 추천 조합을 만들겠습니다.",
                          "action": {
                            "type": "FULL_BUILD_RECOMMEND",
                            "selectedCategory": null,
                            "ragQuery": {
                              "performance": 0.8,
                              "value": 0.2
                            }
                          },
                          "contextPatch": {
                            "budget": 2000000,
                            "usageTags": ["GAMING"]
                          }
                        }
                        """, LlmProvider.OPENAI, "gpt-5.5", "low", 1234));

        AiChatResponseDto response = engine.LLMorchestrator(new AiChatRequestDto(
                "5090 글카가 들어간 PC 추천해줘",
                "session-5090"
        ), null);

        assertThat(response.respondType()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND.name());
        assertThat(response.replyMessage()).isEqualTo("RTX 5090 조건을 유지해 추천 조합을 만들겠습니다.");
        assertThat(response.sessionId()).isEqualTo("session-5090");
        assertThat(response.recommendations()).hasSize(1);
        assertThat(response.recommendations().get(0).items()).hasSize(8);
        assertThat(response.recommendations().get(0).estimatedTotalPrice()).isEqualTo(960_000);
    }

    @Test
    void firstConversationUsesCreatedSessionIdWhenRequestSessionIdIsNull() {
        when(openAiResponsesClient.isConfigured()).thenReturn(true);
        when(openAiResponsesClient.generateLLMresponse(
                anyString(),
                anyString(),
                eq("buildgraph_ai_build_chat_plan"),
                any(),
                eq("gpt-5.5"),
                eq("low"),
                eq(900)
        )).thenReturn(new LLMresponseDto("""
                {
                  "conversationMode": true,
                  "replyMessage": "예산과 용도를 조금 더 알려주세요.",
                  "action": null,
                  "contextPatch": {
                    "budget": 2000000,
                    "usageTags": ["GAMING"]
                  }
                }
                """, LlmProvider.OPENAI, "gpt-5.5", "low", 1234));

        AiChatResponseDto response = engine.LLMorchestrator(
                new AiChatRequestDto("게이밍 PC가 필요해", null),
                null
        );

        assertThat(response.sessionId()).isEqualTo("session-5090");
        assertThat(response.respondType()).isEqualTo("CONVERSATION");
        verify(aiChatSessionStore).findOrCreate(null);
        verify(aiChatSessionStore).updateSession(
                "session-5090",
                Map.of(
                        "budget", 2000000,
                        "usageTags", List.of("GAMING")
                )
        );
    }

    private static List<Map<String, Object>> matchedPartRows() {
        return List.of(
                "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
        ).stream()
                .map(category -> Map.<String, Object>of(
                        "id", category.toLowerCase() + "-id",
                        "category", category,
                        "name", category + " 추천 부품",
                        "manufacturer", "테스트",
                        "price", 120_000,
                        "performance_score", 0.8,
                        "value_score", 0.7,
                        "match_score", 0.78
                ))
                .toList();
    }

}
