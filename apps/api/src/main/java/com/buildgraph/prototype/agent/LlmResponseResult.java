package com.buildgraph.prototype.agent;

public record LlmResponseResult(
        String text,
        LlmProvider provider,
        String model,
        String reasoningEffort,
        long latencyMs,
        Integer inputTokens,
        Integer outputTokens,
        Integer totalTokens
) {
}
