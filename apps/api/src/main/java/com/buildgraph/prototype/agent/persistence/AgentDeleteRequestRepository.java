package com.buildgraph.prototype.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentDeleteRequestRepository extends JpaRepository<AgentDeleteRequestEntity, Long> {
}
