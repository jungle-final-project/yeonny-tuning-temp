package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_log_uploads")
public class AgentLogUploadEntity extends PublicIdEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "upload_job_id")
    private Long uploadJobId;

    @Column(name = "range_minutes", nullable = false)
    private Integer rangeMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LogUploadStatus status;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "storage_path")
    private String storagePath;

    @Column(name = "summary")
    private String summary;

    @Column(name = "incident_window", columnDefinition = "jsonb")
    private String incidentWindow;

    @Column(name = "range_started_at")
    private Instant rangeStartedAt;

    @Column(name = "range_ended_at")
    private Instant rangeEndedAt;

    @Column(name = "consent_accepted_at", nullable = false)
    private Instant consentAcceptedAt;

    @Column(name = "delete_after", nullable = false)
    private Instant deleteAfter;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AgentLogUploadEntity() {
    }
}
