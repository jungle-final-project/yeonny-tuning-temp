package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pc_agent_diagnosis_events")
public class PcAgentDiagnosisEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "diagnosis_id", nullable = false)
    private UUID diagnosisId;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "progress_percent", nullable = false)
    private Integer progressPercent;

    @Column(name = "message")
    private String message;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PcAgentDiagnosisEventEntity() {
    }

    public UUID diagnosisId() {
        return diagnosisId;
    }

    public String eventId() {
        return eventId;
    }

    public String taskId() {
        return taskId;
    }

    public String eventType() {
        return eventType;
    }

    public String status() {
        return status;
    }

    public Integer progressPercent() {
        return progressPercent;
    }

    public String message() {
        return message;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Map<String, Object> rawPayload() {
        return rawPayload;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
