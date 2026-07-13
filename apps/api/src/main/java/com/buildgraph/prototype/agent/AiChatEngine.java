package com.buildgraph.prototype.agent;

public interface AiChatEngine {
    AiChatEngineResponse respond(AiChatEngineRequest request);

    default AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request) {
        return respond(request);
    }

    default AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request, String requestedAiProfile) {
        return respondLlmRequired(request);
    }

    default AiChatEngineResponse explainBuildAssessment(AiChatEngineRequest request, String requestedAiProfile) {
        return respondLlmRequired(request, requestedAiProfile);
    }

    QuoteRequirementAnalysisResult analyzeQuoteRequirement(QuoteRequirementAnalysisRequest request);
}
