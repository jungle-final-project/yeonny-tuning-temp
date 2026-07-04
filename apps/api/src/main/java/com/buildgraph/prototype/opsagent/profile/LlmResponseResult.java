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
