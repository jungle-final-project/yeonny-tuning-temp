package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.support.AsLogRagAnalysisService;
import com.buildgraph.prototype.ticket.contract.SupportDecision;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketQueryService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> TICKET_STATUSES = Set.of(
            "OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED"
    );
    private static final Set<String> REVIEW_STATUSES = Set.of(
            "NOT_REQUIRED", "REQUIRED", "IN_REVIEW", "APPROVED", "REJECTED"
    );
    private static final Set<String> SUPPORT_DECISIONS = SupportDecision.names();
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> VISIT_TIME_SLOTS = Set.of("MORNING", "AFTERNOON", "EVENING");
    private static final Set<String> DIAGNOSTIC_ACCURACIES = Set.of("ACCURATE", "PARTIAL", "MISSED", "UNKNOWN");
    private static final int MAX_REMOTE_ACCESS_CODE_LENGTH = 32;

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

    public Map<String, Object> create(Map<String, Object> request) {
        String symptom = request == null ? "게임 중 프레임 급락" : String.valueOf(request.getOrDefault("symptom", "게임 중 프레임 급락"));
        String logUploadId = request == null ? null : stringOrNull(request.get("logUploadId"));
        Map<String, Object> logUpload = resolvePrototypeLogUploadRow(logUploadId);
        String ticketPublicId = UUID.randomUUID().toString();
        TicketAnalysisDraft draft = ticketAnalysisDraft(ticketPublicId, symptom, asRagAnalysis(logUpload));
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  public_id,
                  user_id,
                  log_upload_id,
                  symptom,
                  status,
                  analysis_status,
                  review_status,
                  support_decision,
                  risk_level,
                  cause_candidates,
                  upgrade_candidates,
                  admin_note,
                  log_summary,
                  support_routing,
                  ai_diagnosis_request
                )
                VALUES (
                  ?::uuid,
                  (SELECT id FROM users WHERE email = 'user@example.com'),
                  ?,
                  ?,
                  'OPEN',
                  ?,
                  ?,
                  ?,
                  ?,
                  ?::jsonb,
                  ?::jsonb,
                  ?,
                  ?::jsonb,
                  ?::jsonb,
                  ?::jsonb
                )
                RETURNING public_id::text AS id
                """,
                ticketPublicId,
                longValue(logUpload, "id"),
                symptom,
                draft.analysisStatus(),
                draft.reviewStatus(),
                draft.supportDecision(),
                draft.riskLevel(),
                toJson(draft.causeCandidates()),
                toJson(draft.upgradeCandidates()),
                draft.adminNote(),
                toJson(draft.logSummary()),
                toJson(draft.supportRouting()),
                toJson(draft.aiDiagnosisRequest()));
        return ticket(DbValueMapper.string(row, "id"));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        if (user == null) {
            return create(request);
        }
        lockUserForTicketCreate(user.internalId());
        String symptom = request == null
                ? "게임 중 프레임 급락"
                : String.valueOf(request.getOrDefault("symptom", "게임 중 프레임 급락"));
        Map<String, Object> logUpload = resolveUserLogUploadRow(
                request == null ? null : stringOrNull(request.get("logUploadId")),
                user.internalId()
        );
        String ticketPublicId = UUID.randomUUID().toString();
        TicketAnalysisDraft draft = ticketAnalysisDraft(ticketPublicId, symptom, asRagAnalysis(logUpload));
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  public_id,
                  user_id,
                  log_upload_id,
                  symptom,
                  status,
                  analysis_status,
                  review_status,
                  support_decision,
                  risk_level,
                  cause_candidates,
                  upgrade_candidates,
                  admin_note,
                  log_summary,
                  support_routing,
                  ai_diagnosis_request
                )
                VALUES (
                  ?::uuid,
                  ?,
                  ?,
                  ?,
                  'OPEN',
                  ?,
                  ?,
                  ?,
                  ?,
                  ?::jsonb,
                  ?::jsonb,
                  ?,
                  ?::jsonb,
                  ?::jsonb,
                  ?::jsonb
                )
                RETURNING id AS internal_id, public_id::text AS id
                """,
                ticketPublicId,
                user.internalId(),
                longValue(logUpload, "id"),
                symptom,
                draft.analysisStatus(),
                draft.reviewStatus(),
                draft.supportDecision(),
                draft.riskLevel(),
                toJson(draft.causeCandidates()),
                toJson(draft.upgradeCandidates()),
                draft.adminNote(),
                toJson(draft.logSummary()),
                toJson(draft.supportRouting()),
                toJson(draft.aiDiagnosisRequest()));
        SupportChatRoomCreator.ensureRoom(jdbcTemplate, user.internalId(), longValue(row, "internal_id"));
        return ticket(DbValueMapper.string(row, "id"), user);
    }

    private void lockUserForTicketCreate(Long userInternalId) {
        List<Map<String, Object>> locked = jdbcTemplate.queryForList("""
                SELECT id
                FROM users
                WHERE id = ?
                  AND deleted_at IS NULL
                FOR UPDATE
                """, userInternalId);
        if (locked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
    }

    public Map<String, Object> ticket(String id) {
        return jdbcTemplate.queryForList(ticketSql() + " WHERE t.deleted_at IS NULL AND t.public_id = ?::uuid", id)
                .stream()
                .findFirst()
                .map(this::ticketMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    public Map<String, Object> adminTicket(String id) {
        return jdbcTemplate.queryForList(ticketSql() + " WHERE t.deleted_at IS NULL AND t.public_id = ?::uuid", id)
                .stream()
                .findFirst()
                .map(this::adminTicketMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    @Transactional
    public Map<String, Object> assignToCurrentAdmin(String id, CurrentUserService.CurrentUser admin) {
        requireAdminActor(admin);
        Map<String, Object> current = ticketActionRow(id);
        requireActionableTicket(current);
        requireAvailableAssignment(current, admin);

        Long assignedAdminId = longValue(current, "assigned_admin_id");
        String status = DbValueMapper.string(current, "status");
        String reviewStatus = DbValueMapper.string(current, "review_status");
        boolean alreadyApplied = admin.internalId().equals(assignedAdminId)
                && !"OPEN".equals(status)
                && !"REQUIRED".equals(reviewStatus);
        if (!alreadyApplied) {
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET assigned_admin_id = ?,
                        status = CASE WHEN status = 'OPEN' THEN 'ASSIGNED' ELSE status END,
                        review_status = CASE WHEN review_status = 'REQUIRED' THEN 'IN_REVIEW' ELSE review_status END,
                        updated_at = now()
                    WHERE id = ?
                    """, admin.internalId(), longValue(current, "internal_id"));
            auditTicketAction(id, current, admin, "AS_TICKET_ASSIGNED_TO_SELF", "ASSIGNED");
        }
        return adminTicket(id);
    }

    @Transactional
    public Map<String, Object> requestMoreInformation(
            String id,
            String adminNote,
            CurrentUserService.CurrentUser admin
    ) {
        requireAdminActor(admin);
        String note = adminNote == null ? null : adminNote.trim();
        if (note == null || note.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "추가 정보 요청 사유를 입력해 주세요.");
        }
        Map<String, Object> current = ticketActionRow(id);
        requireActionableTicket(current);
        requireReviewNotCompleted(current);
        requireAvailableAssignment(current, admin);

        boolean alreadyApplied = admin.internalId().equals(longValue(current, "assigned_admin_id"))
                && "IN_PROGRESS".equals(DbValueMapper.string(current, "status"))
                && "IN_REVIEW".equals(DbValueMapper.string(current, "review_status"))
                && "NEEDS_MORE_INFO".equals(DbValueMapper.string(current, "support_decision"))
                && note.equals(DbValueMapper.string(current, "admin_note"));
        if (!alreadyApplied) {
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET assigned_admin_id = ?,
                        status = 'IN_PROGRESS',
                        review_status = 'IN_REVIEW',
                        support_decision = 'NEEDS_MORE_INFO',
                        admin_note = ?,
                        reviewed_at = NULL,
                        updated_at = now()
                    WHERE id = ?
                    """, admin.internalId(), note, longValue(current, "internal_id"));
            auditTicketAction(id, current, admin, "AS_TICKET_MORE_INFO_REQUESTED", "NEEDS_MORE_INFO");
        }
        return adminTicket(id);
    }

    @Transactional
    public Map<String, Object> approveRemoteSupport(
            String id,
            String adminNote,
            CurrentUserService.CurrentUser admin
    ) {
        requireAdminActor(admin);
        Map<String, Object> current = ticketActionRow(id);
        requireAvailableAssignment(current, admin);

        boolean alreadyApplied = admin.internalId().equals(longValue(current, "assigned_admin_id"))
                && "IN_PROGRESS".equals(DbValueMapper.string(current, "status"))
                && "APPROVED".equals(DbValueMapper.string(current, "review_status"))
                && "REMOTE_POSSIBLE".equals(DbValueMapper.string(current, "support_decision"));
        if (alreadyApplied) {
            prepareChromeRemoteSupportSession(id, admin);
            return adminTicket(id);
        }

        requireActionableTicket(current);
        requireReviewNotCompleted(current);
        if (!isRemoteSupportCandidate(current) || hasOutOfScopeBlockingFactor(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 진단 및 지원 결정에서는 원격 지원을 승인할 수 없습니다.");
        }

        String note = adminNote == null || adminNote.isBlank() ? null : adminNote.trim();
        jdbcTemplate.update("""
                UPDATE as_tickets
                SET assigned_admin_id = ?,
                    status = 'IN_PROGRESS',
                    review_status = 'APPROVED',
                    support_decision = 'REMOTE_POSSIBLE',
                    admin_note = COALESCE(?, admin_note),
                    reviewed_at = now(),
                    updated_at = now()
                WHERE id = ?
                """, admin.internalId(), note, longValue(current, "internal_id"));
        auditTicketAction(id, current, admin, "AS_TICKET_REMOTE_SUPPORT_APPROVED", "REMOTE_POSSIBLE");
        prepareChromeRemoteSupportSession(id, admin);
        return adminTicket(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> userRemoteSupport(String id, CurrentUserService.CurrentUser user) {
        requireUserActor(user);
        return remoteSupportDetails(remoteSupportReadRow(id, user.internalId()), false);
    }

    @Transactional
    public Map<String, Object> registerRemoteAccessCode(
            String id,
            Object accessCode,
            CurrentUserService.CurrentUser user
    ) {
        requireUserActor(user);
        String normalizedCode = normalizeRemoteAccessCode(accessCode);
        Map<String, Object> current = remoteSupportActionRow(id, user.internalId());
        requireRemoteSupportApproved(current);
        requireActiveTicketForRemoteSupport(current);
        String remoteStatus = DbValueMapper.string(current, "remote_support_status");
        if (!Set.of("WAITING_FOR_CODE", "CODE_READY", "REQUESTED", "LINK_SENT").contains(remoteStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "현재 원격지원 상태에서는 지원 코드를 등록할 수 없습니다.");
        }
        jdbcTemplate.update("""
                UPDATE remote_support_sessions
                SET provider = 'CHROME_REMOTE_DESKTOP',
                    session_url = NULL,
                    status = 'CODE_READY',
                    access_code = ?,
                    access_code_registered_at = now()
                WHERE id = ?
                """, normalizedCode, longValue(current, "remote_session_id"));
        auditRemoteSupportAction(id, user, "REMOTE_SUPPORT_CODE_REGISTERED", remoteStatus, "CODE_READY");
        return userRemoteSupport(id, user);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> adminRemoteSupport(String id, CurrentUserService.CurrentUser admin) {
        requireAdminActor(admin);
        Map<String, Object> current = remoteSupportReadRow(id, null);
        requireAssignedAdmin(current, admin);
        return remoteSupportDetails(current, true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> remoteAccessCodeForAdmin(String id, CurrentUserService.CurrentUser admin) {
        requireAdminActor(admin);
        Map<String, Object> current = remoteSupportReadRow(id, null);
        requireAssignedAdmin(current, admin);
        String remoteStatus = DbValueMapper.string(current, "remote_support_status");
        String accessCode = DbValueMapper.string(current, "remote_access_code");
        if (!Set.of("CODE_READY", "IN_PROGRESS").contains(remoteStatus) || accessCode == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "복사할 수 있는 원격지원 코드가 없습니다.");
        }
        return MockData.map("accessCode", accessCode);
    }

    @Transactional
    public Map<String, Object> startRemoteSupport(String id, CurrentUserService.CurrentUser admin) {
        requireAdminActor(admin);
        Map<String, Object> current = remoteSupportActionRow(id, null);
        requireAssignedAdmin(current, admin);
        requireRemoteSupportApproved(current);
        requireActiveTicketForRemoteSupport(current);
        String remoteStatus = DbValueMapper.string(current, "remote_support_status");
        if ("IN_PROGRESS".equals(remoteStatus)) {
            return remoteSupportDetails(current, true);
        }
        if (!"CODE_READY".equals(remoteStatus) || DbValueMapper.string(current, "remote_access_code") == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "지원 코드가 등록된 뒤 원격지원을 시작할 수 있습니다.");
        }
        jdbcTemplate.update("""
                UPDATE remote_support_sessions
                SET status = 'IN_PROGRESS',
                    started_at = COALESCE(started_at, now())
                WHERE id = ?
                """, longValue(current, "remote_session_id"));
        auditRemoteSupportAction(id, admin, "REMOTE_SUPPORT_STARTED", remoteStatus, "IN_PROGRESS");
        return adminRemoteSupport(id, admin);
    }

    @Transactional
    public Map<String, Object> completeRemoteSupport(String id, CurrentUserService.CurrentUser admin) {
        requireAdminActor(admin);
        Map<String, Object> current = remoteSupportActionRow(id, null);
        requireAssignedAdmin(current, admin);
        String remoteStatus = DbValueMapper.string(current, "remote_support_status");
        if ("COMPLETED".equals(remoteStatus)) {
            return remoteSupportDetails(current, true);
        }
        if (!"IN_PROGRESS".equals(remoteStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "진행 중인 원격지원만 완료할 수 있습니다.");
        }
        jdbcTemplate.update("""
                UPDATE remote_support_sessions
                SET status = 'COMPLETED',
                    ended_at = COALESCE(ended_at, now()),
                    ended_reason = COALESCE(ended_reason, 'ADMIN_COMPLETED'),
                    access_code = NULL
                WHERE id = ?
                """, longValue(current, "remote_session_id"));
        auditRemoteSupportAction(id, admin, "REMOTE_SUPPORT_COMPLETED", remoteStatus, "COMPLETED");
        return adminRemoteSupport(id, admin);
    }

    @Transactional
    public Map<String, Object> delete(String id, CurrentUserService.CurrentUser admin) {
        if (admin == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
        Map<String, Object> current = jdbcTemplate.queryForList("""
                        SELECT t.id AS internal_id,
                               t.public_id::text AS id,
                               t.status,
                               (
                                 SELECT r.public_id::text
                                 FROM support_chat_rooms r
                                 WHERE r.as_ticket_id = t.id
                                   AND r.deleted_at IS NULL
                                 ORDER BY r.created_at DESC, r.id DESC
                                 LIMIT 1
                               ) AS support_chat_room_id
                        FROM as_tickets t
                        WHERE t.public_id = ?::uuid
                          AND t.deleted_at IS NULL
                        FOR UPDATE
                        """, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));

        Long ticketInternalId = longValue(current, "internal_id");
        jdbcTemplate.update("""
                UPDATE support_chat_rooms
                SET status = 'ARCHIVED',
                    updated_at = now()
                WHERE as_ticket_id = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                """, ticketInternalId);
        jdbcTemplate.update("""
                UPDATE as_chat_sessions
                SET status = 'ARCHIVED',
                    updated_at = now()
                WHERE as_ticket_id = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                """, ticketInternalId);
        jdbcTemplate.update("""
                UPDATE remote_support_sessions
                SET status = 'CANCELLED',
                    ended_at = COALESCE(ended_at, now()),
                    ended_reason = COALESCE(ended_reason, 'AS_TICKET_DELETED'),
                    access_code = NULL
                WHERE as_ticket_id = ?
                  AND status IN ('REQUESTED', 'LINK_SENT', 'WAITING_FOR_CODE', 'CODE_READY', 'IN_PROGRESS')
                """, ticketInternalId);
        jdbcTemplate.update("""
                UPDATE visit_support_reservations
                SET status = 'CANCELLED',
                    updated_at = now()
                WHERE as_ticket_id = ?
                  AND status IN ('REQUESTED', 'SCHEDULED', 'RESCHEDULE_REQUESTED', 'VISIT_IN_PROGRESS')
                """, ticketInternalId);

        Map<String, Object> deleted = jdbcTemplate.queryForList("""
                        UPDATE as_tickets
                        SET deleted_at = now(),
                            updated_at = now()
                        WHERE id = ?
                          AND deleted_at IS NULL
                        RETURNING public_id::text AS id,
                                  deleted_at::text AS deleted_at
                        """, ticketInternalId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));

        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (
                  actor_user_id,
                  action,
                  target_type,
                  target_id,
                  metadata
                )
                VALUES (
                  ?,
                  'AS_TICKET_DELETED',
                  'as_tickets',
                  ?,
                  jsonb_build_object(
                    'previousStatus', CAST(? AS text),
                    'softDelete', true,
                    'relatedSupportClosed', true
                  )
                )
                """,
                admin.internalId(),
                id,
                DbValueMapper.string(current, "status")
        );

        return MockData.map(
                "id", DbValueMapper.string(deleted, "id"),
                "deleted", true,
                "deletedAt", DbValueMapper.string(deleted, "deleted_at"),
                "supportChatRoomId", DbValueMapper.string(current, "support_chat_room_id")
        );
    }

    public Map<String, Object> ticket(String id, CurrentUserService.CurrentUser user) {
        if (user == null) {
            return ticket(id);
        }
        return jdbcTemplate.queryForList(
                        ticketSql() + " WHERE t.deleted_at IS NULL AND t.public_id = ?::uuid AND t.user_id = ?",
                        id,
                        user.internalId()
                )
                .stream()
                .findFirst()
                .map(this::ticketMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    @Transactional
    public Map<String, Object> requestRemoteSupport(
            String id,
            Map<String, Object> request,
            CurrentUserService.CurrentUser user
    ) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        String reason = request == null ? null : stringOrNull(request.get("reason"));
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason 값이 필요합니다.");
        }
        String contactPhone = request == null ? null : stringOrNull(request.get("contactPhone"));
        Map<String, Object> row = userTicketInternalRow(id, user.internalId());
        Long ticketInternalId = longValue(row, "internal_id");
        Integer activeRequests = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM remote_support_sessions
                WHERE as_ticket_id = ?
                  AND status IN ('REQUESTED', 'LINK_SENT', 'IN_PROGRESS')
                """, Integer.class, ticketInternalId);
        if (activeRequests != null && activeRequests > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 진행 중인 원격지원 요청이 있습니다.");
        }
        jdbcTemplate.update("""
                INSERT INTO remote_support_sessions (
                  as_ticket_id,
                  device_id,
                  provider,
                  status,
                  requested_by_user_id,
                  request_reason,
                  contact_phone_snapshot,
                  user_requested_at
                )
                VALUES (?, ?, 'EXTERNAL_LINK', 'REQUESTED', ?, ?, ?, now())
                """,
                ticketInternalId,
                longValue(row, "device_id"),
                user.internalId(),
                reason,
                contactPhone
        );
        jdbcTemplate.update("""
                UPDATE as_tickets
                SET review_status = 'REQUIRED',
                    updated_at = now()
                WHERE id = ?
                """, ticketInternalId);
        return ticket(id, user);
    }

    private Map<String, Object> userTicketInternalRow(String id, Long userInternalId) {
        return jdbcTemplate.queryForList("""
                        SELECT t.id AS internal_id,
                               lu.device_id
                        FROM as_tickets t
                        LEFT JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
                        WHERE t.deleted_at IS NULL
                          AND t.public_id = ?::uuid
                          AND t.user_id = ?
                        """, id, userInternalId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    @Transactional
    public Map<String, Object> submitFeedback(
            String id,
            Map<String, Object> request,
            CurrentUserService.CurrentUser user
    ) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        int rating = parseInteger("rating", request == null ? null : request.get("rating"));
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating 값은 1~5 사이여야 합니다.");
        }
        String comment = request == null ? null : stringOrNull(request.get("comment"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                UPDATE as_tickets
                SET feedback_rating = ?,
                    feedback_comment = ?,
                    feedback_created_at = now(),
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND user_id = ?
                  AND deleted_at IS NULL
                RETURNING public_id::text AS id
                """, rating, comment, id, user.internalId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다.");
        }
        return ticket(id, user);
    }

    private Map<String, Object> resolveUserLogUploadRow(String logUploadId, Long userInternalId) {
        if (logUploadId == null) {
            return Map.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT id,
                               public_id::text AS log_upload_id,
                               summary,
                               as_rag_analysis::text AS as_rag_analysis
                        FROM agent_log_uploads
                        WHERE public_id = ?::uuid
                          AND user_id = ?
                        """, logUploadId, userInternalId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "로그 업로드를 찾을 수 없습니다."));
    }

    private Map<String, Object> resolvePrototypeLogUploadRow(String logUploadId) {
        if (logUploadId == null) {
            return Map.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT id,
                               public_id::text AS log_upload_id,
                               summary,
                               as_rag_analysis::text AS as_rag_analysis
                        FROM agent_log_uploads
                        WHERE public_id = ?::uuid
                          AND user_id = (SELECT id FROM users WHERE email = 'user@example.com')
                        """, logUploadId)
                .stream()
                .findFirst()
                .orElse(Map.of());
    }

    private static TicketAnalysisDraft ticketAnalysisDraft(String ticketPublicId, String symptom, Map<String, Object> asRagAnalysis) {
        if (asRagAnalysis == null || asRagAnalysis.isEmpty()) {
            return new TicketAnalysisDraft(
                    "NOT_STARTED",
                    "NOT_REQUIRED",
                    null,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    null
            );
        }
        Map<String, Object> supportRouting = AsLogRagAnalysisService.supportRouting(asRagAnalysis);
        Map<String, Object> logSummary = AsLogRagAnalysisService.logSummary(ticketPublicId, symptom, asRagAnalysis);
        return new TicketAnalysisDraft(
                "RULE_READY",
                "REQUIRED",
                AsLogRagAnalysisService.supportDecision(asRagAnalysis),
                AsLogRagAnalysisService.riskLevel(asRagAnalysis),
                AsLogRagAnalysisService.causeCandidates(asRagAnalysis),
                List.of(),
                stringOrNull(asRagAnalysis.get("summaryText")),
                logSummary,
                supportRouting,
                AsLogRagAnalysisService.aiDiagnosisRequest(ticketPublicId, symptom, logSummary, supportRouting)
        );
    }

    private static Map<String, Object> asRagAnalysis(Map<String, Object> logUpload) {
        Object value = DbValueMapper.json(logUpload, "as_rag_analysis", Map.of());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    public Map<String, Object> update(String id, Map<String, Object> request) {
        return update(id, request, null);
    }

    @Transactional
    public Map<String, Object> update(
            String id,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        Map<String, Object> current = ticketRow(id);
        String status = request == null ? null : stringOrNull(request.get("status"));
        String adminNote = request == null ? null : stringOrNull(request.get("adminNote"));
        if (status != null) {
            validateStatusTransition(DbValueMapper.string(current, "status"), status);
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET status = ?, updated_at = now()
                    WHERE public_id = ?::uuid
                    """, status, id);
            finishRemoteSupportForTerminalTicket(id, status);
        }
        if (adminNote != null) {
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET admin_note = ?, updated_at = now()
                    WHERE public_id = ?::uuid
                    """, adminNote, id);
        }
        String assignedAdminId = request == null ? null : stringOrNull(request.get("assignedAdminId"));
        if (assignedAdminId != null) {
            assignAdmin(id, assignedAdminId);
        }
        String supportDecision = request == null ? null : stringOrNull(request.get("supportDecision"));
        String reviewStatus = request == null ? null : stringOrNull(request.get("reviewStatus"));
        String riskLevel = request == null ? null : stringOrNull(request.get("riskLevel"));
        String diagnosticAccuracy = request == null ? null : stringOrNull(request.get("diagnosticAccuracy"));
        validateNullable("supportDecision", supportDecision, SUPPORT_DECISIONS);
        validateNullable("reviewStatus", reviewStatus, REVIEW_STATUSES);
        validateNullable("riskLevel", riskLevel, RISK_LEVELS);
        validateNullable("diagnosticAccuracy", diagnosticAccuracy, DIAGNOSTIC_ACCURACIES);
        String currentDecision = DbValueMapper.string(current, "support_decision");
        boolean unsupportedException = isUnsupportedException(currentDecision, supportDecision)
                || isRoutingException(current, supportDecision, request);
        String exceptionReason = request == null ? null : stringOrNull(request.get("exceptionApprovalReason"));
        String exceptionScope = request == null ? null : stringOrNull(request.get("exceptionResponsibilityScope"));
        String exceptionUserMessage = request == null ? null : stringOrNull(request.get("exceptionUserMessage"));
        if (unsupportedException) {
            requireExceptionField("exceptionApprovalReason", exceptionReason);
            requireExceptionField("exceptionResponsibilityScope", exceptionScope);
            requireExceptionField("exceptionUserMessage", exceptionUserMessage);
        }
        Boolean autoResponseAllowed = request == null || request.get("autoResponseAllowed") == null
                ? null
                : parseBoolean("autoResponseAllowed", request.get("autoResponseAllowed"));
        validateRemoteSupportLinkIfPresent(request);
        validateSupportExecutionPolicy(current, supportDecision, reviewStatus, autoResponseAllowed, request, unsupportedException);
        if (supportDecision != null || reviewStatus != null || riskLevel != null || autoResponseAllowed != null) {
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET support_decision = COALESCE(?, support_decision),
                        review_status = COALESCE(?, review_status),
                        risk_level = COALESCE(?, risk_level),
                        auto_response_allowed = COALESCE(?, auto_response_allowed),
                        updated_at = now()
                    WHERE public_id = ?::uuid
                    """,
                    supportDecision,
                    reviewStatus == null && supportDecision != null ? "APPROVED" : reviewStatus,
                    riskLevel,
                    autoResponseAllowed,
                    id
            );
        }
        if (diagnosticAccuracy != null) {
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET diagnostic_accuracy = ?,
                        updated_at = now()
                    WHERE public_id = ?::uuid
                    """, diagnosticAccuracy, id);
        }
        if (unsupportedException) {
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET exception_approval_reason = ?,
                        exception_responsibility_scope = ?,
                        exception_user_message = ?,
                        exception_approved_at = now(),
                        exception_approved_by = ?,
                        updated_at = now()
                    WHERE public_id = ?::uuid
                    """,
                    exceptionReason,
                    exceptionScope,
                    exceptionUserMessage,
                    admin == null ? null : admin.internalId(),
                    id
            );
        }
        saveRemoteSupportIfRequested(id, request, admin);
        saveVisitSupportIfRequested(current, request);
        auditTicketUpdate(id, current, request, admin);
        return admin == null ? ticket(id) : adminTicket(id);
    }

    private Map<String, Object> ticketRow(String id) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               user_id,
                               log_upload_id,
                               status,
                               review_status,
                               support_decision,
                               support_routing,
                               exception_approval_reason,
                               exception_responsibility_scope,
                               exception_user_message
                        FROM as_tickets
                        WHERE deleted_at IS NULL
                          AND public_id = ?::uuid
                        """, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private Map<String, Object> ticketActionRow(String id) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               assigned_admin_id,
                               status,
                               review_status,
                               support_decision,
                               request_type,
                               diagnosis_result,
                               support_routing,
                               admin_note
                        FROM as_tickets
                        WHERE deleted_at IS NULL
                          AND public_id = ?::uuid
                        FOR UPDATE
                        """, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private Map<String, Object> remoteSupportReadRow(String id, Long userInternalId) {
        String ownershipClause = userInternalId == null ? "" : " AND t.user_id = ?";
        Object[] parameters = userInternalId == null ? new Object[]{id} : new Object[]{id, userInternalId};
        return jdbcTemplate.queryForList(remoteSupportSql() + " WHERE t.public_id = ?::uuid AND t.deleted_at IS NULL" + ownershipClause, parameters)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private Map<String, Object> remoteSupportActionRow(String id, Long userInternalId) {
        String ownershipClause = userInternalId == null ? "" : " AND t.user_id = ?";
        Object[] parameters = userInternalId == null ? new Object[]{id} : new Object[]{id, userInternalId};
        return jdbcTemplate.queryForList(
                        remoteSupportSql() + " WHERE t.public_id = ?::uuid AND t.deleted_at IS NULL" + ownershipClause + " FOR UPDATE OF t",
                        parameters
                )
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private String remoteSupportSql() {
        return """
                SELECT t.id AS internal_id,
                       t.status AS ticket_status,
                       t.review_status,
                       t.support_decision,
                       t.assigned_admin_id,
                       rs.id AS remote_session_id,
                       rs.provider AS remote_support_provider,
                       rs.status AS remote_support_status,
                       rs.access_code AS remote_access_code,
                       rs.access_code_registered_at,
                       rs.started_at AS remote_support_started_at,
                       rs.ended_at AS remote_support_completed_at
                FROM as_tickets t
                LEFT JOIN LATERAL (
                  SELECT id,
                         provider,
                         status,
                         access_code,
                         access_code_registered_at,
                         started_at,
                         ended_at
                  FROM remote_support_sessions
                  WHERE as_ticket_id = t.id
                  ORDER BY created_at DESC, id DESC
                  LIMIT 1
                ) rs ON true
                """;
    }

    private void prepareChromeRemoteSupportSession(String ticketId, CurrentUserService.CurrentUser admin) {
        jdbcTemplate.update("""
                WITH target_ticket AS (
                  SELECT t.id, lu.device_id
                  FROM as_tickets t
                  LEFT JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
                  WHERE t.public_id = ?::uuid
                    AND t.deleted_at IS NULL
                ),
                latest AS (
                  SELECT rs.id, rs.status
                  FROM remote_support_sessions rs
                  JOIN target_ticket tt ON tt.id = rs.as_ticket_id
                  ORDER BY rs.created_at DESC, rs.id DESC
                  LIMIT 1
                ),
                updated AS (
                  UPDATE remote_support_sessions rs
                  SET provider = 'CHROME_REMOTE_DESKTOP',
                      session_url = NULL,
                      status = CASE
                        WHEN rs.status IN ('CODE_READY', 'IN_PROGRESS') THEN rs.status
                        ELSE 'WAITING_FOR_CODE'
                      END,
                      access_code = CASE
                        WHEN rs.status IN ('CODE_READY', 'IN_PROGRESS') THEN rs.access_code
                        ELSE NULL
                      END,
                      requested_by_admin_id = ?
                  WHERE rs.id = (SELECT id FROM latest)
                    AND rs.status IN ('REQUESTED', 'LINK_SENT', 'WAITING_FOR_CODE', 'CODE_READY', 'IN_PROGRESS')
                  RETURNING rs.id
                )
                INSERT INTO remote_support_sessions (
                  as_ticket_id,
                  device_id,
                  provider,
                  status,
                  requested_by_admin_id
                )
                SELECT tt.id,
                       tt.device_id,
                       'CHROME_REMOTE_DESKTOP',
                       'WAITING_FOR_CODE',
                       ?
                FROM target_ticket tt
                WHERE NOT EXISTS (SELECT 1 FROM latest)
                """, ticketId, admin.internalId(), admin.internalId());
    }

    private static void requireUserActor(CurrentUserService.CurrentUser user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
    }

    private static void requireRemoteSupportApproved(Map<String, Object> current) {
        boolean approved = "APPROVED".equals(DbValueMapper.string(current, "review_status"))
                && "REMOTE_POSSIBLE".equals(DbValueMapper.string(current, "support_decision"));
        if (!approved || current.get("remote_session_id") == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "원격지원 승인 후 지원 코드를 등록할 수 있습니다.");
        }
    }

    private static void requireActiveTicketForRemoteSupport(Map<String, Object> current) {
        if (!Set.of("OPEN", "ASSIGNED", "IN_PROGRESS").contains(DbValueMapper.string(current, "ticket_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "완료되거나 종료된 AS 티켓에서는 원격지원을 진행할 수 없습니다.");
        }
    }

    private static void requireAssignedAdmin(
            Map<String, Object> current,
            CurrentUserService.CurrentUser admin
    ) {
        Long assignedAdminId = longValue(current, "assigned_admin_id");
        if (assignedAdminId == null || !assignedAdminId.equals(admin.internalId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "담당 관리자만 원격지원을 처리할 수 있습니다.");
        }
    }

    private static String normalizeRemoteAccessCode(Object value) {
        String raw = value == null ? "" : value.toString().trim();
        String normalized = raw.replaceAll("[\\s-]+", "");
        if (normalized.isBlank() || !normalized.matches("[0-9]+") || normalized.length() > MAX_REMOTE_ACCESS_CODE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원 코드는 숫자와 구분 공백 또는 하이픈만 사용할 수 있습니다.");
        }
        return normalized;
    }

    private static Map<String, Object> remoteSupportDetails(Map<String, Object> row, boolean adminView) {
        String accessCode = DbValueMapper.string(row, "remote_access_code");
        Map<String, Object> result = MockData.map(
                "status", DbValueMapper.string(row, "remote_support_status"),
                "provider", DbValueMapper.string(row, "remote_support_provider"),
                "accessCodeRegistered", accessCode != null,
                "accessCodeRegisteredAt", DbValueMapper.timestamp(row, "access_code_registered_at"),
                "startedAt", DbValueMapper.timestamp(row, "remote_support_started_at"),
                "completedAt", DbValueMapper.timestamp(row, "remote_support_completed_at")
        );
        if (adminView) {
            result.put("maskedAccessCode", maskRemoteAccessCode(accessCode));
        }
        return result;
    }

    private static String maskRemoteAccessCode(String accessCode) {
        if (accessCode == null) {
            return null;
        }
        if (accessCode.length() <= 4) {
            return "•".repeat(accessCode.length());
        }
        return "•".repeat(accessCode.length() - 4) + " " + accessCode.substring(accessCode.length() - 4);
    }

    private static void requireAdminActor(CurrentUserService.CurrentUser admin) {
        if (admin == null || !"ADMIN".equals(admin.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
    }

    private static void requireActionableTicket(Map<String, Object> current) {
        String status = DbValueMapper.string(current, "status");
        if (!Set.of("OPEN", "ASSIGNED", "IN_PROGRESS").contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "완료되거나 종료된 AS 티켓은 다시 처리할 수 없습니다.");
        }
    }

    private static void requireReviewNotCompleted(Map<String, Object> current) {
        String reviewStatus = DbValueMapper.string(current, "review_status");
        if (Set.of("APPROVED", "REJECTED").contains(reviewStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 관리자 검토가 완료된 AS 티켓입니다.");
        }
    }

    private static void requireAvailableAssignment(
            Map<String, Object> current,
            CurrentUserService.CurrentUser admin
    ) {
        Long assignedAdminId = longValue(current, "assigned_admin_id");
        if (assignedAdminId != null && !assignedAdminId.equals(admin.internalId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 관리자가 담당 중인 AS 티켓입니다.");
        }
    }

    private static boolean isRemoteSupportCandidate(Map<String, Object> current) {
        if ("REMOTE_POSSIBLE".equals(DbValueMapper.string(current, "support_decision"))) {
            return true;
        }
        if ("REMOTE_SUPPORT".equals(DbValueMapper.string(current, "request_type"))) {
            return true;
        }
        Object diagnosisValue = DbValueMapper.json(current, "diagnosis_result", Map.of());
        if (diagnosisValue instanceof Map<?, ?> diagnosis
                && "REMOTE_SUPPORT".equals(stringOrNull(diagnosis.get("resolutionType")))) {
            return true;
        }
        Object routingValue = DbValueMapper.json(current, "support_routing", Map.of());
        if (!(routingValue instanceof Map<?, ?> routing)) {
            return false;
        }
        return "REMOTE_SUPPORT".equals(stringOrNull(routing.get("recommendedService")))
                || "REMOTE_POSSIBLE".equals(stringOrNull(routing.get("recommendedDecision")))
                || "REMOTE_POSSIBLE".equals(stringOrNull(routing.get("supportDecision")));
    }

    private void auditTicketAction(
            String ticketId,
            Map<String, Object> current,
            CurrentUserService.CurrentUser admin,
            String action,
            String outcome
    ) {
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (
                  actor_user_id,
                  action,
                  target_type,
                  target_id,
                  metadata
                )
                VALUES (
                  ?,
                  ?,
                  'as_tickets',
                  ?,
                  jsonb_build_object(
                    'beforeStatus', CAST(? AS text),
                    'beforeReviewStatus', CAST(? AS text),
                    'beforeSupportDecision', CAST(? AS text),
                    'outcome', CAST(? AS text)
                  )
                )
                """,
                admin.internalId(),
                action,
                ticketId,
                DbValueMapper.string(current, "status"),
                DbValueMapper.string(current, "review_status"),
                DbValueMapper.string(current, "support_decision"),
                outcome
        );
    }

    private void auditRemoteSupportAction(
            String ticketId,
            CurrentUserService.CurrentUser actor,
            String action,
            String beforeStatus,
            String afterStatus
    ) {
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (
                  actor_user_id,
                  action,
                  target_type,
                  target_id,
                  metadata
                )
                VALUES (
                  ?,
                  ?,
                  'remote_support_sessions',
                  ?,
                  jsonb_build_object(
                    'beforeStatus', CAST(? AS text),
                    'afterStatus', CAST(? AS text)
                  )
                )
                """, actor.internalId(), action, ticketId, beforeStatus, afterStatus);
    }

    private void saveRemoteSupportIfRequested(
            String ticketId,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        if (request == null) {
            return;
        }
        String remoteSupportLink = stringOrNull(request.get("remoteSupportLink"));
        if (remoteSupportLink == null) {
            remoteSupportLink = stringOrNull(request.get("remoteSupportUrl"));
        }
        if (remoteSupportLink == null) {
            return;
        }
        validateRemoteSupportLink(remoteSupportLink);
        jdbcTemplate.update("""
                WITH input AS (
                  SELECT CAST(? AS text) AS session_url,
                         CAST(? AS bigint) AS admin_id
                ),
                target_ticket AS (
                  SELECT t.id,
                         lu.device_id
                  FROM as_tickets t
                  LEFT JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
                  WHERE t.public_id = ?::uuid
                    AND t.deleted_at IS NULL
                ),
                updated AS (
                  UPDATE remote_support_sessions rs
                  SET provider = 'EXTERNAL_LINK',
                      session_url = input.session_url,
                      status = CASE
                        WHEN rs.status = 'IN_PROGRESS' THEN 'IN_PROGRESS'
                        ELSE 'LINK_SENT'
                      END,
                      requested_by_admin_id = input.admin_id
                  FROM target_ticket tt,
                       input
                  WHERE rs.as_ticket_id = tt.id
                    AND rs.status IN ('REQUESTED', 'LINK_SENT', 'IN_PROGRESS')
                    AND rs.id = (
                      SELECT active.id
                      FROM remote_support_sessions active
                      WHERE active.as_ticket_id = tt.id
                        AND active.status IN ('REQUESTED', 'LINK_SENT', 'IN_PROGRESS')
                      ORDER BY active.created_at DESC, active.id DESC
                      LIMIT 1
                    )
                  RETURNING rs.id
                )
                INSERT INTO remote_support_sessions (
                  as_ticket_id,
                  device_id,
                  provider,
                  session_url,
                  status,
                  requested_by_admin_id
                )
                SELECT tt.id,
                       tt.device_id,
                       'EXTERNAL_LINK',
                       input.session_url,
                       'LINK_SENT',
                       input.admin_id
                FROM target_ticket tt
                CROSS JOIN input
                WHERE NOT EXISTS (SELECT 1 FROM updated)
                """, remoteSupportLink, admin == null ? null : admin.internalId(), ticketId);
    }

    private void finishRemoteSupportForTerminalTicket(String ticketId, String ticketStatus) {
        String remoteStatus = switch (ticketStatus) {
            case "RESOLVED", "CLOSED" -> "COMPLETED";
            case "CANCELLED" -> "CANCELLED";
            default -> null;
        };
        if (remoteStatus == null) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE remote_support_sessions rs
                SET status = ?,
                    ended_at = COALESCE(ended_at, now()),
                    ended_reason = COALESCE(ended_reason, ?),
                    access_code = NULL
                FROM as_tickets t
                WHERE rs.as_ticket_id = t.id
                  AND t.public_id = ?::uuid
                  AND rs.status IN ('REQUESTED', 'LINK_SENT', 'WAITING_FOR_CODE', 'CODE_READY', 'IN_PROGRESS')
                """, remoteStatus, "TICKET_" + ticketStatus, ticketId);
    }

    private static void validateRemoteSupportLinkIfPresent(Map<String, Object> request) {
        if (request == null) {
            return;
        }
        String remoteSupportLink = stringOrNull(request.get("remoteSupportLink"));
        if (remoteSupportLink == null) {
            remoteSupportLink = stringOrNull(request.get("remoteSupportUrl"));
        }
        if (remoteSupportLink != null) {
            validateRemoteSupportLink(remoteSupportLink);
        }
    }

    private void saveVisitSupportIfRequested(Map<String, Object> current, Map<String, Object> request) {
        if (request == null || !Boolean.TRUE.equals(booleanOrNull(request.get("visitSupportRequired")))) {
            return;
        }
        String timeSlot = stringOrNull(request.get("visitTimeSlot"));
        if (timeSlot == null) {
            timeSlot = "AFTERNOON";
        }
        validateNullable("visitTimeSlot", timeSlot, VISIT_TIME_SLOTS);
        LocalDate preferredDate = request.get("visitPreferredDate") == null
                ? LocalDate.now().plusDays(1)
                : parseDate("visitPreferredDate", request.get("visitPreferredDate"));
        jdbcTemplate.update("""
                INSERT INTO visit_support_reservations (
                  as_ticket_id,
                  user_id,
                  preferred_date,
                  time_slot,
                  status,
                  address_snapshot,
                  technician_note,
                  updated_at
                )
                VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?, now())
                """,
                longValue(current, "internal_id"),
                longValue(current, "user_id"),
                preferredDate,
                timeSlot,
                stringOrNull(request.get("visitAddressSnapshot")),
                stringOrNull(request.get("visitTechnicianNote"))
        );
    }

    private void auditTicketUpdate(
            String ticketId,
            Map<String, Object> current,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        if (request == null || admin == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (
                  actor_user_id,
                  action,
                  target_type,
                  target_id,
                  metadata
                )
                VALUES (
                  ?,
                  'AS_TICKET_UPDATED',
                  'as_tickets',
                  ?,
                  jsonb_build_object(
                    'beforeStatus', CAST(? AS text),
                    'afterStatus', COALESCE(CAST(? AS text), CAST(? AS text)),
                    'supportDecision', CAST(? AS text),
                    'beforeSupportDecision', CAST(? AS text),
                    'reviewStatus', CAST(? AS text),
                    'exceptionApprovalReason', CAST(? AS text),
                    'exceptionResponsibilityScope', CAST(? AS text),
                    'exceptionUserMessage', CAST(? AS text)
                  )
                )
                """,
                admin.internalId(),
                ticketId,
                DbValueMapper.string(current, "status"),
                stringOrNull(request.get("status")),
                DbValueMapper.string(current, "status"),
                stringOrNull(request.get("supportDecision")),
                DbValueMapper.string(current, "support_decision"),
                stringOrNull(request.get("reviewStatus")),
                stringOrNull(request.get("exceptionApprovalReason")),
                stringOrNull(request.get("exceptionResponsibilityScope")),
                stringOrNull(request.get("exceptionUserMessage"))
        );
    }

    private static boolean isUnsupportedException(String currentDecision, String requestedDecision) {
        return "UNSUPPORTED".equals(currentDecision)
                && requestedDecision != null
                && !"UNSUPPORTED".equals(requestedDecision);
    }

    private static boolean isRoutingException(Map<String, Object> current, String requestedDecision, Map<String, Object> request) {
        if (!hasOutOfScopeBlockingFactor(current)) {
            return false;
        }
        boolean remoteRequested = request != null && (stringOrNull(request.get("remoteSupportLink")) != null
                || stringOrNull(request.get("remoteSupportUrl")) != null);
        boolean visitRequested = request != null && Boolean.TRUE.equals(booleanOrNull(request.get("visitSupportRequired")));
        return remoteRequested
                || visitRequested
                || Set.of("REMOTE_POSSIBLE", "VISIT_REQUIRED", "REPAIR_OR_REPLACE").contains(requestedDecision);
    }

    private static void validateSupportExecutionPolicy(
            Map<String, Object> current,
            String requestedDecision,
            String requestedReviewStatus,
            Boolean autoResponseAllowed,
            Map<String, Object> request,
            boolean exceptionApproved
    ) {
        if (request == null) {
            return;
        }
        boolean remoteRequested = stringOrNull(request.get("remoteSupportLink")) != null
                || stringOrNull(request.get("remoteSupportUrl")) != null;
        boolean visitRequested = Boolean.TRUE.equals(booleanOrNull(request.get("visitSupportRequired")));
        if (remoteRequested && visitRequested) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "원격지원과 방문지원은 한 번에 동시에 생성할 수 없습니다.");
        }
        String currentDecision = DbValueMapper.string(current, "support_decision");
        String targetDecision = requestedDecision == null ? currentDecision : requestedDecision;
        String effectiveReviewStatus = requestedReviewStatus;
        if (effectiveReviewStatus == null && requestedDecision != null) {
            effectiveReviewStatus = "APPROVED";
        }
        if (effectiveReviewStatus == null) {
            effectiveReviewStatus = DbValueMapper.string(current, "review_status");
        }
        if (Boolean.TRUE.equals(autoResponseAllowed) && !"APPROVED".equals(effectiveReviewStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "관리자 승인 전 자동 응답을 허용할 수 없습니다.");
        }
        if (remoteRequested && (!"APPROVED".equals(effectiveReviewStatus) || !"REMOTE_POSSIBLE".equals(targetDecision))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "원격지원 링크는 승인된 REMOTE_POSSIBLE 티켓에만 저장할 수 있습니다.");
        }
        if (visitRequested && (!"APPROVED".equals(effectiveReviewStatus) || !Set.of("VISIT_REQUIRED", "REPAIR_OR_REPLACE").contains(targetDecision))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "방문 예약은 승인된 VISIT_REQUIRED 티켓에만 생성할 수 있습니다.");
        }
        if ((remoteRequested || visitRequested)
                && ("UNSUPPORTED".equals(targetDecision) || "UNSUPPORTED".equals(currentDecision) || hasOutOfScopeBlockingFactor(current))
                && !exceptionApproved) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "지원 범위 밖 티켓은 예외 승인 전 원격/방문 예약을 만들 수 없습니다.");
        }
    }

    private static boolean hasOutOfScopeBlockingFactor(Map<String, Object> current) {
        Object value = DbValueMapper.json(current, "support_routing", Map.of());
        if (!(value instanceof Map<?, ?> routing)) {
            return false;
        }
        Object factors = routing.get("blockingFactors");
        if (!(factors instanceof List<?> blockingFactors)) {
            return false;
        }
        return blockingFactors.stream()
                .map(String::valueOf)
                .anyMatch(Set.of(
                        "OUT_OF_SCOPE",
                        "UNSUPPORTED_SCOPE",
                        "OUT_OF_PC_SCOPE",
                        "DATA_RECOVERY_REQUIRED",
                        "UNSUPPORTED_SOFTWARE",
                        "PHYSICAL_DAMAGE_POLICY_REQUIRED"
                )::contains);
    }

    private static void requireExceptionField(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 필요합니다.");
        }
    }

    private void assignAdmin(String ticketId, String assignedAdminId) {
        validatePublicUuid("assignedAdminId", assignedAdminId);
        Map<String, Object> adminRow = jdbcTemplate.queryForList("""
                        SELECT id
                        FROM users
                        WHERE public_id = ?::uuid
                          AND role = 'ADMIN'
                        """, assignedAdminId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignedAdminId must reference an ADMIN user."));
        jdbcTemplate.update("""
                UPDATE as_tickets
                SET assigned_admin_id = ?, updated_at = now()
                WHERE public_id = ?::uuid
                """, longValue(adminRow, "id"), ticketId);
    }

    private String ticketSql() {
        return """
                SELECT t.public_id::text AS id,
                       user_owner.public_id::text AS user_id,
                       user_owner.email AS user_email,
                       user_owner.name AS user_name,
                       t.status,
                       t.analysis_status,
                       t.review_status,
                       t.support_decision,
                       t.risk_level,
                       t.auto_response_allowed,
                       t.symptom,
                       t.request_number,
                       t.request_type,
                       t.diagnosis_id::text AS diagnosis_id,
                       t.diagnosis_mode,
                       t.diagnosis_title,
                       t.diagnosis_summary,
                       t.evidence_summary AS diagnosis_evidence,
                       t.diagnosis_result,
                       COALESCE((
                         SELECT jsonb_agg(
                           jsonb_build_object(
                             'eventId', event.event_id,
                             'taskId', event.task_id,
                             'eventType', event.event_type,
                             'status', event.status,
                             'progressPercent', event.progress_percent,
                             'message', event.message,
                             'occurredAt', event.occurred_at
                           ) ORDER BY event.occurred_at ASC, event.id ASC
                         )
                         FROM pc_agent_diagnosis_events event
                         WHERE event.diagnosis_id = t.diagnosis_id
                       ), '[]'::jsonb) AS diagnosis_events,
                       t.diagnosed_at,
                       lu.public_id::text AS log_upload_id,
                       lu.summary AS uploaded_log_summary,
                       als.public_id::text AS log_summary_id,
                       als.summary_payload AS log_summary_payload,
                       als.feature_payload AS log_feature_payload,
                       als.risk_flags AS log_risk_flags,
                       atl.public_id::text AS as_label_id,
                       atl.failure_category AS as_label_failure_category,
                       atl.severity AS as_label_severity,
                       related_part.public_id::text AS as_label_related_part_id,
                       atl.recommendation_id AS as_label_recommendation_id,
                       atl.use_for_recommendation_training AS as_label_use_for_recommendation_training,
                       atl.note AS as_label_note,
                       room.public_id::text AS support_chat_room_id,
                       room.user_unread_count AS support_chat_user_unread_count,
                       room.admin_unread_count AS support_chat_admin_unread_count,
                       room.last_message_at AS support_chat_last_message_at,
                       admin.public_id::text AS assigned_admin_id,
                       t.cause_candidates,
                       t.upgrade_candidates,
                       t.incident_window,
                       t.log_summary,
                       t.support_routing,
                       t.safety_advice_level,
                       t.safety_notices,
                       COALESCE(t.log_summary->>'summaryText', lu.summary) AS log_summary_text,
                       t.admin_note,
                       t.ai_diagnosis_request,
                       t.exception_approval_reason,
                       t.exception_responsibility_scope,
                       t.exception_user_message,
                       t.exception_approved_at,
                       t.feedback_rating,
                       t.feedback_comment,
                       t.feedback_created_at,
                       t.diagnostic_accuracy,
                       t.resolved_at,
                       t.reviewed_at,
                       t.created_at,
                       t.updated_at,
                       rs.session_url AS remote_support_link,
                       rs.status AS remote_support_status,
                       rs.access_code_registered_at AS remote_access_code_registered_at,
                       rs.started_at AS remote_support_started_at,
                       rs.ended_at AS remote_support_completed_at,
                       vr.public_id::text AS visit_support_id,
                       vr.status AS visit_support_status,
                       vr.preferred_date AS visit_preferred_date,
                       vr.time_slot AS visit_time_slot
                FROM as_tickets t
                JOIN users user_owner ON user_owner.id = t.user_id
                LEFT JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
                LEFT JOIN agent_log_summaries als ON als.as_ticket_id = t.id
                LEFT JOIN as_ticket_labels atl ON atl.as_ticket_id = t.id
                LEFT JOIN parts related_part ON related_part.id = atl.related_part_id
                LEFT JOIN support_chat_rooms room
                  ON room.as_ticket_id = t.id
                 AND room.user_id = t.user_id
                 AND room.status = 'ACTIVE'
                 AND room.deleted_at IS NULL
                LEFT JOIN users admin ON admin.id = t.assigned_admin_id
                LEFT JOIN LATERAL (
                  SELECT session_url,
                         status,
                         access_code_registered_at,
                         started_at,
                         ended_at
                  FROM remote_support_sessions
                  WHERE as_ticket_id = t.id
                  ORDER BY created_at DESC, id DESC
                  LIMIT 1
                ) rs ON true
                LEFT JOIN LATERAL (
                  SELECT public_id, status, preferred_date, time_slot
                  FROM visit_support_reservations
                  WHERE as_ticket_id = t.id
                  ORDER BY created_at DESC, id DESC
                  LIMIT 1
                ) vr ON true
                """;
    }

    private Map<String, Object> ticketMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "userId", DbValueMapper.string(row, "user_id"),
                "userEmail", DbValueMapper.string(row, "user_email"),
                "userName", DbValueMapper.string(row, "user_name"),
                "status", DbValueMapper.string(row, "status"),
                "analysisStatus", DbValueMapper.string(row, "analysis_status"),
                "reviewStatus", DbValueMapper.string(row, "review_status"),
                "supportDecision", DbValueMapper.string(row, "support_decision"),
                "riskLevel", DbValueMapper.string(row, "risk_level"),
                "autoResponseAllowed", row.get("auto_response_allowed"),
                "symptom", DbValueMapper.string(row, "symptom"),
                "title", DbValueMapper.string(row, "diagnosis_title"),
                "description", DbValueMapper.string(row, "diagnosis_summary"),
                "requestNumber", DbValueMapper.string(row, "request_number"),
                "requestType", DbValueMapper.string(row, "request_type"),
                "diagnosisId", DbValueMapper.string(row, "diagnosis_id"),
                "diagnosisMode", DbValueMapper.string(row, "diagnosis_mode"),
                "diagnosisTitle", DbValueMapper.string(row, "diagnosis_title"),
                "diagnosisSummary", DbValueMapper.string(row, "diagnosis_summary"),
                "diagnosisEvidence", DbValueMapper.json(row, "diagnosis_evidence", List.of()),
                "diagnosisResult", DbValueMapper.json(row, "diagnosis_result", null),
                "diagnosisEvents", DbValueMapper.json(row, "diagnosis_events", List.of()),
                "diagnosedAt", DbValueMapper.timestamp(row, "diagnosed_at"),
                "logUploadId", DbValueMapper.string(row, "log_upload_id"),
                "uploadedLogSummary", DbValueMapper.string(row, "uploaded_log_summary"),
                "logSummaryId", DbValueMapper.string(row, "log_summary_id"),
                "logSummaryPayload", DbValueMapper.json(row, "log_summary_payload", Map.of()),
                "logFeaturePayload", DbValueMapper.json(row, "log_feature_payload", Map.of()),
                "logRiskFlags", DbValueMapper.json(row, "log_risk_flags", Map.of()),
                "asTrainingLabel", asTrainingLabel(row),
                "supportChatRoomId", DbValueMapper.string(row, "support_chat_room_id"),
                "supportChatUserUnreadCount", numberInt(row.get("support_chat_user_unread_count")),
                "supportChatAdminUnreadCount", numberInt(row.get("support_chat_admin_unread_count")),
                "supportChatLastMessageAt", DbValueMapper.timestamp(row, "support_chat_last_message_at"),
                "assignedAdminId", DbValueMapper.string(row, "assigned_admin_id"),
                "causeCandidates", DbValueMapper.json(row, "cause_candidates", List.of()),
                "upgradeCandidates", DbValueMapper.json(row, "upgrade_candidates", List.of()),
                "incidentWindow", DbValueMapper.json(row, "incident_window", null),
                "logSummary", DbValueMapper.json(row, "log_summary", null),
                "supportRouting", DbValueMapper.json(row, "support_routing", null),
                "safetyAdviceLevel", DbValueMapper.string(row, "safety_advice_level"),
                "safetyNotices", DbValueMapper.json(row, "safety_notices", List.of()),
                "logSummaryText", DbValueMapper.string(row, "log_summary_text"),
                "adminNote", DbValueMapper.string(row, "admin_note"),
                "aiDiagnosisRequest", DbValueMapper.json(row, "ai_diagnosis_request", Map.of()),
                "exceptionApprovalReason", DbValueMapper.string(row, "exception_approval_reason"),
                "exceptionResponsibilityScope", DbValueMapper.string(row, "exception_responsibility_scope"),
                "exceptionUserMessage", DbValueMapper.string(row, "exception_user_message"),
                "exceptionApprovedAt", DbValueMapper.timestamp(row, "exception_approved_at"),
                "feedbackRating", row.get("feedback_rating"),
                "feedbackComment", DbValueMapper.string(row, "feedback_comment"),
                "feedbackCreatedAt", DbValueMapper.timestamp(row, "feedback_created_at"),
                "diagnosticAccuracy", DbValueMapper.string(row, "diagnostic_accuracy"),
                "remoteSupportLink", DbValueMapper.string(row, "remote_support_link"),
                "remoteSupportStatus", DbValueMapper.string(row, "remote_support_status"),
                "remoteAccessCodeRegisteredAt", DbValueMapper.timestamp(row, "remote_access_code_registered_at"),
                "remoteSupportStartedAt", DbValueMapper.timestamp(row, "remote_support_started_at"),
                "remoteSupportCompletedAt", DbValueMapper.timestamp(row, "remote_support_completed_at"),
                "visitSupportRequired", row.get("visit_support_id") != null,
                "visitSupportStatus", DbValueMapper.string(row, "visit_support_status"),
                "visitPreferredDate", row.get("visit_preferred_date"),
                "visitTimeSlot", DbValueMapper.string(row, "visit_time_slot"),
                "resolvedAt", DbValueMapper.timestamp(row, "resolved_at"),
                "reviewedAt", DbValueMapper.timestamp(row, "reviewed_at"),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "updatedAt", DbValueMapper.timestamp(row, "updated_at")
        );
    }

    private Map<String, Object> adminTicketMap(Map<String, Object> row) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(ticketMap(row));
        result.put("diagnosisResult", DbValueMapper.json(row, "diagnosis_result", Map.of()));
        return result;
    }

    private Map<String, Object> asTrainingLabel(Map<String, Object> row) {
        String labelId = DbValueMapper.string(row, "as_label_id");
        if (labelId == null) {
            return null;
        }
        return MockData.map(
                "id", labelId,
                "failureCategory", DbValueMapper.string(row, "as_label_failure_category"),
                "severity", DbValueMapper.string(row, "as_label_severity"),
                "relatedPartId", DbValueMapper.string(row, "as_label_related_part_id"),
                "recommendationId", DbValueMapper.string(row, "as_label_recommendation_id"),
                "useForRecommendationTraining", row.get("as_label_use_for_recommendation_training"),
                "note", DbValueMapper.string(row, "as_label_note")
        );
    }

    private record TicketAnalysisDraft(
            String analysisStatus,
            String reviewStatus,
            String supportDecision,
            String riskLevel,
            List<Map<String, Object>> causeCandidates,
            List<Map<String, Object>> upgradeCandidates,
            String adminNote,
            Map<String, Object> logSummary,
            Map<String, Object> supportRouting,
            Map<String, Object> aiDiagnosisRequest
    ) {
    }

    private static void validateStatusTransition(String before, String after) {
        validateNullable("status", after, TICKET_STATUSES);
        boolean allowed = switch (before) {
            case "OPEN" -> Set.of("ASSIGNED", "IN_PROGRESS", "RESOLVED", "CANCELLED").contains(after);
            case "ASSIGNED" -> Set.of("ASSIGNED", "IN_PROGRESS", "RESOLVED", "CANCELLED").contains(after);
            case "IN_PROGRESS" -> Set.of("ASSIGNED", "RESOLVED", "CANCELLED").contains(after);
            case "RESOLVED" -> "CLOSED".equals(after);
            default -> false;
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AS 티켓 상태 전이가 허용되지 않습니다.");
        }
    }

    private static void validateNullable(String fieldName, String value, Set<String> allowedValues) {
        if (value != null && !allowedValues.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 올바르지 않습니다.");
        }
    }

    private static Boolean parseBoolean(String fieldName, Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = value == null ? null : value.toString();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 올바르지 않습니다.");
    }

    private static int parseInteger(String fieldName, Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 올바르지 않습니다.", exception);
        }
    }

    private static LocalDate parseDate(String fieldName, Object value) {
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 올바르지 않습니다.", exception);
        }
    }

    private static void validateRemoteSupportLink(String value) {
        if (value.length() > 2_000 || value.chars().anyMatch(Character::isWhitespace)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "remoteSupportLink 값이 올바르지 않습니다.");
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase();
            if (uri.getHost() == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "remoteSupportLink 값이 올바르지 않습니다.");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "remoteSupportLink 값이 올바르지 않습니다.", exception);
        }
    }

    private static Boolean booleanOrNull(Object value) {
        return value == null ? null : parseBoolean("boolean", value);
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static Integer numberInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? 0 : Integer.valueOf(value.toString());
    }

    private static void validatePublicUuid(String fieldName, String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be a UUID.");
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
