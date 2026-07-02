package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.RabbitQueueConfig;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentJobPublisher {
    private final RabbitTemplate rabbitTemplate;

    public AgentJobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishRun(String sessionId, AgentSessionRoot root, AgentRunProfile profile) {
        rabbitTemplate.convertAndSend(
                RabbitQueueConfig.AGENT_EXCHANGE,
                RabbitQueueConfig.AGENT_RUN_ROUTING_KEY,
                payload(sessionId, root, profile)
        );
    }

    private static Map<String, Object> payload(String sessionId, AgentSessionRoot root, AgentRunProfile profile) {
        return MockData.map(
                "sessionId", sessionId,
                "rootType", root.type().name(),
                "rootPublicId", root.publicId(),
                "purpose", profile.purpose().name(),
                "ragSourceTypes", profile.ragSourceTypes(),
                "toolNames", profile.toolNames(),
                "summaryTarget", profile.summaryTarget()
        );
    }
}
