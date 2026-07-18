package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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
                        "admin_note", "Remote support link sent."
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

    private static void assertThatStatus(ResponseStatusException exception, HttpStatus status) {
        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode()).isEqualTo(status);
    }
}
