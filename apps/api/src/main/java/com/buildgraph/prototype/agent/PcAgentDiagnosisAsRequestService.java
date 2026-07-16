package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.ticket.SupportChatRoomProvisioner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PcAgentDiagnosisAsRequestService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String REQUEST_TYPE = "PHYSICAL_INSPECTION";

    private final JdbcTemplate jdbcTemplate;
    private final PcAgentDiagnosisSocketBroker diagnosisBroker;
    private final SupportChatRoomProvisioner supportChatRoomProvisioner;

    public PcAgentDiagnosisAsRequestService(
            JdbcTemplate jdbcTemplate,
            PcAgentDiagnosisSocketBroker diagnosisBroker,
            SupportChatRoomProvisioner supportChatRoomProvisioner
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.diagnosisBroker = diagnosisBroker;
        this.supportChatRoomProvisioner = supportChatRoomProvisioner;
    }

    @Transactional
    public Map<String, Object> create(AgentPrincipal principal, CreateRequest request, String idempotencyKey) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Agent authentication is required.");
        }
        if (request == null) {
            throw invalid("AS request body is required.");
        }
        String diagnosisId = required(request.diagnosisId(), "diagnosisId");
        validateUuid(diagnosisId);
        if (!Objects.equals(diagnosisId, required(idempotencyKey, "Idempotency-Key"))) {
            throw invalid("Idempotency-Key must match diagnosisId.");
        }
        String deviceId = required(request.deviceId(), "deviceId");
        if (!Objects.equals(deviceId, principal.deviceId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_MISMATCH", "AS request device does not match the authenticated Agent.");
        }
        if (!Boolean.TRUE.equals(request.consentAccepted())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED", "Diagnostic information transfer consent is required.");
        }

        Map<String, Object> existing = existingRequest(principal, diagnosisId);
        if (existing != null) {
            return existingResponse(existing, principal.userInternalId());
        }

        PcAgentDiagnosisSocketBroker.DiagnosisResultRecord resultRecord = diagnosisBroker.latestResult(diagnosisId);
        PcAgentDiagnosisRequest diagnosisRequest = diagnosisBroker.latestRequest(diagnosisId);
        if (diagnosisRequest == null) {
            diagnosisRequest = storedDiagnosisRequest(principal, diagnosisId);
        }
        ValidatedDiagnosis diagnosis = validateDiagnosis(principal, request, resultRecord, diagnosisRequest);

        lockUser(principal);
        lockDevice(principal);
        existing = existingRequest(principal, diagnosisId);
        if (existing != null) {
            return existingResponse(existing, principal.userInternalId());
        }
        requireStoredConsent(principal);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  diagnosis_id,
                  agent_device_id,
                  user_id,
                  request_number,
                  request_type,
                  symptom,
                  diagnosis_title,
                  diagnosis_summary,
                  evidence_summary,
                  diagnosed_at,
                  diagnosis_mode,
                  diagnosis_result,
                  diagnosis_consent_accepted_at,
                  status,
                  analysis_status,
                  review_status,
                  support_decision,
                  risk_level,
                  auto_response_allowed,
                  cause_candidates,
                  upgrade_candidates,
                  admin_note,
                  updated_at
                )
                VALUES (
                  ?::uuid,
                  ?,
                  ?,
                  'AS-' || to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul', 'YYYYMMDD')
                    || '-' || lpad(nextval('as_ticket_request_number_seq')::text, 6, '0'),
                  ?,
                  ?,
                  ?,
                  ?,
                  ?::jsonb,
                  ?,
                  ?,
                  ?::jsonb,
                  now(),
                  'OPEN',
                  'RULE_READY',
                  'REQUIRED',
                  'VISIT_REQUIRED',
                  ?,
                  false,
                  ?::jsonb,
                  '[]'::jsonb,
                  ?,
                  now()
                )
                RETURNING id AS ticket_internal_id,
                          public_id::text AS request_id,
                          request_number,
                          status,
                          request_type,
                          symptom,
                          diagnosis_title,
                          diagnosis_summary,
                          evidence_summary,
                          diagnosed_at,
                          diagnosis_mode,
                          diagnosis_consent_accepted_at,
                          created_at
                """,
                diagnosisId,
                principal.deviceInternalId(),
                principal.userInternalId(),
                REQUEST_TYPE,
                diagnosis.symptom(),
                diagnosis.title(),
                diagnosis.summary(),
                toJson(diagnosis.evidence()),
                Timestamp.from(diagnosis.diagnosedAt()),
                diagnosis.mode(),
                toJson(diagnosis.result()),
                diagnosis.riskLevel(),
                toJson(diagnosis.findings()),
                diagnosis.summary()
        );
        return response(row, principal.userInternalId());
    }

    private ValidatedDiagnosis validateDiagnosis(
            AgentPrincipal principal,
            CreateRequest request,
            PcAgentDiagnosisSocketBroker.DiagnosisResultRecord resultRecord,
            PcAgentDiagnosisRequest diagnosisRequest
    ) {
        if (resultRecord == null || diagnosisRequest == null) {
            throw new ApiException(HttpStatus.CONFLICT, "DIAGNOSIS_RESULT_NOT_FOUND", "A completed diagnosis result is required before creating an AS request.");
        }
        if (!Objects.equals(resultRecord.deviceId(), principal.deviceId())
                || !Objects.equals(diagnosisRequest.deviceId(), principal.deviceId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_MISMATCH", "Diagnosis result does not belong to the authenticated Agent.");
        }
        Map<String, Object> result = resultRecord.result();
        String resultId = required(request.resultId(), "resultId");
        if (!Objects.equals(resultId, resultRecord.resultId())
                || !Objects.equals(resultId, text(result.get("resultId")))) {
            throw invalid("resultId does not match the stored diagnosis result.");
        }
        // 진단 결과가 정상이거나 근거가 부족해도 사용자가 AS를 접수할 수 있다.
        // 접수 유형만 PHYSICAL_INSPECTION으로 고정하고, 결과의 resolutionType은 제한하지 않는다.
        if (!REQUEST_TYPE.equals(required(request.requestType(), "requestType"))) {
            throw new ApiException(HttpStatus.CONFLICT, "AS_NOT_ELIGIBLE", "Only PHYSICAL_INSPECTION AS requests are supported.");
        }
        List<?> storedEvidence = list(result.get("evidence"));
        if (storedEvidence.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "AS_NOT_ELIGIBLE", "Diagnosis evidence is required before creating an AS request.");
        }
        if (request.evidenceSummary() == null || request.evidenceSummary().isEmpty()) {
            throw invalid("evidenceSummary is required.");
        }
        List<Map<String, Object>> evidence = validatedEvidenceSummary(request.evidenceSummary(), storedEvidence);

        String symptom = required(request.symptom(), "symptom");
        if (!Objects.equals(symptom, diagnosisRequest.symptom())) {
            throw invalid("symptom does not match the server diagnosis request.");
        }
        String title = required(request.diagnosisTitle(), "diagnosisTitle");
        String summary = required(request.diagnosisSummary(), "diagnosisSummary");
        if (!Objects.equals(title, text(result.get("title")))
                || !Objects.equals(summary, text(result.get("summary")))) {
            throw invalid("Diagnosis title or summary does not match the stored result.");
        }
        String mode = required(request.mode(), "mode").toUpperCase(java.util.Locale.ROOT);
        if (!Objects.equals(mode, diagnosisRequest.mode())) {
            throw invalid("mode does not match the server diagnosis request.");
        }
        Instant diagnosedAt = timestamp(required(request.diagnosedAt(), "diagnosedAt"), "diagnosedAt");
        Instant evaluatedAt = timestamp(required(text(result.get("evaluatedAt")), "evaluatedAt"), "evaluatedAt");
        if (!diagnosedAt.equals(evaluatedAt)) {
            throw invalid("diagnosedAt does not match the stored result.");
        }
        List<?> findings = list(result.get("findings"));
        String severity = text(result.get("severity"));
        return new ValidatedDiagnosis(
                symptom,
                title,
                summary,
                evidence,
                findings,
                diagnosedAt,
                mode,
                riskLevel(severity),
                result
        );
    }

    private PcAgentDiagnosisRequest storedDiagnosisRequest(AgentPrincipal principal, String diagnosisId) {
        return jdbcTemplate.queryForList("""
                        SELECT d.public_id::text AS device_id,
                               r.symptom,
                               r.requested_checks,
                               r.requested_at,
                               r.expires_at,
                               r.mode
                        FROM pc_agent_diagnosis_requests r
                        JOIN agent_devices d ON d.id = r.agent_device_id
                        WHERE r.diagnosis_id = ?::uuid
                          AND r.agent_device_id = ?
                          AND r.user_id = ?
                        """,
                        diagnosisId,
                        principal.deviceInternalId(),
                        principal.userInternalId()
                )
                .stream()
                .findFirst()
                .map(row -> new PcAgentDiagnosisRequest(
                        diagnosisId,
                        DbValueMapper.string(row, "device_id"),
                        DbValueMapper.string(row, "symptom"),
                        stringList(DbValueMapper.json(row, "requested_checks", List.of())),
                        timestamp(String.valueOf(DbValueMapper.timestamp(row, "requested_at")), "requestedAt"),
                        timestamp(String.valueOf(DbValueMapper.timestamp(row, "expires_at")), "expiresAt"),
                        DbValueMapper.string(row, "mode")
                ))
                .orElse(null);
    }

    private Map<String, Object> existingRequest(AgentPrincipal principal, String diagnosisId) {
        return jdbcTemplate.queryForList("""
                        SELECT t.id AS ticket_internal_id,
                               t.public_id::text AS request_id,
                               t.request_number,
                               t.status,
                               t.request_type,
                               t.symptom,
                               t.diagnosis_title,
                               t.diagnosis_summary,
                               t.evidence_summary,
                               t.diagnosed_at,
                               t.diagnosis_mode,
                               t.diagnosis_consent_accepted_at,
                               t.created_at,
                               t.deleted_at
                        FROM as_tickets t
                        WHERE t.diagnosis_id = ?::uuid
                          AND t.agent_device_id = ?
                          AND t.user_id = ?
                        """,
                        diagnosisId,
                        principal.deviceInternalId(),
                        principal.userInternalId()
                )
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> existingResponse(Map<String, Object> row, Long userInternalId) {
        if (row.get("deleted_at") != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "AS_REQUEST_ALREADY_EXISTS",
                    "This diagnosis already has a deleted AS request and cannot be submitted again.",
                    MockData.map(
                            "requestId", DbValueMapper.string(row, "request_id"),
                            "requestNumber", DbValueMapper.string(row, "request_number")
                    )
            );
        }
        return response(row, userInternalId);
    }

    private void lockDevice(AgentPrincipal principal) {
        if (jdbcTemplate.queryForList("""
                        SELECT id
                        FROM agent_devices
                        WHERE id = ?
                          AND public_id = ?::uuid
                          AND user_id = ?
                          AND status IN ('ACTIVE', 'UPDATE_REQUIRED')
                        FOR UPDATE
                        """,
                        principal.deviceInternalId(),
                        principal.deviceId(),
                        principal.userInternalId()
                ).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_MISMATCH", "Agent device is not active for the authenticated user.");
        }
    }

    private void lockUser(AgentPrincipal principal) {
        if (jdbcTemplate.queryForList("""
                        SELECT id
                        FROM users
                        WHERE id = ?
                          AND deleted_at IS NULL
                        FOR UPDATE
                        """, principal.userInternalId()).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Authenticated Agent user was not found.");
        }
    }

    private static List<Map<String, Object>> validatedEvidenceSummary(
            List<Map<String, Object>> submitted,
            List<?> stored
    ) {
        List<Map<String, Object>> validated = new java.util.ArrayList<>();
        for (Map<String, Object> item : submitted) {
            if (item == null
                    || text(item.get("component")) == null
                    || text(item.get("metricType")) == null
                    || item.get("value") == null
                    || text(item.get("source")) == null
                    || text(item.get("sampledAt")) == null) {
                throw invalid("evidenceSummary contains an invalid measurement.");
            }
            boolean matched = stored.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .anyMatch(candidate -> evidenceMatches(item, candidate));
            if (!matched) {
                throw invalid("evidenceSummary does not match the stored diagnosis result.");
            }
            validated.add(Map.copyOf(item));
        }
        return List.copyOf(validated);
    }

    private static boolean evidenceMatches(Map<String, Object> submitted, Map<?, ?> stored) {
        return "AVAILABLE".equals(text(stored.get("availability")))
                && Objects.equals(text(submitted.get("component")), text(stored.get("component")))
                && Objects.equals(text(submitted.get("metricType")), text(stored.get("metricType")))
                && Objects.equals(text(submitted.get("unit")), text(stored.get("unit")))
                && Objects.equals(text(submitted.get("status")), text(stored.get("status")))
                && Objects.equals(text(submitted.get("source")), text(stored.get("source")))
                && Objects.equals(text(submitted.get("sampledAt")), text(stored.get("sampledAt")))
                && numericValueEquals(submitted.get("value"), stored.get("value"));
    }

    private static boolean numericValueEquals(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return Objects.equals(left, right);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    private void requireStoredConsent(AgentPrincipal principal) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM agent_consents
                WHERE device_id = ?
                  AND user_id = ?
                  AND consent_type = 'SERVER_UPLOAD'
                  AND accepted = true
                  AND revoked_at IS NULL
                """, Integer.class, principal.deviceInternalId(), principal.userInternalId());
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED", "Stored diagnostic information transfer consent is required.");
        }
    }

    private Map<String, Object> response(Map<String, Object> row, Long userInternalId) {
        Long ticketInternalId = longValue(row, "ticket_internal_id");
        String requestId = DbValueMapper.string(row, "request_id");
        String supportChatRoomId = supportChatRoomProvisioner.ensureRoom(userInternalId, ticketInternalId);
        return MockData.map(
                "requestId", requestId,
                "requestNumber", DbValueMapper.string(row, "request_number"),
                "status", DbValueMapper.string(row, "status"),
                "requestType", DbValueMapper.string(row, "request_type"),
                "symptom", DbValueMapper.string(row, "symptom"),
                "diagnosisTitle", DbValueMapper.string(row, "diagnosis_title"),
                "diagnosisSummary", DbValueMapper.string(row, "diagnosis_summary"),
                "evidenceSummary", DbValueMapper.json(row, "evidence_summary", List.of()),
                "diagnosedAt", DbValueMapper.timestamp(row, "diagnosed_at"),
                "mode", DbValueMapper.string(row, "diagnosis_mode"),
                "consentAcceptedAt", DbValueMapper.timestamp(row, "diagnosis_consent_accepted_at"),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "supportChatRoomId", supportChatRoomId,
                "webPath", "/support/" + requestId
        );
    }

    private static String required(String value, String field) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isBlank()) {
            throw invalid(field + " is required.");
        }
        return text;
    }

    private static void validateUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw invalid("diagnosisId must be a UUID.");
        }
    }

    private static Instant timestamp(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException error) {
                throw invalid(field + " must be an ISO-8601 timestamp.");
            }
        }
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Diagnosis AS request JSON serialization failed.", error);
        }
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static String riskLevel(String severity) {
        if ("CRITICAL".equals(severity)) {
            return "HIGH";
        }
        // 이상 근거가 없는 결과(정상·측정 정보 부족)로도 접수할 수 있으므로 위험도를 낮게 잡는다.
        if ("NORMAL".equals(severity) || "INDETERMINATE".equals(severity)) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AS_REQUEST", message);
    }

    public record CreateRequest(
            String diagnosisId,
            String resultId,
            String deviceId,
            String requestType,
            String symptom,
            String diagnosisTitle,
            String diagnosisSummary,
            List<Map<String, Object>> evidenceSummary,
            String diagnosedAt,
            String mode,
            Boolean consentAccepted
    ) {
    }

    private record ValidatedDiagnosis(
            String symptom,
            String title,
            String summary,
            List<Map<String, Object>> evidence,
            List<?> findings,
            Instant diagnosedAt,
            String mode,
            String riskLevel,
            Map<String, Object> result
    ) {
    }
}
