package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_update_rollouts")
public class AgentUpdateRolloutEntity extends PublicIdEntity {
    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "version", nullable = false)
    private String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentRolloutStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AgentUpdateRolloutEntity() {
    }
}
