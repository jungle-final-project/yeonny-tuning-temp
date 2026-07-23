package com.buildgraph.prototype.aichat.chat;

import java.util.Map;

public record AiChatAction(
        AiChatActionType type,
        String label,
        Map<String, Object> payload
) {
}
