package com.buildgraph.prototype.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

class AgentAsJpaMappingTest {
    @Test
    void entitiesMapToDbSchemaTables() {
        assertTable(AgentActivationTokenEntity.class, "agent_activation_tokens");
        assertTable(AgentDeviceEntity.class, "agent_devices");
        assertTable(AgentConsentEntity.class, "agent_consents");
        assertTable(AgentHeartbeatEntity.class, "agent_heartbeats");
        assertTable(AgentUpdatePolicyEntity.class, "agent_update_policies");
        assertTable(AgentUpdateRolloutEntity.class, "agent_update_rollouts");
        assertTable(AgentUploadJobEntity.class, "agent_upload_jobs");
        assertTable(AgentLogBundleEntity.class, "agent_log_bundles");
        assertTable(AgentDeleteRequestEntity.class, "agent_delete_requests");
        assertTable(AgentIdempotencyRecordEntity.class, "agent_idempotency_records");
        assertTable(AgentLogUploadEntity.class, "agent_log_uploads");
        assertTable(AsTicketEntity.class, "as_tickets");
        assertTable(RemoteSupportSessionEntity.class, "remote_support_sessions");
        assertTable(VisitSupportReservationEntity.class, "visit_support_reservations");
    }

    @Test
    void enumColumnsUseStringMapping() throws Exception {
        assertEnumColumn(AgentDeviceEntity.class, "status", AgentDeviceStatus.class, false);
        assertEnumColumn(AgentConsentEntity.class, "consentType", AgentConsentType.class, false);
        assertEnumColumn(AgentUpdatePolicyEntity.class, "channel", AgentUpdateChannel.class, false);
        assertEnumColumn(AgentUpdateRolloutEntity.class, "status", AgentRolloutStatus.class, false);
        assertEnumColumn(AgentUploadJobEntity.class, "status", AgentUploadJobStatus.class, false);
        assertEnumColumn(AgentDeleteRequestEntity.class, "scope", AgentDeleteRequestScope.class, false);
        assertEnumColumn(AgentDeleteRequestEntity.class, "status", AgentDeleteRequestStatus.class, false);
        assertEnumColumn(AgentIdempotencyRecordEntity.class, "status", AgentIdempotencyStatus.class, false);
        assertEnumColumn(AgentLogUploadEntity.class, "status", LogUploadStatus.class, false);
        assertEnumColumn(AsTicketEntity.class, "status", AsTicketStatus.class, false);
        assertEnumColumn(AsTicketEntity.class, "analysisStatus", AsAnalysisStatus.class, false);
        assertEnumColumn(AsTicketEntity.class, "reviewStatus", AsReviewStatus.class, false);
        assertEnumColumn(AsTicketEntity.class, "supportDecision", AsSupportDecision.class, true);
        assertEnumColumn(AsTicketEntity.class, "riskLevel", RiskLevel.class, true);
        assertEnumColumn(RemoteSupportSessionEntity.class, "provider", RemoteSupportProvider.class, false);
        assertEnumColumn(RemoteSupportSessionEntity.class, "status", RemoteSupportStatus.class, false);
        assertEnumColumn(VisitSupportReservationEntity.class, "timeSlot", VisitTimeSlot.class, false);
        assertEnumColumn(VisitSupportReservationEntity.class, "status", VisitReservationStatus.class, false);
    }

