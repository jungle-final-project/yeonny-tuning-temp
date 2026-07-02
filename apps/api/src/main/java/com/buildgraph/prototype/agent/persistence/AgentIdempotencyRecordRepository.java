package com.buildgraph.prototype.agent.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentIdempotencyRecordRepository extends JpaRepository<AgentIdempotencyRecordEntity, Long> {
    Optional<AgentIdempotencyRecordEntity> findByAgentDeviceIdAndRequestMethodAndRequestPathAndIdempotencyKey(
            Long agentDeviceId,
            String requestMethod,
            String requestPath,
            String idempotencyKey
    );
}
