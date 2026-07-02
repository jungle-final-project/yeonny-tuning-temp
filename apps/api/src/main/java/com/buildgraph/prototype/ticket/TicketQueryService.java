package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketQueryService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> TERMINAL_STATUSES = Set.of("CLOSED", "CANCELLED");

    private final JdbcTemplate jdbcTemplate;

    public TicketQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> tickets() {
        return jdbcTemplate.queryForList(ticketSql() + " WHERE t.deleted_at IS NULL ORDER BY t.created_at DESC, t.id DESC")
                .stream()
                .map(this::ticketMap)
                .toList();
    }

    public Map<String, Object> create(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        String symptom = request == null ? "게임 중 프레임 급락" : String.valueOf(request.getOrDefault("symptom", "게임 중 프레임 급락"));
        String logUploadId = request == null ? null : stringOrNull(request.get("logUploadId"));
        Long logUploadInternalId = logUploadId == null ? null : logUploadInternalId(logUploadId, user.internalId());
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  user_id,
                  log_upload_id,
                  symptom,
                  status,
                  cause_candidates,
                  upgrade_candidates
                )
                VALUES (?, ?, ?, 'OPEN', '[]'::jsonb, '[]'::jsonb)
                RETURNING public_id::text AS id
                """, user.internalId(), logUploadInternalId, symptom);
        return ticket(DbValueMapper.string(row, "id"), user);
    }

    public Map<String, Object> ticket(String id, CurrentUserService.CurrentUser user) {
        return jdbcTemplate.queryForList(ticketSql() + """
                        WHERE t.deleted_at IS NULL
                          AND t.public_id = ?::uuid
                          AND t.user_id = ?
                        """, id, user.internalId())
                .stream()
                .findFirst()
                .map(this::ticketMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    public Map<String, Object> adminTicket(String id) {
        return jdbcTemplate.queryForList(ticketSql() + " WHERE t.deleted_at IS NULL AND t.public_id = ?::uuid", id)
                .stream()
                .findFirst()
                .map(this::ticketMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    public Map<String, Object> update(String id, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> before = rawTicket(id);
        String beforeStatus = DbValueMapper.string(before, "status");
        String requestedStatus = normalizeStatus(request == null ? null : stringOrNull(request.get("status")));
        String nextStatus = requestedStatus == null ? beforeStatus : requestedStatus;
        String adminNote = request == null ? null : stringOrNull(request.get("adminNote"));
        String assignedAdminPublicId = request == null ? null : stringOrNull(request.get("assignedAdminId"));
        if (assignedAdminPublicId == null && ("ASSIGNED".equals(nextStatus) || "IN_PROGRESS".equals(nextStatus))) {
            assignedAdminPublicId = admin.id();
        }
        Long assignedAdminInternalId = assignedAdminPublicId == null ? null : adminInternalId(assignedAdminPublicId);

        if (requestedStatus != null && !canTransition(beforeStatus, requestedStatus)) {
            audit(admin.internalId(), "AS_TICKET_UPDATE_REJECTED", id, MockData.map(
                    "beforeStatus", beforeStatus,
                    "requestedStatus", requestedStatus,
                    "reason", "CONFLICT_STATE"
            ));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "허용되지 않는 AS 티켓 상태 전이입니다.");
        }
        String supportDecision = request == null ? null : stringOrNull(request.get("supportDecision"));
        String reviewStatus = request == null ? null : stringOrNull(request.get("reviewStatus"));
        String riskLevel = request == null ? null : stringOrNull(request.get("riskLevel"));
        Boolean autoResponseAllowed = request == null || request.get("autoResponseAllowed") == null
                ? null
                : Boolean.valueOf(request.get("autoResponseAllowed").toString());
        String effectiveReviewStatus = reviewStatus == null && supportDecision != null ? "APPROVED" : reviewStatus;

        jdbcTemplate.update("""
                UPDATE as_tickets
                SET status = ?,
                    assigned_admin_id = COALESCE(?, assigned_admin_id),
                    admin_note = COALESCE(?, admin_note),
                    support_decision = COALESCE(?, support_decision),
                    review_status = COALESCE(?, review_status),
                    risk_level = COALESCE(?, risk_level),
                    auto_response_allowed = COALESCE(?, auto_response_allowed),
                    resolved_at = CASE
                      WHEN ? = 'RESOLVED' AND resolved_at IS NULL THEN now()
                      ELSE resolved_at
                    END,
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """,
                nextStatus,
                assignedAdminInternalId,
                adminNote,
                supportDecision,
                effectiveReviewStatus,
                riskLevel,
                autoResponseAllowed,
                nextStatus,
                id
        );
        Map<String, Object> after = adminTicket(id);
        audit(admin.internalId(), "AS_TICKET_UPDATED", id, MockData.map(
                "beforeStatus", beforeStatus,
                "afterStatus", after.get("status"),
                "assignedAdminId", after.get("assignedAdminId"),
                "adminNoteUpdated", adminNote != null,
                "supportDecisionUpdated", supportDecision != null,
                "reviewStatusUpdated", effectiveReviewStatus != null
        ));
        return after;
    }

    private Long logUploadInternalId(String logUploadId, Long userId) {
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM agent_log_uploads
                        WHERE public_id = ?::uuid
                          AND user_id = ?
                        """, logUploadId, userId)
                .stream()
                .findFirst()
                .map(row -> numberLong(row.get("id")))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "로그 업로드를 찾을 수 없습니다."));
    }

    private Long adminInternalId(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM users
                        WHERE public_id = ?::uuid
                          AND role = 'ADMIN'
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .map(row -> numberLong(row.get("id")))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "담당 관리자를 찾을 수 없습니다."));
    }

    private Map<String, Object> rawTicket(String id) {
        return jdbcTemplate.queryForList(ticketSql() + " WHERE t.deleted_at IS NULL AND t.public_id = ?::uuid", id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private void audit(Long adminInternalId, String action, String targetId, Map<String, Object> metadata) {
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (actor_user_id, action, target_type, target_id, metadata)
                VALUES (?, ?, 'as_tickets', ?, ?::jsonb)
                """, adminInternalId, action, targetId, json(metadata));
    }

    private String ticketSql() {
        return """
                SELECT t.public_id::text AS id,
                       user_owner.public_id::text AS user_id,
                       t.status,
                       t.analysis_status,
                       t.review_status,
                       t.support_decision,
                       t.risk_level,
                       t.auto_response_allowed,
                       t.symptom,
                       lu.public_id::text AS log_upload_id,
                       admin.public_id::text AS assigned_admin_id,
                       t.cause_candidates,
                       t.upgrade_candidates,
                       t.admin_note,
                       t.resolved_at,
                       t.created_at,
                       t.updated_at
                FROM as_tickets t
                JOIN users user_owner ON user_owner.id = t.user_id
                LEFT JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
                LEFT JOIN users admin ON admin.id = t.assigned_admin_id
                """;
    }

    private Map<String, Object> ticketMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "userId", DbValueMapper.string(row, "user_id"),
                "status", DbValueMapper.string(row, "status"),
                "analysisStatus", DbValueMapper.string(row, "analysis_status"),
                "reviewStatus", DbValueMapper.string(row, "review_status"),
                "supportDecision", DbValueMapper.string(row, "support_decision"),
                "riskLevel", DbValueMapper.string(row, "risk_level"),
                "autoResponseAllowed", row.get("auto_response_allowed"),
                "symptom", DbValueMapper.string(row, "symptom"),
                "logUploadId", DbValueMapper.string(row, "log_upload_id"),
                "assignedAdminId", DbValueMapper.string(row, "assigned_admin_id"),
                "causeCandidates", DbValueMapper.json(row, "cause_candidates", List.of()),
                "upgradeCandidates", DbValueMapper.json(row, "upgrade_candidates", List.of()),
                "adminNote", DbValueMapper.string(row, "admin_note"),
                "resolvedAt", DbValueMapper.timestamp(row, "resolved_at"),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "updatedAt", DbValueMapper.timestamp(row, "updated_at")
        );
    }

    private static boolean canTransition(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return true;
        }
        if (TERMINAL_STATUSES.contains(from)) {
            return false;
        }
        return switch (from) {
            case "OPEN" -> Set.of("ASSIGNED", "IN_PROGRESS", "CANCELLED").contains(to);
            case "ASSIGNED" -> Set.of("IN_PROGRESS", "ASSIGNED", "CANCELLED").contains(to);
            case "IN_PROGRESS" -> Set.of("ASSIGNED", "RESOLVED", "CANCELLED").contains(to);
            case "RESOLVED" -> "CLOSED".equals(to);
            default -> false;
        };
    }

    private static String normalizeStatus(String value) {
        String status = stringOrNull(value);
        if (status == null) {
            return null;
        }
        String normalized = status.toUpperCase();
        if (!Set.of("OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 AS 티켓 상태입니다.");
        }
        return normalized;
    }

    private static Long numberLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 변환에 실패했습니다.", error);
        }
    }
}
