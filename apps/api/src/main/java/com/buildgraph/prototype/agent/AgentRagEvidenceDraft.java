package com.buildgraph.prototype.agent;

import java.math.BigDecimal;
import java.util.Map;

public record AgentRagEvidenceDraft(
        String sourceId,
        String chunkText,
        String summary,
        BigDecimal score,
        Map<String, Object> metadata
) {
}
