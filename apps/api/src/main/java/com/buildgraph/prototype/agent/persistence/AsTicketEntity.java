package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "as_tickets")
public class AsTicketEntity extends PublicIdEntity {
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "log_upload_id")
    private Long logUploadId;

    @Column(name = "assigned_admin_id")
    private Long assignedAdminId;

    @Column(name = "symptom", nullable = false)
    private String symptom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AsTicketStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false)
    private AsAnalysisStatus analysisStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private AsReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "support_decision")
    private AsSupportDecision supportDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level")
    private RiskLevel riskLevel;

    @Column(name = "auto_response_allowed", nullable = false)
    private Boolean autoResponseAllowed;

    @Column(name = "cause_candidates", columnDefinition = "jsonb")
    private String causeCandidates;

    @Column(name = "upgrade_candidates", columnDefinition = "jsonb")
    private String upgradeCandidates;

    @Column(name = "admin_note")
    private String adminNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AsTicketEntity() {
    }
}
