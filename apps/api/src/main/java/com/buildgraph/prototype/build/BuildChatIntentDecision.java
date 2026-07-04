package com.buildgraph.prototype.build;

import java.util.List;

public record BuildChatIntentDecision(
        BuildChatIntent intent,
        String confidence,
        String sideEffectRisk,
        String targetCategory,
        String partQuery,
        String preferredPath,
        String cachePolicy,
        String semanticConstraintSignature,
        List<String> ambiguityReasons
) {
    public boolean isMutation() {
        return intent == BuildChatIntent.MUTATE_DRAFT_REMOVE
                || intent == BuildChatIntent.MUTATE_DRAFT_QUANTITY
                || intent == BuildChatIntent.MUTATE_DRAFT_REPLACE_EXACT
                || intent == BuildChatIntent.MUTATE_DRAFT_RECOMMEND;
    }

    public boolean isSemanticCacheEligible() {
        return "SEMANTIC_READ_ONLY".equals(cachePolicy)
                && "NONE".equals(sideEffectRisk)
                && semanticConstraintSignature != null
                && !semanticConstraintSignature.isBlank();
    }
}
