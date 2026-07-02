package com.buildgraph.prototype.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentHeartbeatRepository extends JpaRepository<AgentHeartbeatEntity, Long> {
}
