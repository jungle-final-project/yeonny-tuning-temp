package com.buildgraph.prototype.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.buildgraph.prototype.agent.AgentPurpose;
import org.junit.jupiter.api.Test;

class RagVectorPolicyTest {
    @Test
    void pathSwitchesCanDisableOnlyRequirementParse() {
        RagVectorPolicy policy = new RagVectorPolicy(true, false, true, true, true);

        assertThat(policy.enabledFor(AgentPurpose.REQUIREMENT_PARSE)).isFalse();
        assertThat(policy.enabledFor(AgentPurpose.BUILD_RECOMMEND)).isTrue();
        assertThat(policy.enabledFor(AgentPurpose.BUILD_EXPLAIN)).isTrue();
        assertThat(policy.enabledFor(AgentPurpose.AS_ANALYZE)).isTrue();
        assertThat(policy.publicSearchEnabled()).isTrue();
    }

    @Test
    void globalSwitchDisablesEveryVectorPath() {
        RagVectorPolicy policy = new RagVectorPolicy(false, true, true, true, true);

        assertThat(policy.enabledFor(AgentPurpose.REQUIREMENT_PARSE)).isFalse();
        assertThat(policy.enabledFor(AgentPurpose.BUILD_RECOMMEND)).isFalse();
        assertThat(policy.enabledFor(AgentPurpose.BUILD_EXPLAIN)).isFalse();
        assertThat(policy.enabledFor(AgentPurpose.AS_ANALYZE)).isFalse();
        assertThat(policy.publicSearchEnabled()).isFalse();
    }

    @Test
    void publicSearchRespectsPurposeSpecificSwitchWhenPurposeIsKnown() {
        RagVectorPolicy policy = new RagVectorPolicy(true, false, true, true, true);

        assertThat(policy.publicSearchEnabledFor("REQUIREMENT_PARSE")).isFalse();
        assertThat(policy.publicSearchEnabledFor("BUILD_RECOMMEND")).isTrue();
        assertThat(policy.publicSearchEnabledFor(null)).isTrue();
    }
}
