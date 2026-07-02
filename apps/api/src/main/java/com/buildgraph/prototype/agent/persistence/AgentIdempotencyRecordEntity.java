package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "agent_idempotency_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_agent_idempotency_scope",
                columnNames = {"agent_device_id", "request_method", "request_path", "idempotency_key"}
        )
)
public class AgentIdempotencyRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_device_id", nullable = false)
    private Long agentDeviceId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_method", nullable = false)
    private String requestMethod;

    @Column(name = "request_path", nullable = false)
    private String requestPath;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_content_type")
    private String responseContentType;

    @Column(name = "response_body")
    private String responseBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentIdempotencyStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected AgentIdempotencyRecordEntity() {
    }

    public AgentIdempotencyRecordEntity(
            Long agentDeviceId,
            String idempotencyKey,
            String requestMethod,
            String requestPath,
            String requestHash,
            Instant now,
            Instant expiresAt
    ) {
        this.agentDeviceId = agentDeviceId;
        this.idempotencyKey = idempotencyKey;
        this.requestMethod = requestMethod;
        this.requestPath = requestPath;
        this.requestHash = requestHash;
        this.status = AgentIdempotencyStatus.IN_PROGRESS;
        this.updatedAt = now;
        this.expiresAt = expiresAt;
    }

    public Long id() {
        return id;
    }

    public Long agentDeviceId() {
        return agentDeviceId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestMethod() {
        return requestMethod;
    }

    public String requestPath() {
        return requestPath;
    }

    public boolean hasRequestHash(String requestHash) {
        return Objects.equals(this.requestHash, requestHash);
    }

    public boolean isCompleted() {
        return status == AgentIdempotencyStatus.COMPLETED;
    }

    public Integer responseStatus() {
        return responseStatus;
    }

    public String responseContentType() {
        return responseContentType;
    }

    public String responseBody() {
        return responseBody;
    }

    public void complete(Integer responseStatus, String responseBody, String responseContentType, Instant now) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.responseContentType = responseContentType;
        this.status = AgentIdempotencyStatus.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }
}
