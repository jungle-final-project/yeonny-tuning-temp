package com.buildgraph.prototype.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentUpdateRolloutRepository extends JpaRepository<AgentUpdateRolloutEntity, Long> {
}
