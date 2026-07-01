package com.buildgraph.prototype.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentDeviceRepository extends JpaRepository<AgentDeviceEntity, Long> {
}
