package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_upload_jobs")
public class AgentUploadJobEntity extends PublicIdEntity {
    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AgentUploadJobStatus status;

    @Column(name = "range_started_at", nullable = false)
    private Instant rangeStartedAt;

    @Column(name = "range_ended_at", nullable = false)
    private Instant rangeEndedAt;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected AgentUploadJobEntity() {
    }
}
