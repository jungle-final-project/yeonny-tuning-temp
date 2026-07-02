package com.buildgraph.prototype.agent;

public interface AiChatEngine {
    AiChatEngineResponse respond(AiChatEngineRequest request);

    default AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request) {
        return respond(request);
    }

    default AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request, String requestedAiProfile) {
        return respondLlmRequired(request);
    }

    QuoteRequirementAnalysisResult analyzeQuoteRequirement(QuoteRequirementAnalysisRequest request);
}
