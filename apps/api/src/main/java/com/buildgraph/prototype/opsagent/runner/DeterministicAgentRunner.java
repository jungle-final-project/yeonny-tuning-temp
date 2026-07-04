package com.buildgraph.prototype.opsagent.runner;

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

import com.buildgraph.prototype.part.ToolCheckService;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class DeterministicAgentRunner implements AgentRunner {
    private final AgentTraceService agentTraceService;
    private final AgentRagRetrievalService agentRagRetrievalService;
    private final ToolCheckService toolCheckService;

    public DeterministicAgentRunner(
            AgentTraceService agentTraceService,
            AgentRagRetrievalService agentRagRetrievalService,
            ToolCheckService toolCheckService
    ) {
        this.agentTraceService = agentTraceService;
        this.agentRagRetrievalService = agentRagRetrievalService;
        this.toolCheckService = toolCheckService;
    }

    @Override
    @Transactional
    public void run(String sessionId, AgentSessionRoot root, AgentRunProfile profile) {
        List<AgentRagEvidenceDraft> evidenceSet = agentRagRetrievalService.retrieveEvidenceSet(root, profile);
        evidenceSet.forEach(evidence -> agentTraceService.recordRagEvidence(sessionId, evidence));
        agentTraceService.advanceStatus(sessionId, AgentStatus.RAG_SEARCHED, "SYSTEM", "RAG evidence set retrieved for " + profile.purpose());

        for (AgentToolInvocationDraft draft : toolInvocations(root, profile)) {
            agentTraceService.recordToolInvocation(sessionId, draft);
        }
        agentTraceService.advanceStatus(sessionId, AgentStatus.TOOLS_CALLED, "SYSTEM", "tool invocations completed for " + profile.purpose());

        agentTraceService.updateSummary(sessionId, AgentRunTraceDrafts.deterministicSummary(profile, evidenceSet.get(0)));
        agentTraceService.advanceStatus(sessionId, AgentStatus.SUMMARY_READY, "SYSTEM", "summary generated for " + profile.summaryTarget());
        agentTraceService.advanceStatus(sessionId, AgentStatus.SUCCEEDED, "SYSTEM", "agent run completed");
    }

    /** Calls the real Tool service and falls back to seed drafts if validation cannot resolve parts. */
    private List<AgentToolInvocationDraft> toolInvocations(AgentSessionRoot root, AgentRunProfile profile) {
        try {
            return AgentRunTraceDrafts.toolInvocationsFromResults(
                    root,
                    profile,
                    toolCheckService.checkAgentTools(root.type().name(), root.publicId(), profile.toolNames())
            );
        } catch (RuntimeException ignored) {
            return AgentRunTraceDrafts.toolInvocations(root, profile);
        }
    }
}
