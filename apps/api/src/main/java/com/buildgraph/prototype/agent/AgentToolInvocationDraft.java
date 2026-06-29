package com.buildgraph.prototype.agent;

import java.util.Map;

public record AgentToolInvocationDraft(
        String toolName,
        ToolStatus status,
        ConfidenceLevel confidence,
        String summary,
        Map<String, Object> requestPayload,
        Map<String, Object> resultPayload,
        Integer latencyMs
) {
}
