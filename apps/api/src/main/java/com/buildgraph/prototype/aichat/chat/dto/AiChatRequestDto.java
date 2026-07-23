package com.buildgraph.prototype.aichat.chat.dto;

public record AiChatRequestDto(
        String message,
        Long userInternalId
) {
}
