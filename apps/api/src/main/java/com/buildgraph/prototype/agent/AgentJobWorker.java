package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.RabbitQueueConfig;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AgentJobWorker {
    private final AgentTraceService agentTraceService;
    private final AgentRunner agentRunner;

    public AgentJobWorker(AgentTraceService agentTraceService, AgentRunner agentRunner) {
        this.agentTraceService = agentTraceService;
        this.agentRunner = agentRunner;
    }

    @RabbitListener(queues = RabbitQueueConfig.AGENT_RUN_QUEUE)
    public void runAgent(Map<String, Object> payload) {
        String sessionId = requiredText(payload.get("sessionId"), "sessionId");
        AgentSessionRoot root = new AgentSessionRoot(
                AgentSessionRootType.valueOf(requiredText(payload.get("rootType"), "rootType")),
                requiredText(payload.get("rootPublicId"), "rootPublicId")
        );
        AgentRunProfile profile = new AgentRunProfile(
                AgentPurpose.valueOf(requiredText(payload.get("purpose"), "purpose")),
                stringList(payload.get("ragSourceTypes")),
                stringList(payload.get("toolNames")),
                requiredText(payload.get("summaryTarget"), "summaryTarget")
        );

        try {
            agentTraceService.advanceStatus(sessionId, AgentStatus.RUNNING, "WORKER", "agent worker accepted queued job");
            agentRunner.run(sessionId, root, profile);
        } catch (RuntimeException error) {
            agentTraceService.markFailed(sessionId, "WORKER", safeReason(error));
            throw error;
        }
    }

    private static String requiredText(Object value, String field) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Agent job payload missing " + field);
        }
        return text;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split(",")).stream()
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
