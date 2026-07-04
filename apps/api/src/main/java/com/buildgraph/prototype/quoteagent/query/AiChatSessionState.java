package com.buildgraph.prototype.quoteagent.query;

import java.util.Map;

public record AiChatSessionState(
    String sessionId,
    Map<String, Object> context
) {
} 