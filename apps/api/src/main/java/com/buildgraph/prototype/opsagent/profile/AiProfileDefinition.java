package com.buildgraph.prototype.opsagent.profile;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;
import com.buildgraph.prototype.opsagent.as.*;
import com.buildgraph.prototype.opsagent.profile.*;
import com.buildgraph.prototype.opsagent.trace.*;
import com.buildgraph.prototype.opsagent.runner.*;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;

public record AiProfileDefinition(
        AiProfile profile,
        LlmProvider provider,
        String model,
        String reasoningEffort,
        int ragTopK,
        String promptVersion,
        int maxOutputTokens,
        int recentMessageLimit,
        boolean includeEvidenceChunkText,
        boolean includeToolResultPayload,
        boolean useCompactPrompt
) {
}
