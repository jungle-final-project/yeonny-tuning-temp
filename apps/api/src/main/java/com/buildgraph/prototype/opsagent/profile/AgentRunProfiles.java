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

import java.util.List;

public final class AgentRunProfiles {
    private AgentRunProfiles() {
    }

    public static AgentRunProfile forRoot(AgentSessionRoot root) {
        return switch (root.purpose()) {
            case REQUIREMENT_PARSE -> requirementParse();
            case BUILD_RECOMMEND -> new AgentRunProfile(
                    AgentPurpose.BUILD_RECOMMEND,
                    List.of("PART_SPEC", "BENCHMARK", "INTERNAL_RULE"),
                    List.of("compatibility", "power", "size", "performance", "price"),
                    "build_recommendation"
            );
            case BUILD_EXPLAIN -> new AgentRunProfile(
                    AgentPurpose.BUILD_EXPLAIN,
                    List.of("PART_SPEC", "BENCHMARK", "GUIDE", "INTERNAL_RULE"),
                    List.of("performance", "price"),
                    "build_explanation"
            );
            case AS_ANALYZE -> new AgentRunProfile(
                    AgentPurpose.AS_ANALYZE,
                    List.of("TROUBLESHOOTING", "INTERNAL_RULE", "BENCHMARK", "PART_SPEC"),
                    List.of("performance", "compatibility", "price"),
                    "as_cause_and_upgrade_candidates"
            );
        };
    }

    public static AgentRunProfile requirementParse() {
        return new AgentRunProfile(
                AgentPurpose.REQUIREMENT_PARSE,
                List.of("GUIDE", "BENCHMARK", "INTERNAL_RULE"),
                List.of(),
                "requirement_structured_parse"
        );
    }
}
