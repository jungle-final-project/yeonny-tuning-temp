package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_consents")
public class AgentConsentEntity extends PublicIdEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id")
    private Long deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false)
    private AgentConsentType consentType;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "accepted", nullable = false)
    private Boolean accepted;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AgentConsentEntity() {
    }
}
