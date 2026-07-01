package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_devices")
public class AgentDeviceEntity extends PublicIdEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "activation_token_id")
    private Long activationTokenId;

    @Column(name = "device_fingerprint_hash", nullable = false)
    private String deviceFingerprintHash;

    @Column(name = "hostname_hash")
    private String hostnameHash;

    @Column(name = "agent_token_hash", nullable = false, unique = true)
    private String agentTokenHash;

    @Column(name = "registration_idempotency_key", nullable = false)
    private String registrationIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentDeviceStatus status;

    @Column(name = "os_version", nullable = false)
    private String osVersion;

    @Column(name = "agent_version", nullable = false)
    private String agentVersion;

    @Column(name = "policy_version")
    private String policyVersion;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "blocked_reason")
    private String blockedReason;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected AgentDeviceEntity() {
    }
}
