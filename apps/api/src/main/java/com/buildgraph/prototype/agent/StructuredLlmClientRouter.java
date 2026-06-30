package com.buildgraph.prototype.agent;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StructuredLlmClientRouter {
    private final OpenAiResponsesClient openAiResponsesClient;

    public StructuredLlmClientRouter(OpenAiResponsesClient openAiResponsesClient) {
        this.openAiResponsesClient = openAiResponsesClient;
    }

    public boolean isConfigured(LlmProvider provider) {
        return switch (provider) {
            case OPENAI -> openAiResponsesClient.isConfigured();
        };
    }

    public String missingKeyMessage(LlmProvider provider) {
        return switch (provider) {
            case OPENAI -> "OPENAI_API_KEY가 필요합니다.";
        };
    }

    public LlmResponseResult createStructuredJsonResult(
            AiProfileDefinition profile,
            String systemPrompt,
            String userPrompt,
            String schemaName,
            Map<String, Object> jsonSchema
    ) {
        return switch (profile.provider()) {
            case OPENAI -> openAiResponsesClient.createStructuredJsonResult(
                    systemPrompt,
                    userPrompt,
                    schemaName,
                    jsonSchema,
                    profile.model(),
                    profile.reasoningEffort(),
                    profile.maxOutputTokens()
            );
        };
    }
}
