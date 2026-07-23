package com.buildgraph.prototype.aichat.adapter;

import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
import com.buildgraph.prototype.aichat.chat.dto.AiChatRequestDto;
import com.buildgraph.prototype.aichat.chat.dto.AiChatResponseDto;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QuoteAgentAiChatEngineAdapter {
    private final com.buildgraph.prototype.aichat.chat.AiChatEngine quoteAgent;

    public QuoteAgentAiChatEngineAdapter(
            com.buildgraph.prototype.aichat.chat.AiChatEngine quoteAgent
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
        AiChatResponseDto response = quoteAgent.LLMorchestrator(
                new AiChatRequestDto(
                        request == null ? null : request.message(),
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
                null
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

}
