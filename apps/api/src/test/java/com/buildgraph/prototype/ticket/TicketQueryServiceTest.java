package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class TicketQueryServiceTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final TicketQueryService service = new TicketQueryService(jdbcTemplate);
    private final CurrentUserService.CurrentUser admin = new CurrentUserService.CurrentUser(
            1L,
            "admin-public-id",
            "admin@example.com",
            "Admin",
            "ADMIN",
            null
    );
    private final CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
            20L,
            "user-public-id",
            "user@example.com",
            "User",
            "USER",
            null
    );

    @Test
    void deleteSoftDeletesTicketClosesRelatedSupportAndWritesAuditLog() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "status", "IN_PROGRESS",
                        "support_chat_room_id", "room-public-id"
                )));
        when(jdbcTemplate.queryForList(contains("UPDATE as_tickets"), eq(100L)))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "deleted_at", "2026-07-16T06:00:00Z"
                )));

        Map<String, Object> response = service.delete("ticket-public-id", admin);

        assertThat(response).containsEntry("id", "ticket-public-id");
        assertThat(response).containsEntry("deleted", true);
        assertThat(response).containsEntry("deletedAt", "2026-07-16T06:00:00Z");
        assertThat(response).containsEntry("supportChatRoomId", "room-public-id");
        verify(jdbcTemplate).update(contains("UPDATE support_chat_rooms"), eq(100L));
        verify(jdbcTemplate).update(contains("UPDATE as_chat_sessions"), eq(100L));
        verify(jdbcTemplate).update(contains("UPDATE remote_support_sessions"), eq(100L));
        verify(jdbcTemplate).update(contains("UPDATE visit_support_reservations"), eq(100L));
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("AS_TICKET_DELETED") && sql.contains("softDelete")),
                eq(1L),
                eq("ticket-public-id"),
                eq("IN_PROGRESS")
        );
    }

    @Test
    void deleteRejectsMissingOrAlreadyDeletedTicket() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("missing-ticket")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.delete("missing-ticket", admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.NOT_FOUND));
    }

    @Test
    void userTicketLookupRestrictsTicketByOwner() {
        when(jdbcTemplate.queryForList(contains("SELECT t.public_id::text AS id"), eq("ticket-public-id"), eq(20L)))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "user_id", "user-public-id",
                        "user_email", "user@example.com",
                        "user_name", "User",
                        "status", "OPEN",
                        "analysis_status", "RULE_READY",
                        "review_status", "APPROVED",
                        "support_decision", "REMOTE_POSSIBLE",
                        "risk_level", "MEDIUM",
                        "auto_response_allowed", false,
                        "symptom", "GPU temperature spike",
                        "request_number", "AS-20260714-0001",
                        "request_type", "PHYSICAL_INSPECTION",
                        "diagnosis_id", "9a0e3c21-6648-41e7-a88e-17be1761b806",
                        "diagnosis_mode", "LIVE",
                        "diagnosis_title", "GPU 냉각 계통 이상 가능성",
                        "diagnosis_summary", "고온과 열 제한 징후가 함께 감지되었습니다.",
                        "diagnosis_evidence", "[{\"component\":\"gpu\",\"metricType\":\"temperature\",\"value\":96,\"unit\":\"°C\"}]",
                        "diagnosis_result", "{\"recommendedActions\":[\"그래픽 드라이버 재설치\"]}",
                        "diagnosis_events", "[{\"eventId\":\"event-1\",\"message\":\"그래픽 장치 확인 완료\",\"progressPercent\":100}]",
                        "diagnosed_at", "2026-07-14T01:02:03Z",
                        "log_upload_id", "log-upload-public-id",
                        "assigned_admin_id", "admin-public-id",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "admin_note", "Remote support link sent.",
                        "access_code", "123456789"
                )));

        Map<String, Object> response = service.ticket("ticket-public-id", user);

        assertThat(response.get("id")).isEqualTo("ticket-public-id");
        assertThat(response.get("supportDecision")).isEqualTo("REMOTE_POSSIBLE");
        assertThat(response.get("requestNumber")).isEqualTo("AS-20260714-0001");
        assertThat(response.get("requestType")).isEqualTo("PHYSICAL_INSPECTION");
        assertThat(response.get("userEmail")).isEqualTo("user@example.com");
        assertThat(response.get("userName")).isEqualTo("User");
        assertThat(response.get("diagnosisId")).isEqualTo("9a0e3c21-6648-41e7-a88e-17be1761b806");
        assertThat(response.get("diagnosisMode")).isEqualTo("LIVE");
        assertThat(response.get("diagnosisTitle")).isEqualTo("GPU 냉각 계통 이상 가능성");
        assertThat(response.get("diagnosisSummary")).isEqualTo("고온과 열 제한 징후가 함께 감지되었습니다.");
        assertThat(response.get("diagnosisEvidence")).isEqualTo(List.of(Map.of(
                "component", "gpu",
                "metricType", "temperature",
                "value", 96,
                "unit", "°C"
        )));
        assertThat(response.get("diagnosedAt")).isEqualTo("2026-07-14T01:02:03Z");
        assertThat(response).doesNotContainKey("accessCode");
        assertThat(response.get("diagnosisResult")).isEqualTo(Map.of(
                "recommendedActions", List.of("그래픽 드라이버 재설치")
        ));
        assertThat(response.get("diagnosisEvents")).isEqualTo(List.of(Map.of(
                "eventId", "event-1",
                "message", "그래픽 장치 확인 완료",
                "progressPercent", 100
        )));
        verify(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("t.user_id = ?")
                        && sql.contains("user_owner.email AS user_email")
                        && sql.contains("ORDER BY event.occurred_at ASC, event.id ASC")),
                eq("ticket-public-id"),
                eq(20L)
        );
    }

    @Test
    void updateStoresApprovedRemoteDecisionLinkAndAuditLog() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "NEEDS_MORE_INFO"
                )))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "status", "IN_PROGRESS",
                        "analysis_status", "RULE_READY",
                        "review_status", "APPROVED",
                        "support_decision", "REMOTE_POSSIBLE",
                        "risk_level", "HIGH",
                        "auto_response_allowed", true,
                        "diagnostic_accuracy", "ACCURATE",
                        "symptom", "GPU temperature spike",
                        "log_upload_id", "log-upload-public-id",
                        "assigned_admin_id", "admin-public-id",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "admin_note", "Remote support link sent.",
                        "remote_support_link", "https://support.example/session/1",
                        "remote_support_status", "LINK_SENT"
                )));

        Map<String, Object> response = service.update("ticket-public-id", MockData.map(
                "status", "IN_PROGRESS",
                "supportDecision", "REMOTE_POSSIBLE",
                "reviewStatus", "APPROVED",
                "riskLevel", "HIGH",
                "autoResponseAllowed", true,
                "diagnosticAccuracy", "ACCURATE",
                "adminNote", "Remote support link sent.",
                "remoteSupportLink", "https://support.example/session/1"
        ), admin);

        assertThat(response.get("supportDecision")).isEqualTo("REMOTE_POSSIBLE");
        assertThat(response.get("reviewStatus")).isEqualTo("APPROVED");
        assertThat(response.get("riskLevel")).isEqualTo("HIGH");
        assertThat(response.get("autoResponseAllowed")).isEqualTo(true);
        assertThat(response.get("diagnosticAccuracy")).isEqualTo("ACCURATE");
        assertThat(response.get("remoteSupportLink")).isEqualTo("https://support.example/session/1");
        assertThat(response.get("remoteSupportStatus")).isEqualTo("LINK_SENT");
        assertThat(response.get("visitSupportRequired")).isEqualTo(false);

        verify(jdbcTemplate).update(contains("UPDATE as_tickets"), eq("IN_PROGRESS"), eq("ticket-public-id"));
        verify(jdbcTemplate).update(
                contains("support_decision"),
                eq("REMOTE_POSSIBLE"),
                eq("APPROVED"),
                eq("HIGH"),
                eq(true),
                eq("ticket-public-id")
        );
        verify(jdbcTemplate).update(contains("diagnostic_accuracy"), eq("ACCURATE"), eq("ticket-public-id"));
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("UPDATE remote_support_sessions")
                        && sql.contains("NOT EXISTS (SELECT 1 FROM updated)")
                        && sql.contains("'IN_PROGRESS'")),
                eq("https://support.example/session/1"),
                eq(1L),
                eq("ticket-public-id")
        );
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("INSERT INTO admin_audit_logs")
                        && sql.contains("CAST(? AS text)")),
                eq(1L),
                eq("ticket-public-id"),
                eq("OPEN"),
                eq("IN_PROGRESS"),
                eq("OPEN"),
                eq("REMOTE_POSSIBLE"),
                eq("NEEDS_MORE_INFO"),
                eq("APPROVED"),
                isNull(),
                isNull(),
                isNull()
        );
    }

    @Test
    void updateCompletesActiveRemoteSupportWhenTicketIsResolved() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "IN_PROGRESS",
                        "review_status", "APPROVED",
                        "support_decision", "REMOTE_POSSIBLE"
                )))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "status", "RESOLVED",
                        "analysis_status", "RULE_READY",
                        "review_status", "APPROVED",
                        "support_decision", "REMOTE_POSSIBLE",
                        "risk_level", "MEDIUM",
                        "symptom", "driver issue",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "remote_support_status", "COMPLETED"
                )));

        Map<String, Object> response = service.update("ticket-public-id", MockData.map(
                "status", "RESOLVED"
        ), admin);

        assertThat(response.get("status")).isEqualTo("RESOLVED");
        assertThat(response.get("remoteSupportStatus")).isEqualTo("COMPLETED");
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("UPDATE remote_support_sessions")
                        && sql.contains("ended_at = COALESCE")),
                eq("COMPLETED"),
                eq("TICKET_RESOLVED"),
                eq("ticket-public-id")
        );
    }

    @Test
    void updateRejectsRemoteLinkBeforeApprovedRemoteDecision() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "NEEDS_MORE_INFO"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "remoteSupportLink", "https://support.example/session/not-approved"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void updateRejectsVisitBookingWithoutApprovedVisitDecision() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "REMOTE_POSSIBLE"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "reviewStatus", "APPROVED",
                "visitSupportRequired", true
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void updateRejectsAutoResponseBeforeApproval() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "REMOTE_POSSIBLE"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "autoResponseAllowed", true
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void updateRejectsRemoteAndVisitBookingInOneDecision() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "NEEDS_MORE_INFO"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "supportDecision", "REMOTE_POSSIBLE",
                "reviewStatus", "APPROVED",
                "remoteSupportLink", "https://support.example/session/1",
                "visitSupportRequired", true
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void updateBlocksRemoteOrVisitReservationForUnsupportedTicketWithoutExceptionApproval() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "UNSUPPORTED"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "remoteSupportLink", "https://support.example/session/unsupported"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void updateAllowsUnsupportedExceptionTransitionWhenRequiredFieldsAreRecorded() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "UNSUPPORTED"
                )))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "status", "OPEN",
                        "analysis_status", "RULE_READY",
                        "review_status", "APPROVED",
                        "support_decision", "REMOTE_POSSIBLE",
                        "risk_level", "LOW",
                        "auto_response_allowed", false,
                        "symptom", "ISP router issue",
                        "log_upload_id", "log-upload-public-id",
                        "assigned_admin_id", "admin-public-id",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "exception_approval_reason", "Customer paid exception support.",
                        "exception_responsibility_scope", "PC settings only.",
                        "exception_user_message", "Router hardware remains out of scope."
                )));

        Map<String, Object> response = service.update("ticket-public-id", MockData.map(
                "supportDecision", "REMOTE_POSSIBLE",
                "reviewStatus", "APPROVED",
                "exceptionApprovalReason", "Customer paid exception support.",
                "exceptionResponsibilityScope", "PC settings only.",
                "exceptionUserMessage", "Router hardware remains out of scope.",
                "remoteSupportLink", "https://support.example/session/exception"
        ), admin);

        assertThat(response.get("supportDecision")).isEqualTo("REMOTE_POSSIBLE");
        assertThat(response.get("exceptionApprovalReason")).isEqualTo("Customer paid exception support.");

        verify(jdbcTemplate).update(
                contains("exception_approval_reason"),
                eq("Customer paid exception support."),
                eq("PC settings only."),
                eq("Router hardware remains out of scope."),
                eq(1L),
                eq("ticket-public-id")
        );
        verify(jdbcTemplate).update(contains("remote_support_sessions"), eq("https://support.example/session/exception"), eq(1L), eq("ticket-public-id"));
    }

    @Test
    void updateKeepsLegacySupportDecisionForBackwardCompatibility() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "NEEDS_MORE_INFO"
                )))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "status", "OPEN",
                        "analysis_status", "RULE_READY",
                        "review_status", "APPROVED",
                        "support_decision", "REPAIR_OR_REPLACE",
                        "risk_level", "HIGH",
                        "auto_response_allowed", false,
                        "symptom", "SMART critical error",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]"
                )));

        Map<String, Object> response = service.update("ticket-public-id", MockData.map(
                "supportDecision", "REPAIR_OR_REPLACE"
        ), admin);

        assertThat(response.get("supportDecision")).isEqualTo("REPAIR_OR_REPLACE");
        verify(jdbcTemplate).update(
                contains("support_decision"),
                eq("REPAIR_OR_REPLACE"),
                eq("APPROVED"),
                isNull(),
                isNull(),
                eq("ticket-public-id")
        );
    }

    @Test
    void requestRemoteSupportCreatesRequestedSessionForTicketOwner() {
        when(jdbcTemplate.queryForList(contains("SELECT t.id AS internal_id"), eq("ticket-public-id"), eq(20L)))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "device_id", 10L
                )));
        when(jdbcTemplate.queryForObject(contains("remote_support_sessions"), eq(Integer.class), eq(100L)))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(contains("SELECT t.public_id::text AS id"), eq("ticket-public-id"), eq(20L)))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "status", "OPEN",
                        "analysis_status", "RULE_READY",
                        "review_status", "REQUIRED",
                        "support_decision", "REMOTE_POSSIBLE",
                        "risk_level", "MEDIUM",
                        "auto_response_allowed", false,
                        "symptom", "driver issue",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "remote_support_status", "REQUESTED"
                )));

        Map<String, Object> response = service.requestRemoteSupport("ticket-public-id", MockData.map(
                "reason", "드라이버 오류를 원격으로 확인해 주세요.",
                "contactPhone", "010-1234-5678"
        ), user);

        assertThat(response.get("remoteSupportStatus")).isEqualTo("REQUESTED");
        verify(jdbcTemplate).update(
                contains("remote_support_sessions"),
                eq(100L),
                eq(10L),
                eq(20L),
                eq("드라이버 오류를 원격으로 확인해 주세요."),
                eq("010-1234-5678")
        );
    }

    @Test
    void requestRemoteSupportRejectsDuplicateActiveRequest() {
        when(jdbcTemplate.queryForList(contains("SELECT t.id AS internal_id"), eq("ticket-public-id"), eq(20L)))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "device_id", 10L
                )));
        when(jdbcTemplate.queryForObject(contains("remote_support_sessions"), eq(Integer.class), eq(100L)))
                .thenReturn(1);

        assertThatThrownBy(() -> service.requestRemoteSupport("ticket-public-id", MockData.map(
                "reason", "원격지원이 필요합니다."
        ), user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void submitFeedbackStoresRatingAndCommentForTicketOwner() {
        when(jdbcTemplate.queryForList(contains("UPDATE as_tickets"), eq(5), eq("원격지원 후 해결됐습니다."), eq("ticket-public-id"), eq(20L)))
                .thenReturn(List.of(MockData.map("id", "ticket-public-id")));
        when(jdbcTemplate.queryForList(contains("SELECT t.public_id::text AS id"), eq("ticket-public-id"), eq(20L)))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "status", "RESOLVED",
                        "analysis_status", "RULE_READY",
                        "review_status", "APPROVED",
                        "support_decision", "REMOTE_POSSIBLE",
                        "risk_level", "LOW",
                        "auto_response_allowed", false,
                        "symptom", "driver issue",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "feedback_rating", 5,
                        "feedback_comment", "원격지원 후 해결됐습니다."
                )));

        Map<String, Object> response = service.submitFeedback("ticket-public-id", MockData.map(
                "rating", 5,
                "comment", "원격지원 후 해결됐습니다."
        ), user);

        assertThat(response.get("feedbackRating")).isEqualTo(5);
        assertThat(response.get("feedbackComment")).isEqualTo("원격지원 후 해결됐습니다.");
    }

    @Test
    void updateStoresAssignedAdminPublicId() {
        String assignedAdminId = "00000000-0000-4000-8000-000000000001";
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "NEEDS_MORE_INFO"
                )))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "status", "OPEN",
                        "analysis_status", "RULE_READY",
                        "review_status", "REQUIRED",
                        "support_decision", "NEEDS_MORE_INFO",
                        "risk_level", "MEDIUM",
                        "symptom", "GPU temperature spike",
                        "assigned_admin_id", assignedAdminId,
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]"
                )));
        when(jdbcTemplate.queryForList(contains("FROM users"), eq(assignedAdminId)))
                .thenReturn(List.of(MockData.map("id", 1L)));

        Map<String, Object> response = service.update("ticket-public-id", MockData.map(
                "assignedAdminId", assignedAdminId
        ), admin);

        assertThat(response.get("assignedAdminId")).isEqualTo(assignedAdminId);
        verify(jdbcTemplate).update(contains("assigned_admin_id"), eq(1L), eq("ticket-public-id"));
    }

    @Test
    void updateRejectsInvalidAssignedAdminId() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "assignedAdminId", "not-a-uuid"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateRejectsInvalidTicketStatusTransition() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "RESOLVED"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "status", "IN_PROGRESS"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void updateRejectsUnknownSupportDecision() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "supportDecision", "QUICK_ASSIST"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateRejectsInvalidAutoResponseAllowedValue() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "autoResponseAllowed", "not-a-boolean"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateRejectsInvalidRemoteSupportLink() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "remoteSupportLink", "javascript:alert(1)"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.BAD_REQUEST));
    }

    @Test
    void updateRejectsMissingTicketId() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("missing-ticket-id")))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.update("missing-ticket-id", MockData.map(
                "supportDecision", "REMOTE_POSSIBLE"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.NOT_FOUND));
    }

    @Test
    void assignToCurrentAdminClaimsUnassignedTicketAndStartsReview() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("ticket-public-id")))
                .thenReturn(List.of(actionRow("OPEN", "REQUIRED", "NEEDS_MORE_INFO")));
        when(jdbcTemplate.queryForList(contains("LEFT JOIN agent_log_uploads"), eq("ticket-public-id")))
                .thenReturn(List.of(ticketResultRow("ASSIGNED", "IN_REVIEW", "NEEDS_MORE_INFO", admin.id(), null)));

        Map<String, Object> response = service.assignToCurrentAdmin("ticket-public-id", admin);

        assertThat(response).containsEntry("status", "ASSIGNED");
        assertThat(response).containsEntry("reviewStatus", "IN_REVIEW");
        assertThat(response).containsEntry("assignedAdminId", admin.id());
        assertThat(response).containsKey("diagnosisResult");
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("assigned_admin_id") && sql.contains("status = CASE")),
                eq(1L),
                eq(100L)
        );
    }

    @Test
    void assignToCurrentAdminRejectsTicketOwnedByAnotherAdmin() {
        Map<String, Object> current = actionRow("ASSIGNED", "IN_REVIEW", "NEEDS_MORE_INFO");
        current.put("assigned_admin_id", 99L);
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("ticket-public-id")))
                .thenReturn(List.of(current));

        assertThatThrownBy(() -> service.assignToCurrentAdmin("ticket-public-id", admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void approveRemoteSupportAllowsAdminToSwitchAnActionableTicketToRemoteTriage() {
        Map<String, Object> current = actionRow("OPEN", "REQUIRED", "VISIT_REQUIRED");
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("ticket-public-id")))
                .thenReturn(List.of(current));
        when(jdbcTemplate.queryForList(contains("LEFT JOIN agent_log_uploads"), eq("ticket-public-id")))
                .thenReturn(List.of(ticketResultRow("IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE", admin.id(), "원격 확인 승인")));

        Map<String, Object> response = service.approveRemoteSupport("ticket-public-id", "원격 확인 승인", admin);

        assertThat(response).containsEntry("status", "IN_PROGRESS");
        assertThat(response).containsEntry("reviewStatus", "APPROVED");
        assertThat(response).containsEntry("supportDecision", "REMOTE_POSSIBLE");
        assertThat(response).containsEntry("assignedAdminId", admin.id());
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("reviewed_at = now()") && sql.contains("support_decision = 'REMOTE_POSSIBLE'")),
                eq(1L),
                eq("원격 확인 승인"),
                eq(100L)
        );
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("'CHROME_REMOTE_DESKTOP'") && sql.contains("'WAITING_FOR_CODE'")),
                eq("ticket-public-id"),
                eq(1L),
                eq(1L)
        );
    }

    @Test
    void approveRemoteSupportRejectsTicketWithOutOfScopeBlockingFactor() {
        Map<String, Object> current = actionRow("OPEN", "REQUIRED", "VISIT_REQUIRED");
        current.put("support_routing", "{\"blockingFactors\":[\"PHYSICAL_DAMAGE_POLICY_REQUIRED\"]}");
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("ticket-public-id")))
                .thenReturn(List.of(current));

        assertThatThrownBy(() -> service.approveRemoteSupport("ticket-public-id", null, admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void approveRemoteSupportRejectsCompletedTicket() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("ticket-public-id")))
                .thenReturn(List.of(actionRow("RESOLVED", "APPROVED", "REMOTE_POSSIBLE")));

        assertThatThrownBy(() -> service.approveRemoteSupport("ticket-public-id", null, admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void requestMoreInformationRequiresReasonAndKeepsDecisionInReview() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("ticket-public-id")))
                .thenReturn(List.of(actionRow("OPEN", "REQUIRED", "VISIT_REQUIRED")));
        when(jdbcTemplate.queryForList(contains("LEFT JOIN agent_log_uploads"), eq("ticket-public-id")))
                .thenReturn(List.of(ticketResultRow("IN_PROGRESS", "IN_REVIEW", "NEEDS_MORE_INFO", admin.id(), "재현 시각을 알려 주세요.")));

        Map<String, Object> response = service.requestMoreInformation("ticket-public-id", "재현 시각을 알려 주세요.", admin);

        assertThat(response).containsEntry("status", "IN_PROGRESS");
        assertThat(response).containsEntry("reviewStatus", "IN_REVIEW");
        assertThat(response).containsEntry("supportDecision", "NEEDS_MORE_INFO");
        assertThat(response).containsEntry("adminNote", "재현 시각을 알려 주세요.");
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("support_decision = 'NEEDS_MORE_INFO'") && sql.contains("reviewed_at = NULL")),
                eq(1L),
                eq("재현 시각을 알려 주세요."),
                eq(100L)
        );

        assertThatThrownBy(() -> service.requestMoreInformation("ticket-public-id", " ", admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.BAD_REQUEST));
    }

    @Test
    void duplicateRemoteApprovalIsIdempotent() {
        Map<String, Object> current = actionRow("IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE");
        current.put("assigned_admin_id", 1L);
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq("ticket-public-id")))
                .thenReturn(List.of(current));
        when(jdbcTemplate.queryForList(contains("LEFT JOIN agent_log_uploads"), eq("ticket-public-id")))
                .thenReturn(List.of(ticketResultRow("IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE", admin.id(), "승인 완료")));

        Map<String, Object> response = service.approveRemoteSupport("ticket-public-id", "승인 완료", admin);

        assertThat(response).containsEntry("reviewStatus", "APPROVED");
        verify(jdbcTemplate, never()).update(
                argThat(sql -> sql.contains("reviewed_at = now()")),
                any(),
                any(),
                any()
        );
    }

    @Test
    void approvedTicketOwnerCanRegisterAndReplaceRemoteAccessCode() {
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id"),
                eq(20L)
        )).thenReturn(
                List.of(remoteSupportRow("WAITING_FOR_CODE", null, 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE")),
                List.of(remoteSupportRow("CODE_READY", "123456789", 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE"))
        );

        Map<String, Object> response = service.registerRemoteAccessCode("ticket-public-id", "123-456 789", user);

        assertThat(response).containsEntry("status", "CODE_READY");
        assertThat(response).containsEntry("accessCodeRegistered", true);
        assertThat(response).doesNotContainKey("accessCode");
        assertThat(response).doesNotContainKey("maskedAccessCode");
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("status = 'CODE_READY'") && sql.contains("access_code = ?")),
                eq("123456789"),
                eq(500L)
        );
    }

    @Test
    void remoteAccessCodeRegistrationRequiresApprovalAndTicketOwnership() {
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id"),
                eq(20L)
        )).thenReturn(List.of(remoteSupportRow("WAITING_FOR_CODE", null, 1L, "IN_PROGRESS", "REQUIRED", "VISIT_REQUIRED")));

        assertThatThrownBy(() -> service.registerRemoteAccessCode("ticket-public-id", "123456", user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));

        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("other-ticket"),
                eq(20L)
        )).thenReturn(List.of());
        assertThatThrownBy(() -> service.registerRemoteAccessCode("other-ticket", "123456", user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.NOT_FOUND));
    }

    @Test
    void completedTicketRejectsRemoteAccessCodeRegistration() {
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id"),
                eq(20L)
        )).thenReturn(List.of(remoteSupportRow("WAITING_FOR_CODE", null, 1L, "RESOLVED", "APPROVED", "REMOTE_POSSIBLE")));

        assertThatThrownBy(() -> service.registerRemoteAccessCode("ticket-public-id", "123456", user))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void assignedAdminCanStartCodeReadyRemoteSupport() {
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id")
        )).thenReturn(
                List.of(remoteSupportRow("CODE_READY", "123456", 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE")),
                List.of(remoteSupportRow("IN_PROGRESS", "123456", 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE"))
        );

        Map<String, Object> response = service.startRemoteSupport("ticket-public-id", admin);

        assertThat(response).containsEntry("status", "IN_PROGRESS");
        assertThat(response).containsEntry("maskedAccessCode", "•• 3456");
        assertThat(response).doesNotContainKey("accessCode");
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("status = 'IN_PROGRESS'") && sql.contains("started_at = COALESCE")),
                eq(500L)
        );
    }

    @Test
    void remoteSupportCannotStartWithoutCode() {
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id")
        )).thenReturn(List.of(remoteSupportRow("WAITING_FOR_CODE", null, 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE")));

        assertThatThrownBy(() -> service.startRemoteSupport("ticket-public-id", admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void completingRemoteSupportClearsAccessCode() {
        Map<String, Object> completed = remoteSupportRow("COMPLETED", null, 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE");
        completed.put("remote_support_completed_at", "2026-07-19T03:00:00Z");
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id")
        )).thenReturn(
                List.of(remoteSupportRow("IN_PROGRESS", "123456", 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE")),
                List.of(completed)
        );

        Map<String, Object> response = service.completeRemoteSupport("ticket-public-id", admin);

        assertThat(response).containsEntry("status", "COMPLETED");
        assertThat(response).containsEntry("accessCodeRegistered", false);
        assertThat(response).containsEntry("maskedAccessCode", null);
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("status = 'COMPLETED'") && sql.contains("access_code = NULL")),
                eq(500L)
        );
    }

    @Test
    void duplicateRemoteSupportStartAndCompleteAreIdempotent() {
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id")
        )).thenReturn(List.of(remoteSupportRow("IN_PROGRESS", "123456", 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE")));

        assertThat(service.startRemoteSupport("ticket-public-id", admin)).containsEntry("status", "IN_PROGRESS");
        verify(jdbcTemplate, never()).update(argThat(sql -> sql.contains("started_at = COALESCE")), any(Object[].class));

        org.mockito.Mockito.reset(jdbcTemplate);
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id")
        )).thenReturn(List.of(remoteSupportRow("COMPLETED", null, 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE")));

        assertThat(service.completeRemoteSupport("ticket-public-id", admin)).containsEntry("status", "COMPLETED");
        verify(jdbcTemplate, never()).update(argThat(sql -> sql.contains("ended_reason = COALESCE")), any(Object[].class));
    }

    @Test
    void adminAccessCodeLookupRequiresAssignedAdminAndDoesNotAffectTicketDto() {
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id")
        )).thenReturn(List.of(remoteSupportRow("CODE_READY", "123456", 99L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE")));

        assertThatThrownBy(() -> service.remoteAccessCodeForAdmin("ticket-public-id", admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void completedRemoteSupportDoesNotExposeAccessCode() {
        when(jdbcTemplate.queryForList(
                contains("rs.access_code AS remote_access_code"),
                eq("ticket-public-id")
        )).thenReturn(List.of(remoteSupportRow("COMPLETED", null, 1L, "IN_PROGRESS", "APPROVED", "REMOTE_POSSIBLE")));

        assertThatThrownBy(() -> service.remoteAccessCodeForAdmin("ticket-public-id", admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    private static Map<String, Object> actionRow(String status, String reviewStatus, String supportDecision) {
        return MockData.map(
                "internal_id", 100L,
                "id", "ticket-public-id",
                "status", status,
                "review_status", reviewStatus,
                "support_decision", supportDecision,
                "request_type", "PHYSICAL_INSPECTION",
                "diagnosis_result", "{}",
                "support_routing", "{}"
        );
    }

    private static Map<String, Object> ticketResultRow(
            String status,
            String reviewStatus,
            String supportDecision,
            String assignedAdminId,
            String adminNote
    ) {
        Map<String, Object> row = MockData.map(
                "id", "ticket-public-id",
                "status", status,
                "analysis_status", "RULE_READY",
                "review_status", reviewStatus,
                "support_decision", supportDecision,
                "risk_level", "LOW",
                "auto_response_allowed", false,
                "symptom", "원격 점검 요청",
                "assigned_admin_id", assignedAdminId,
                "cause_candidates", "[]",
                "upgrade_candidates", "[]",
                "diagnosis_result", "{\"resolutionType\":\"REMOTE_SUPPORT\"}",
                "reviewed_at", "2026-07-19T01:00:00Z"
        );
        if (adminNote != null) {
            row.put("admin_note", adminNote);
        }
        return row;
    }

    private static Map<String, Object> remoteSupportRow(
            String remoteStatus,
            String accessCode,
            Long assignedAdminId,
            String ticketStatus,
            String reviewStatus,
            String supportDecision
    ) {
        return MockData.map(
                "internal_id", 100L,
                "ticket_status", ticketStatus,
                "review_status", reviewStatus,
                "support_decision", supportDecision,
                "assigned_admin_id", assignedAdminId,
                "remote_session_id", 500L,
                "remote_support_provider", "CHROME_REMOTE_DESKTOP",
                "remote_support_status", remoteStatus,
                "remote_access_code", accessCode,
                "access_code_registered_at", accessCode == null ? null : "2026-07-19T02:00:00Z",
                "remote_support_started_at", "IN_PROGRESS".equals(remoteStatus) || "COMPLETED".equals(remoteStatus)
                        ? "2026-07-19T02:30:00Z"
                        : null,
                "remote_support_completed_at", "COMPLETED".equals(remoteStatus) ? "2026-07-19T03:00:00Z" : null
        );
    }

    private static void assertThatStatus(ResponseStatusException exception, HttpStatus status) {
        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode()).isEqualTo(status);
    }
}
