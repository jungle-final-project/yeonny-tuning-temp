package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PcAgentDiagnosisRequestService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_CHECKS = Set.of("cpu", "gpu", "memory", "disk", "cooling");
    private static final Set<String> ALLOWED_MODES = Set.of("LIVE", "DEMO");
    private static final Duration REQUEST_TTL = Duration.ofMinutes(2);

    private final JdbcTemplate jdbcTemplate;
    private final PcAgentDiagnosisSocketBroker broker;
    private final Clock clock;
    private final Supplier<UUID> diagnosisIdSupplier;

    @Autowired
    public PcAgentDiagnosisRequestService(JdbcTemplate jdbcTemplate, PcAgentDiagnosisSocketBroker broker) {
        this(jdbcTemplate, broker, Clock.systemUTC(), UUID::randomUUID);
    }

    PcAgentDiagnosisRequestService(
            JdbcTemplate jdbcTemplate,
            PcAgentDiagnosisSocketBroker broker,
            Clock clock,
            Supplier<UUID> diagnosisIdSupplier
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.broker = broker;
        this.clock = clock;
        this.diagnosisIdSupplier = diagnosisIdSupplier;
    }

    public Map<String, Object> create(CurrentUserService.CurrentUser user, CreateRequest body) {
        String symptom = requiredText(body == null ? null : body.symptom(), "증상을 입력해 주세요.");
        if (symptom.length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSIS_REQUEST", "증상은 2000자 이하로 입력해 주세요.");
        }
        List<String> requestedChecks = normalizeChecks(body.requestedChecks());
        String mode = requiredText(body.mode(), "진단 모드가 필요합니다.").toUpperCase(Locale.ROOT);
        if (!ALLOWED_MODES.contains(mode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSIS_REQUEST", "진단 모드는 LIVE 또는 DEMO여야 합니다.");
        }
        String deviceId = connectedDeviceId(user.internalId());
        Instant requestedAt = Instant.now(clock);
        PcAgentDiagnosisRequest request = new PcAgentDiagnosisRequest(
                diagnosisIdSupplier.get().toString(),
                deviceId,
                symptom,
                requestedChecks,
                requestedAt,
                requestedAt.plus(REQUEST_TTL),
                mode
        );
        storeRequest(user.internalId(), request);
        PcAgentDiagnosisSocketBroker.AgentResponse response;
        try {
            response = broker.dispatchAndAwait(request);
            markAgentResponse(request.diagnosisId(), response.status());
        } catch (RuntimeException error) {
            markDispatchFailed(request.diagnosisId(), error);
            throw error;
        }
        return MockData.map(
                "diagnosisId", request.diagnosisId(),
                "deviceId", request.deviceId(),
                "requestedAt", request.requestedAt().toString(),
                "expiresAt", request.expiresAt().toString(),
                "mode", request.mode(),
                "status", response.status(),
                "message", response.message()
        );
    }

    private void markAgentResponse(String diagnosisId, String status) {
        int updated = jdbcTemplate.update("""
                UPDATE pc_agent_diagnosis_requests
                SET request_status = ?,
                    connection_status = 'CONNECTED',
                    accepted_at = CASE
                      WHEN ? IN ('ACCEPTED', 'DUPLICATE') THEN COALESCE(accepted_at, now())
                      ELSE accepted_at
                    END,
                    updated_at = now()
                WHERE diagnosis_id = ?::uuid
                """, status, status, diagnosisId);
        if (updated != 1) {
            throw new IllegalStateException("Stored PC Agent diagnosis request was not found.");
        }
    }

    private void markDispatchFailed(String diagnosisId, RuntimeException error) {
        String connectionStatus = error instanceof ApiException apiError
                && Set.of("AGENT_DISCONNECTED", "AGENT_CONNECTION_FAILED").contains(apiError.code())
                ? "DISCONNECTED"
                : "CONNECTED";
        jdbcTemplate.update("""
                UPDATE pc_agent_diagnosis_requests
                SET request_status = 'DISPATCH_FAILED',
                    connection_status = ?,
                    updated_at = now()
                WHERE diagnosis_id = ?::uuid
                """, connectionStatus, diagnosisId);
    }

    private void storeRequest(Long userInternalId, PcAgentDiagnosisRequest request) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO pc_agent_diagnosis_requests (
                  diagnosis_id,
                  user_id,
                  agent_device_id,
                  symptom,
                  requested_checks,
                  requested_at,
                  expires_at,
                  mode
                )
                SELECT ?::uuid, ?, d.id, ?, ?::jsonb, ?, ?, ?
                FROM agent_devices d
                WHERE d.public_id = ?::uuid
                  AND d.user_id = ?
                  AND d.status IN ('ACTIVE', 'UPDATE_REQUIRED')
                ON CONFLICT (diagnosis_id) DO NOTHING
                """,
                request.diagnosisId(),
                userInternalId,
                request.symptom(),
                toJson(request.requestedChecks()),
                Timestamp.from(request.requestedAt()),
                Timestamp.from(request.expiresAt()),
                request.mode(),
                request.deviceId(),
                userInternalId
        );
        if (inserted != 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DUPLICATE_DIAGNOSIS_ID",
                    "진단 요청 식별자가 이미 사용되었거나 장치가 유효하지 않습니다."
            );
        }
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Diagnosis request JSON serialization failed.", error);
        }
    }

    private String connectedDeviceId(Long userInternalId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT public_id::text AS device_id
                FROM agent_devices
                WHERE user_id = ?
                  AND status IN ('ACTIVE', 'UPDATE_REQUIRED')
                ORDER BY last_seen_at DESC NULLS LAST, updated_at DESC NULLS LAST, id DESC
                """, userInternalId);
        return rows.stream()
                .map(row -> DbValueMapper.string(row, "device_id"))
                .filter(deviceId -> deviceId != null && broker.isConnected(deviceId))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.CONFLICT,
                        "AGENT_DISCONNECTED",
                        "현재 연결된 PC Agent가 없습니다. PC Agent 실행 상태를 확인해 주세요."
                ));
    }

    private static List<String> normalizeChecks(List<String> requestedChecks) {
        if (requestedChecks == null || requestedChecks.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSIS_REQUEST", "진단 항목이 필요합니다.");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String check : requestedChecks) {
            String value = requiredText(check, "진단 항목이 비어 있습니다.").toLowerCase(Locale.ROOT);
            if (!ALLOWED_CHECKS.contains(value)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSIS_REQUEST", "지원하지 않는 진단 항목입니다: " + value);
            }
            normalized.add(value);
        }
        return new ArrayList<>(normalized);
    }

    private static String requiredText(String value, String message) {
        String text = value == null ? null : value.trim();
        if (text == null || text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSIS_REQUEST", message);
        }
        return text;
    }

    public record CreateRequest(String symptom, List<String> requestedChecks, String mode) {
    }
}
