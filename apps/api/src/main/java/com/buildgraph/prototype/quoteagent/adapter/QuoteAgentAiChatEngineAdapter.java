package com.buildgraph.prototype.quoteagent.adapter;

import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatRequestDto;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatResponseDto;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QuoteAgentAiChatEngineAdapter {
    private final com.buildgraph.prototype.quoteagent.chat.AiChatEngine quoteAgent;

    public QuoteAgentAiChatEngineAdapter(
            com.buildgraph.prototype.quoteagent.chat.AiChatEngine quoteAgent
    ) {
        this.quoteAgent = quoteAgent;
    }

    public AiChatEngineResponse respond(AiChatEngineRequest request) {
        return respondLlmRequired(request, null);
    }

    public AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request) {
        return respondLlmRequired(request, null);
    }

    public AiChatEngineResponse respondLlmRequired(
            AiChatEngineRequest request,
            String requestedAiProfile
    ) {
        String sessionId = sessionId(request == null ? null : request.context());
        AiChatResponseDto response = quoteAgent.LLMorchestrator(
                new AiChatRequestDto(
                        request == null ? null : request.message(),
                        sessionId,
                        request == null ? null : request.userInternalId()
                ),
                requestedAiProfile
        );
        return toMainResponse(response);
    }

    private static AiChatEngineResponse toMainResponse(AiChatResponseDto source) {
        List<AiChatEngineResponse.PartRecommendation> parts = source.partRecommendations().stream()
                .map(QuoteAgentAiChatEngineAdapter::toMainPart)
                .toList();
        List<AiChatEngineResponse.BuildRecommendation> builds = source.recommendations().stream()
                .map(build -> new AiChatEngineResponse.BuildRecommendation(
                        build.recommendedFor(),
                        build.recommendedFor(),
                        "성능·가성비 선호 벡터와 Tool 검증을 기준으로 선택한 조합입니다.",
                        build.estimatedTotalPrice(),
                        "MEDIUM",
                        build.items().stream()
                                .map(QuoteAgentAiChatEngineAdapter::toMainPart)
                                .toList()
                ))
                .toList();

        return new AiChatEngineResponse(
                source.replyMessage(),
                toMainIntent(source.respondType()),
                List.<AiChatAction>of(),
                builds,
                parts,
                Map.of("quoteAgentMode", true),
                List.of(),
                List.of(),
                source.sessionId()
        );
    }

    private static AiChatEngineResponse.PartRecommendation toMainPart(
            AiChatResponseDto.PartRecommendation part
    ) {
        return new AiChatEngineResponse.PartRecommendation(
                part.partId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes() == null ? Map.of() : part.attributes()
        );
    }

    private static AiChatIntent toMainIntent(String respondType) {
        if (respondType == null || "CONVERSATION".equals(respondType)) {
            return AiChatIntent.ASK_FOLLOW_UP;
        }
        try {
            return AiChatIntent.valueOf(respondType);
        } catch (IllegalArgumentException ignored) {
            return AiChatIntent.ASK_FOLLOW_UP;
        }
    }

    private static String sessionId(Map<String, Object> context) {
        if (context == null) return null;
        Object value = context.get("sessionId");
        if (value == null) value = context.get("agentSessionId");
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
