package com.buildgraph.prototype.quoteagent.chat.dto;

public record AiChatRequestDto(
        String message,
        String sessionId,
        Long userInternalId
) {
}
