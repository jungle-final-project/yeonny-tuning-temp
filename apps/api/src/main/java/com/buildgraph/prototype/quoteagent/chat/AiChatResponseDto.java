package com.buildgraph.prototype.quoteagent.chat;

import java.util.List;
import java.util.Map;

public record AiChatResponseDto(
        String replyMessage,
        String sessionId,
        String respondType,
        List<BuildRecommendation> recommendations,
        List<PartRecommendation> partRecommendations
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
