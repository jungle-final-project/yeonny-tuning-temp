package com.buildgraph.prototype.config.security;

public record AgentIdempotencyDecision(
        Status status,
        Long recordId,
        Integer responseStatus,
        String responseBody,
        String responseContentType
) {
    public static AgentIdempotencyDecision proceed(Long recordId) {
        return new AgentIdempotencyDecision(Status.PROCEED, recordId, null, null, null);
    }

    public static AgentIdempotencyDecision replay(Integer responseStatus, String responseBody, String responseContentType) {
        return new AgentIdempotencyDecision(Status.REPLAY, null, responseStatus, responseBody, responseContentType);
    }

    public static AgentIdempotencyDecision conflict() {
        return new AgentIdempotencyDecision(Status.CONFLICT, null, null, null, null);
    }

    public static AgentIdempotencyDecision inProgress() {
        return new AgentIdempotencyDecision(Status.IN_PROGRESS, null, null, null, null);
    }

    public enum Status {
        PROCEED,
        REPLAY,
        CONFLICT,
        IN_PROGRESS
    }
}
