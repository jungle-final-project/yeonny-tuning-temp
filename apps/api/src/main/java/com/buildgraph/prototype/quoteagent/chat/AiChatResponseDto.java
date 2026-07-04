package com.buildgraph.prototype.quoteagent.chat;

import java.util.List;
import java.util.Map;

public record AiChatResponseDto(
        String assistantMessage,
        AiChatIntent intent,
        List<AiChatAction> actions,
        List<BuildRecommendation> recommendations,
        List<PartRecommendation> partRecommendations,
        Map<String, Object> parsedContext,
        List<String> evidenceIds,
        List<Map<String, Object>> toolResults,
        String agentSessionId
) {
    public record BuildRecommendation(
            String name,
            String recommendedFor,
            String summary,
            int estimatedTotalPrice,
            String confidence,
            List<PartRecommendation> items
    ) {
    }

    public record PartRecommendation(
            String partId,
            String category,
            String name,
            String manufacturer,
            int price,
            Map<String, Object> attributes
    ) {
    }
}
