package com.buildgraph.prototype.rag;

import com.buildgraph.prototype.agent.AgentPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RagVectorPolicy {
    private final boolean globalEnabled;
    private final boolean requirementParseEnabled;
    private final boolean buildRecommendEnabled;
    private final boolean asAnalyzeEnabled;
    private final boolean publicSearchEnabled;

    public RagVectorPolicy(
            @Value("${rag.vector.enabled:true}") boolean globalEnabled,
            @Value("${rag.vector.requirement-parse-enabled:${rag.vector.enabled:true}}") boolean requirementParseEnabled,
            @Value("${rag.vector.build-recommend-enabled:${rag.vector.enabled:true}}") boolean buildRecommendEnabled,
            @Value("${rag.vector.as-analyze-enabled:${rag.vector.enabled:true}}") boolean asAnalyzeEnabled,
            @Value("${rag.vector.public-search-enabled:${rag.vector.enabled:true}}") boolean publicSearchEnabled
    ) {
        this.globalEnabled = globalEnabled;
        this.requirementParseEnabled = requirementParseEnabled;
        this.buildRecommendEnabled = buildRecommendEnabled;
        this.asAnalyzeEnabled = asAnalyzeEnabled;
        this.publicSearchEnabled = publicSearchEnabled;
    }

    public static RagVectorPolicy allEnabled() {
        return new RagVectorPolicy(true, true, true, true, true);
    }

    public boolean enabledFor(AgentPurpose purpose) {
        if (!globalEnabled || purpose == null) {
            return false;
        }
        return switch (purpose) {
            case REQUIREMENT_PARSE -> requirementParseEnabled;
            case BUILD_RECOMMEND, BUILD_EXPLAIN -> buildRecommendEnabled;
            case AS_ANALYZE -> asAnalyzeEnabled;
        };
    }

    public boolean publicSearchEnabled() {
        return globalEnabled && publicSearchEnabled;
    }

    public boolean publicSearchEnabledFor(String purpose) {
        if (!publicSearchEnabled()) {
            return false;
        }
        if (purpose == null || purpose.isBlank()) {
            return true;
        }
        try {
            return enabledFor(AgentPurpose.valueOf(purpose.trim()));
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    public boolean globalEnabled() {
        return globalEnabled;
    }

    public boolean requirementParseEnabled() {
        return requirementParseEnabled;
    }

    public boolean buildRecommendEnabled() {
        return buildRecommendEnabled;
    }

    public boolean asAnalyzeEnabled() {
        return asAnalyzeEnabled;
    }
}