    @Test
    void enumsUseDbSchemaValues() {
        assertEnumValues(AgentDeviceStatus.class, "PENDING_REGISTERED", "ACTIVE", "UPDATE_REQUIRED", "BLOCKED", "REVOKED", "UNINSTALLED");
        assertEnumValues(AgentConsentType.class, "LOCAL_COLLECTION", "SERVER_UPLOAD", "QUALITY_IMPROVEMENT", "REMOTE_CONNECTION", "REMOTE_FULL_CONTROL", "HIGH_RISK_REMOTE_ACTION");
        assertEnumValues(AgentUpdateChannel.class, "STABLE");
        assertEnumValues(AgentRolloutStatus.class, "ACTIVE", "PAUSED", "ROLLED_BACK");
        assertEnumValues(AgentUploadJobStatus.class, "QUEUED", "UPLOADING", "UPLOADED", "FAILED_RETRYABLE", "FAILED_FINAL", "CANCELLED", "EXPIRED");
        assertEnumValues(AgentDeleteRequestScope.class, "LOCAL_LOGS", "SERVER_LOGS", "ALL");
        assertEnumValues(AgentDeleteRequestStatus.class, "REQUESTED", "PROCESSING", "COMPLETED", "FAILED");
        assertEnumValues(AgentIdempotencyStatus.class, "IN_PROGRESS", "COMPLETED");
        assertEnumValues(LogUploadStatus.class, "UPLOADED", "PROCESSING", "FAILED");
        assertEnumValues(AsTicketStatus.class, "OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED");
        assertEnumValues(AsAnalysisStatus.class, "NOT_STARTED", "QUEUED", "ANALYZING", "RULE_READY", "LLM_READY", "FAILED");
        assertEnumValues(AsReviewStatus.class, "NOT_REQUIRED", "REQUIRED", "IN_REVIEW", "APPROVED", "REJECTED");
        assertEnumValues(AsSupportDecision.class, "SELF_SOLVABLE", "REMOTE_POSSIBLE", "VISIT_REQUIRED", "REPAIR_OR_REPLACE", "NEEDS_MORE_INFO", "MONITOR_ONLY", "UNSUPPORTED");
        assertEnumValues(RiskLevel.class, "LOW", "MEDIUM", "HIGH");
        assertEnumValues(RemoteSupportProvider.class, "EXTERNAL_LINK", "ANYDESK", "TEAMVIEWER", "ZOOM", "GOOGLE_MEET");
        assertEnumValues(RemoteSupportStatus.class, "REQUESTED", "LINK_SENT", "IN_PROGRESS", "COMPLETED", "CANCELLED");
        assertEnumValues(VisitTimeSlot.class, "MORNING", "AFTERNOON", "EVENING");
        assertEnumValues(VisitReservationStatus.class, "REQUESTED", "SCHEDULED", "RESCHEDULE_REQUESTED", "VISIT_IN_PROGRESS", "COMPLETED", "CANCELLED");
    }

    @Test
    void repositoriesExposeJpaRepositoryForEachEntity() {
        assertJpaRepository(AgentActivationTokenRepository.class, AgentActivationTokenEntity.class);
        assertJpaRepository(AgentDeviceRepository.class, AgentDeviceEntity.class);
        assertJpaRepository(AgentConsentRepository.class, AgentConsentEntity.class);
        assertJpaRepository(AgentHeartbeatRepository.class, AgentHeartbeatEntity.class);
        assertJpaRepository(AgentUpdatePolicyRepository.class, AgentUpdatePolicyEntity.class);
        assertJpaRepository(AgentUpdateRolloutRepository.class, AgentUpdateRolloutEntity.class);
        assertJpaRepository(AgentUploadJobRepository.class, AgentUploadJobEntity.class);
        assertJpaRepository(AgentLogBundleRepository.class, AgentLogBundleEntity.class);
        assertJpaRepository(AgentDeleteRequestRepository.class, AgentDeleteRequestEntity.class);
        assertJpaRepository(AgentIdempotencyRecordRepository.class, AgentIdempotencyRecordEntity.class);
        assertJpaRepository(AgentLogUploadRepository.class, AgentLogUploadEntity.class);
        assertJpaRepository(AsTicketRepository.class, AsTicketEntity.class);
        assertJpaRepository(RemoteSupportSessionRepository.class, RemoteSupportSessionEntity.class);
        assertJpaRepository(VisitSupportReservationRepository.class, VisitSupportReservationEntity.class);
    }

    private static void assertTable(Class<?> entityType, String tableName) {
        assertThat(entityType.getAnnotation(Entity.class)).isNotNull();
        assertThat(entityType.getAnnotation(Table.class).name()).isEqualTo(tableName);
    }

    private static void assertEnumColumn(
            Class<?> entityType,
            String fieldName,
            Class<? extends Enum<?>> enumType,
            boolean nullable
    ) throws Exception {
        var field = entityType.getDeclaredField(fieldName);
        assertThat(field.getType()).isEqualTo(enumType);
        assertThat(field.getAnnotation(Enumerated.class).value()).isEqualTo(EnumType.STRING);
        assertThat(field.getAnnotation(Column.class).nullable()).isEqualTo(nullable);
    }

    private static void assertEnumValues(Class<? extends Enum<?>> enumType, String... values) {
        assertThat(Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList())
                .containsExactly(values);
    }

    private static void assertJpaRepository(Class<?> repositoryType, Class<?> entityType) {
        assertThat(repositoryType.getGenericInterfaces())
                .anySatisfy(type -> assertJpaRepositoryType(type, entityType));
    }

    private static void assertJpaRepositoryType(Type type, Class<?> entityType) {
        assertThat(type).isInstanceOf(ParameterizedType.class);
        ParameterizedType parameterized = (ParameterizedType) type;
        assertThat(parameterized.getRawType()).isEqualTo(JpaRepository.class);
        assertThat(parameterized.getActualTypeArguments()[0]).isEqualTo(entityType);
        assertThat(parameterized.getActualTypeArguments()[1]).isEqualTo(Long.class);
    }
}
