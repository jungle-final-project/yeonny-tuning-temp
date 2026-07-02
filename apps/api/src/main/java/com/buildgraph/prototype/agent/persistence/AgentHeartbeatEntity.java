package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_heartbeats")
public class AgentHeartbeatEntity extends PublicIdEntity {
    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "agent_version", nullable = false)
    private String agentVersion;

    @Column(name = "service_status", nullable = false)
    private String serviceStatus;

    @Column(name = "tray_status")
    private String trayStatus;

    @Column(name = "policy_version")
    private String policyVersion;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AgentHeartbeatEntity() {
    }
}
