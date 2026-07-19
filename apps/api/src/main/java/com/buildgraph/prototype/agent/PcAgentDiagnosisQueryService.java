package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisEventEntity;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisEventRepository;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisResultEntity;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisResultRepository;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PcAgentDiagnosisQueryService {
    private static final Set<String> TERMINAL_STATES = Set.of(
            "COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"
    );

    private final JdbcTemplate jdbcTemplate;
    private final PcAgentDiagnosisSocketBroker broker;
    private final PcAgentDiagnosisEventRepository eventRepository;
    private final PcAgentDiagnosisResultRepository resultRepository;

    public PcAgentDiagnosisQueryService(
            JdbcTemplate jdbcTemplate,
            PcAgentDiagnosisSocketBroker broker,
            PcAgentDiagnosisEventRepository eventRepository,
            PcAgentDiagnosisResultRepository resultRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.broker = broker;
        this.eventRepository = eventRepository;
        this.resultRepository = resultRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(CurrentUserService.CurrentUser user, String diagnosisIdText) {
        UUID diagnosisId = parseUuid(diagnosisIdText);
        Map<String, Object> request = jdbcTemplate.queryForList("""
                        SELECT r.diagnosis_id::text AS diagnosis_id,
                               d.public_id::text AS device_id,
                               r.request_status,
                               r.connection_status,
                               r.accepted_at,
                               r.requested_at,
                               r.created_at,
                               r.updated_at
                        FROM pc_agent_diagnosis_requests r
                        JOIN agent_devices d ON d.id = r.agent_device_id
                        WHERE r.diagnosis_id = ?
                          AND r.user_id = ?
                        """, diagnosisId, user.internalId())
                .stream()
                .findFirst()
                .orElseThrow(PcAgentDiagnosisQueryService::notFound);

        List<PcAgentDiagnosisEventEntity> events =
                eventRepository.findAllByDiagnosisIdOrderByOccurredAtAscIdAsc(diagnosisId);
        PcAgentDiagnosisEventEntity latestEvent = events.isEmpty() ? null : events.get(events.size() - 1);
        PcAgentDiagnosisResultEntity result = resultRepository.findByDiagnosisId(diagnosisId).orElse(null);
        String requestStatus = DbValueMapper.string(request, "request_status");
        String deviceId = DbValueMapper.string(request, "device_id");
        boolean accepted = request.get("accepted_at") != null
                || "ACCEPTED".equals(requestStatus)
                || "DUPLICATE".equals(requestStatus);
        boolean completed = result != null
                || (latestEvent != null && TERMINAL_STATES.contains(latestEvent.status()));
        Object completedAt = result != null
                ? result.updatedAt()
                : latestEvent != null && TERMINAL_STATES.contains(latestEvent.status())
                        ? latestEvent.occurredAt()
                        : null;
        Object updatedAt = latestTimestamp(
                DbValueMapper.timestamp(request, "updated_at"),
                latestEvent == null ? null : latestEvent.createdAt(),
                result == null ? null : result.updatedAt()
        );

        return MockData.map(
                "diagnosisId", diagnosisId.toString(),
                "status", requestStatus,
                "connectionStatus", DbValueMapper.string(request, "connection_status"),
                "agentConnected", broker.isConnected(deviceId),
                "accepted", accepted,
                "currentProgress", latestEvent == null ? 0 : latestEvent.progressPercent(),
                "currentTask", latestEvent == null ? null : latestEvent.taskId(),
                "events", events.stream().map(PcAgentDiagnosisQueryService::eventResponse).toList(),
                "completed", completed,
                "result", result == null ? null : resultResponse(result),
                "resolutionType", result == null ? null : result.resolutionType(),
                "dataMode", result == null ? null : result.dataMode(),
                "scenarioId", result == null ? null : result.scenarioId(),
                "requestedAt", DbValueMapper.timestamp(request, "requested_at"),
                "completedAt", completedAt,
                "createdAt", DbValueMapper.timestamp(request, "created_at"),
                "updatedAt", updatedAt
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latest(CurrentUserService.CurrentUser user) {
        Map<String, Object> request = jdbcTemplate.queryForList("""
                        SELECT r.diagnosis_id::text AS diagnosis_id,
                               d.public_id::text AS device_id,
                               r.request_status,
                               r.connection_status,
                               r.accepted_at,
                               r.requested_at,
                               r.created_at,
                               r.updated_at
                        FROM pc_agent_diagnosis_requests r
                        JOIN agent_devices d ON d.id = r.agent_device_id
                        WHERE r.user_id = ?
                        ORDER BY r.requested_at DESC, r.diagnosis_id DESC
                        LIMIT 1
                        """, user.internalId())
                .stream()
                .findFirst()
                .orElse(null);
        if (request == null) {
            return MockData.map("diagnosis", null);
        }

        UUID diagnosisId = UUID.fromString(DbValueMapper.string(request, "diagnosis_id"));
        Map<String, Object> latestEvent = jdbcTemplate.queryForList("""
                        SELECT task_id,
                               status,
                               progress_percent,
                               occurred_at,
                               created_at
                        FROM pc_agent_diagnosis_events
                        WHERE diagnosis_id = ?
                        ORDER BY occurred_at DESC, id DESC
                        LIMIT 1
                        """, diagnosisId)
                .stream()
                .findFirst()
                .orElse(null);
        List<Map<String, Object>> recentMessages = jdbcTemplate.queryForList("""
                        SELECT event_id,
                               status,
                               progress_percent,
                               message,
                               occurred_at
                        FROM (
                          SELECT id,
                                 event_id,
                                 status,
                                 progress_percent,
                                 message,
                                 occurred_at
                          FROM pc_agent_diagnosis_events
                          WHERE diagnosis_id = ?
                            AND message IS NOT NULL
                            AND BTRIM(message) <> ''
                          ORDER BY occurred_at DESC, id DESC
                          LIMIT 3
                        ) recent
                        ORDER BY occurred_at ASC, id ASC
                        """, diagnosisId)
                .stream()
                .map(PcAgentDiagnosisQueryService::recentMessageResponse)
                .toList();
        Map<String, Object> result = jdbcTemplate.queryForList("""
                        SELECT severity,
                               resolution_type,
                               data_mode,
                               scenario_id,
                               updated_at
                        FROM pc_agent_diagnosis_results
                        WHERE diagnosis_id = ?
                        """, diagnosisId)
                .stream()
                .findFirst()
                .orElse(null);

        String requestStatus = DbValueMapper.string(request, "request_status");
        String currentStatus = latestEvent == null ? null : DbValueMapper.string(latestEvent, "status");
        boolean accepted = request.get("accepted_at") != null
                || "ACCEPTED".equals(requestStatus)
                || "DUPLICATE".equals(requestStatus);
        boolean completed = result != null
                || (currentStatus != null && TERMINAL_STATES.contains(currentStatus));
        Object completedAt = result != null
                ? DbValueMapper.timestamp(result, "updated_at")
                : completed && latestEvent != null
                        ? DbValueMapper.timestamp(latestEvent, "occurred_at")
                        : null;
        Object updatedAt = latestTimestamp(
                DbValueMapper.timestamp(request, "updated_at"),
                latestEvent == null ? null : DbValueMapper.timestamp(latestEvent, "created_at"),
                result == null ? null : DbValueMapper.timestamp(result, "updated_at")
        );

        return MockData.map("diagnosis", MockData.map(
                "diagnosisId", diagnosisId.toString(),
                "status", requestStatus,
                "connectionStatus", DbValueMapper.string(request, "connection_status"),
                "agentConnected", broker.isConnected(DbValueMapper.string(request, "device_id")),
                "accepted", accepted,
                "currentStatus", currentStatus,
                "currentProgress", latestEvent == null ? 0 : DbValueMapper.integer(latestEvent, "progress_percent"),
                "currentTask", latestEvent == null ? null : DbValueMapper.string(latestEvent, "task_id"),
                "recentMessages", recentMessages,
                "completed", completed,
                "resultAvailable", result != null,
                "resultSeverity", result == null ? null : DbValueMapper.string(result, "severity"),
                "resolutionType", result == null ? null : DbValueMapper.string(result, "resolution_type"),
                "dataMode", result == null ? null : DbValueMapper.string(result, "data_mode"),
                "scenarioId", result == null ? null : DbValueMapper.string(result, "scenario_id"),
                "requestedAt", DbValueMapper.timestamp(request, "requested_at"),
                "completedAt", completedAt,
                "createdAt", DbValueMapper.timestamp(request, "created_at"),
                "updatedAt", updatedAt,
                "asTicket", linkedTicket(user, diagnosisId)
        ));
    }

    private static Map<String, Object> recentMessageResponse(Map<String, Object> event) {
        return MockData.map(
                "eventId", DbValueMapper.string(event, "event_id"),
                "status", DbValueMapper.string(event, "status"),
                "progressPercent", DbValueMapper.integer(event, "progress_percent"),
                "message", DbValueMapper.string(event, "message"),
                "occurredAt", DbValueMapper.timestamp(event, "occurred_at")
        );
    }

    private Map<String, Object> linkedTicket(CurrentUserService.CurrentUser user, UUID diagnosisId) {
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               status,
                               review_status,
                               support_decision,
                               created_at
                        FROM as_tickets
                        WHERE diagnosis_id = ?
                          AND user_id = ?
                          AND deleted_at IS NULL
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """, diagnosisId, user.internalId())
                .stream()
                .findFirst()
                .map(row -> MockData.map(
                        "id", DbValueMapper.string(row, "id"),
                        "status", DbValueMapper.string(row, "status"),
                        "reviewStatus", DbValueMapper.string(row, "review_status"),
                        "supportDecision", DbValueMapper.string(row, "support_decision"),
                        "createdAt", DbValueMapper.timestamp(row, "created_at")
                ))
                .orElse(null);
    }

    private static Map<String, Object> eventResponse(PcAgentDiagnosisEventEntity event) {
        return MockData.map(
                "eventId", event.eventId(),
                "taskId", event.taskId(),
                "eventType", event.eventType(),
                "status", event.status(),
                "progressPercent", event.progressPercent(),
                "message", event.message(),
                "occurredAt", event.occurredAt(),
                "rawPayload", event.rawPayload(),
                "createdAt", event.createdAt()
        );
    }

    private static Map<String, Object> resultResponse(PcAgentDiagnosisResultEntity result) {
        return MockData.map(
                "resultId", result.resultId(),
                "diagnosisType", result.diagnosisType(),
                "severity", result.severity(),
                "title", result.title(),
                "summary", result.summary(),
                "resolutionType", result.resolutionType(),
                "canAutoRecover", result.canAutoRecover(),
                "evidence", result.evidence(),
                "findings", result.findings(),
                "actions", result.actions(),
                "dataMode", result.dataMode(),
                "scenarioId", result.scenarioId(),
                "rawPayload", result.rawPayload(),
                "createdAt", result.createdAt(),
                "updatedAt", result.updatedAt()
        );
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw notFound();
        }
    }

    private static Object latestTimestamp(Object... values) {
        Instant latest = null;
        for (Object value : values) {
            Instant candidate = value instanceof Instant instant ? instant : parseInstant(value);
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest == null ? null : latest.toString();
    }

    private static Instant parseInstant(Object value) {
        try {
            return value == null ? null : Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "DIAGNOSIS_NOT_FOUND", "진단 요청을 찾을 수 없습니다.");
    }
}
