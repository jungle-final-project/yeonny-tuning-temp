package com.buildgraph.prototype.opsagent.runner;

import com.buildgraph.prototype.quoteagent.llm.AiChatClient;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.opsagent.profile.*;
import com.buildgraph.prototype.opsagent.trace.*;
import com.buildgraph.prototype.parts.tool.ToolService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AgentRunnerConfig {
    @Bean
    AgentRunner agentRunner(
            AgentTraceService agentTraceService,
            AgentRagRetrievalService agentRagRetrievalService,
            AiChatClient openAiResponsesClient,
            ToolService toolCheckService,
            LlmGenerationService llmGenerationService,
            AiProfileConfig aiProfileConfig,
            JdbcTemplate jdbcTemplate,
            @Value("${agent.runner.mode:deterministic}") String runnerMode
    ) {
        String normalizedMode = runnerMode == null ? "deterministic" : runnerMode.trim().toLowerCase();
        return switch (normalizedMode) {
            case "deterministic" -> new DeterministicAgentRunner(agentTraceService, agentRagRetrievalService, toolCheckService);
            case "llm" -> new LlmAgentRunner(
                    agentTraceService,
                    agentRagRetrievalService,
                    openAiResponsesClient,
                    toolCheckService,
                    llmGenerationService,
                    aiProfileConfig.defaultBuildChatProfile(),
                    jdbcTemplate
            );
            default -> throw new IllegalArgumentException("지원하지 않는 AGENT_RUNNER_MODE입니다: " + runnerMode);
        };
    }
}
