package com.buildgraph.prototype.quoteagent.chat;

public record AiChatRequestDto(
        String message,
        String sessionId
) {
}
