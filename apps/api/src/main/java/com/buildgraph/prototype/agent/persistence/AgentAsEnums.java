package com.buildgraph.prototype.agent.persistence;

enum AgentDeviceStatus {
    PENDING_REGISTERED,
    ACTIVE,
    UPDATE_REQUIRED,
    BLOCKED,
    REVOKED,
    UNINSTALLED
}

enum AgentConsentType {
    LOCAL_COLLECTION,
    SERVER_UPLOAD,
    QUALITY_IMPROVEMENT
}

enum AgentUpdateChannel {
    STABLE
}

enum AgentRolloutStatus {
    ACTIVE,
    PAUSED,
    ROLLED_BACK
}

enum AgentUploadJobStatus {
    QUEUED,
    UPLOADING,
    UPLOADED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    CANCELLED,
    EXPIRED
}

enum AgentDeleteRequestScope {
    LOCAL_LOGS,
    SERVER_LOGS,
    ALL
}

enum AgentDeleteRequestStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED
}

enum AgentIdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}

enum LogUploadStatus {
    UPLOADED,
    PROCESSING,
    FAILED
}

enum AsTicketStatus {
    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    CANCELLED
}

enum AsAnalysisStatus {
    NOT_STARTED,
    QUEUED,
    ANALYZING,
    RULE_READY,
    LLM_READY,
    FAILED
}

enum AsReviewStatus {
    NOT_REQUIRED,
    REQUIRED,
    IN_REVIEW,
    APPROVED,
    REJECTED
}

enum AsSupportDecision {
    SELF_SOLVABLE,
    REMOTE_POSSIBLE,
    VISIT_REQUIRED,
    NEEDS_MORE_INFO
}

enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

enum RemoteSupportProvider {
    EXTERNAL_LINK,
    ANYDESK,
    TEAMVIEWER,
    ZOOM,
    GOOGLE_MEET
}

enum RemoteSupportStatus {
    REQUESTED,
    LINK_SENT,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}

enum VisitTimeSlot {
    MORNING,
    AFTERNOON,
    EVENING
}

enum VisitReservationStatus {
    REQUESTED,
    SCHEDULED,
    RESCHEDULE_REQUESTED,
    VISIT_IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
