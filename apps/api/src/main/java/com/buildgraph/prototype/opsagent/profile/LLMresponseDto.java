package com.buildgraph.prototype.opsagent.profile;

public record LLMresponseDto(
        String text,
        LlmProvider provider,
        String model,
        String reasoningEffort,
        long latencyMs
) {
}
