package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pc_agent_diagnosis_results")
public class PcAgentDiagnosisResultEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "diagnosis_id", nullable = false, unique = true)
    private UUID diagnosisId;

    @Column(name = "result_id", nullable = false, unique = true)
    private String resultId;

    @Column(name = "diagnosis_type")
    private String diagnosisType;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "summary", nullable = false)
    private String summary;

    @Column(name = "resolution_type", nullable = false)
    private String resolutionType;

    @Column(name = "can_auto_recover", nullable = false)
    private Boolean canAutoRecover;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> evidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> findings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", nullable = false, columnDefinition = "jsonb")
    private List<Object> actions;

    @Column(name = "data_mode", nullable = false)
    private String dataMode;

    @Column(name = "scenario_id")
    private String scenarioId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected PcAgentDiagnosisResultEntity() {
    }

    public UUID diagnosisId() { return diagnosisId; }
    public String resultId() { return resultId; }
    public String diagnosisType() { return diagnosisType; }
    public String severity() { return severity; }
    public String title() { return title; }
    public String summary() { return summary; }
    public String resolutionType() { return resolutionType; }
    public Boolean canAutoRecover() { return canAutoRecover; }
    public List<Map<String, Object>> evidence() { return evidence; }
    public List<Map<String, Object>> findings() { return findings; }
    public List<Object> actions() { return actions; }
    public String dataMode() { return dataMode; }
    public String scenarioId() { return scenarioId; }
    public Map<String, Object> rawPayload() { return rawPayload; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
