package com.buildgraph.prototype.quoteagent.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
import com.buildgraph.prototype.aichat.adapter.QuoteAgentAiChatEngineAdapter;
import com.buildgraph.prototype.aichat.chat.dto.AiChatRequestDto;
import com.buildgraph.prototype.aichat.chat.dto.AiChatResponseDto;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuoteAgentAiChatEngineAdapterTest {
    @Mock
    private com.buildgraph.prototype.aichat.chat.AiChatEngine quoteAgent;


    @Test
    void mapsQuoteAgentBuildToMainEngineContract() {
        String sessionId = "2ac2fccd-49dc-4baa-b2f0-d3ca63733378";
        AiChatResponseDto.PartRecommendation part = new AiChatResponseDto.PartRecommendation(
                "part-1",
                "CPU",
                "CPU A",
                "Maker",
                300000,
                Map.of("matchScore", 0.8)
        );
        when(quoteAgent.LLMorchestrator(
                new AiChatRequestDto("300만원 게임용", 7L),
                "BUILD_CHAT_FAST"
        )).thenReturn(new AiChatResponseDto(
                "전체 견적을 추천합니다.",
                sessionId,
                "FULL_BUILD_RECOMMEND",
                List.of(new AiChatResponseDto.BuildRecommendation(
                        "게임용",
                        300000,
                        List.of(part)
                )),
                List.of()
        ));

        QuoteAgentAiChatEngineAdapter adapter = new QuoteAgentAiChatEngineAdapter(quoteAgent);
        AiChatEngineResponse response = adapter.respondLlmRequired(
                new AiChatEngineRequest(
                        "300만원 게임용",
                        "HOME",
                        null,
                        null,
                        null,
                        Map.of("agentSessionId", sessionId),
                        7L
                ),
                "BUILD_CHAT_FAST"
        );

        assertEquals(AiChatIntent.FULL_BUILD_RECOMMEND, response.intent());
        assertNull(response.agentSessionId());
        assertEquals("part-1", response.recommendations().get(0).items().get(0).partId());
        assertTrue(Boolean.TRUE.equals(response.parsedContext().get("quoteAgentMode")));
    }
}
