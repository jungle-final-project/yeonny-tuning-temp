package com.buildgraph.prototype.aichat.query;

import java.util.Map;

public record AiChatSessionState(
    String sessionId,
    Map<String, Object> context
) {
}