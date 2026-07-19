package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisEventEntity;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisEventRepository;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisResultEntity;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisResultRepository;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PcAgentDiagnosisPersistenceService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> DIAGNOSIS_STATES = Set.of(
            "RECEIVED", "COLLECTING", "DIAGNOSING", "EVALUATING", "COMPLETED",
            "PARTIALLY_COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT"
    );
    private static final Set<String> RESULT_SEVERITIES = Set.of(
            "NORMAL", "INFO", "WARNING", "CRITICAL", "INDETERMINATE"
    );
    private static final Set<String> RESOLUTION_TYPES = Set.of(
            "NONE", "SOFTWARE_RECOVERY", "USER_ACTION", "PHYSICAL_INSPECTION", "UNKNOWN"
    );
    private static final Set<String> DATA_MODES = Set.of("LIVE", "DEMO");

    private final JdbcTemplate jdbcTemplate;
    private final PcAgentDiagnosisEventRepository eventRepository;
    private final PcAgentDiagnosisResultRepository resultRepository;

    public PcAgentDiagnosisPersistenceService(
            JdbcTemplate jdbcTemplate,
            PcAgentDiagnosisEventRepository eventRepository,
            PcAgentDiagnosisResultRepository resultRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventRepository = eventRepository;
        this.resultRepository = resultRepository;
    }

    @Transactional
    public boolean storeStatus(AgentPrincipal principal, Map<String, Object> detail) {
        UUID diagnosisId = uuid(detail.get("diagnosisId"));
        String eventId = text(detail.get("eventId"));
        String eventType = text(detail.get("eventType"));
        String status = firstText(detail.get("status"), detail.get("sessionState"));
        String taskId = text(detail.get("taskId"));
        String message = text(detail.get("message"));
        Integer progress = integer(detail.get("progressPercent"), detail.get("progress"));
        Instant occurredAt = timestamp(detail.get("occurredAt"), detail.get("timestamp"));
        if (principal == null || diagnosisId == null || eventId == null || eventId.length() > 128
                || eventType == null || !DIAGNOSIS_STATES.contains(status)
                || progress == null || progress < 0 || progress > 100 || occurredAt == null
                || !lockOwnedRequest(principal, diagnosisId)) {
            return false;
        }

        Optional<PcAgentDiagnosisEventEntity> existing = eventRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            return diagnosisId.equals(existing.get().diagnosisId());
        }
        int inserted = eventRepository.insertIfAbsent(
                diagnosisId,
                eventId,
                taskId,
                eventType,
                status,
                progress,
                message,
                occurredAt,
                toJson(detail)
        );
        if (inserted == 1) {
            return true;
        }
        return eventRepository.findByEventId(eventId)
                .map(item -> diagnosisId.equals(item.diagnosisId()))
                .orElse(false);
    }

    @Transactional
    public boolean storeResult(AgentPrincipal principal, Map<String, Object> detail) {
        UUID diagnosisId = uuid(detail.get("diagnosisId"));
        String resultId = text(detail.get("resultId"));
        String diagnosisType = text(detail.get("diagnosisType"));
        String severity = text(detail.get("severity"));
        String title = text(detail.get("title"));
        String summary = text(detail.get("summary"));
        String resolutionType = text(detail.get("resolutionType"));
        Boolean canAutoRecover = detail.get("canAutoRecover") instanceof Boolean value ? value : null;
        List<?> evidence = list(detail.get("evidence"));
        List<?> findings = list(detail.get("findings"));
        List<?> actions = list(detail.containsKey("actions")
                ? detail.get("actions")
                : detail.get("recommendedActions"));
        String dataMode = Optional.ofNullable(text(detail.get("dataMode"))).orElse("LIVE");
        String scenarioId = text(detail.get("scenarioId"));
        Instant evaluatedAt = timestamp(detail.get("evaluatedAt"));
        if (principal == null || diagnosisId == null || resultId == null || resultId.length() > 128
                || !RESULT_SEVERITIES.contains(severity) || title == null || summary == null
                || !RESOLUTION_TYPES.contains(resolutionType) || canAutoRecover == null
                || evidence == null || findings == null || actions == null || evaluatedAt == null
                || !DATA_MODES.contains(dataMode) || ("DEMO".equals(dataMode) && scenarioId == null)
                || !lockOwnedRequest(principal, diagnosisId)) {
            return false;
        }

        Optional<PcAgentDiagnosisResultEntity> existingResult = resultRepository.findByResultId(resultId);
        if (existingResult.isPresent() && !diagnosisId.equals(existingResult.get().diagnosisId())) {
            return false;
        }
        return resultRepository.upsert(
                diagnosisId,
                resultId,
                diagnosisType,
                severity,
                title,
                summary,
                resolutionType,
                canAutoRecover,
                toJson(evidence),
                toJson(findings),
                toJson(actions),
                dataMode,
                scenarioId,
                toJson(detail)
        ) == 1;
    }

    private boolean lockOwnedRequest(AgentPrincipal principal, UUID diagnosisId) {
        return !jdbcTemplate.queryForList("""
                SELECT r.diagnosis_id
                FROM pc_agent_diagnosis_requests r
                JOIN agent_devices d ON d.id = r.agent_device_id
                WHERE r.diagnosis_id = ?
                  AND r.agent_device_id = ?
                  AND r.user_id = ?
                  AND d.public_id = ?::uuid
                FOR UPDATE
                """,
                diagnosisId,
                principal.deviceInternalId(),
                principal.userInternalId(),
                principal.deviceId()
        ).isEmpty();
    }

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("PC Agent diagnosis JSON serialization failed.", error);
        }
    }

    private static UUID uuid(Object value) {
        try {
            String text = text(value);
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static Integer integer(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : null;
    }

    private static Instant timestamp(Object... values) {
        for (Object value : values) {
            String text = text(value);
            if (text == null) {
                continue;
            }
            try {
                return Instant.parse(text);
            } catch (DateTimeException ignored) {
                try {
                    return OffsetDateTime.parse(text).toInstant();
                } catch (DateTimeException ignoredAgain) {
                    // 다음 후보 필드를 확인한다.
                }
            }
        }
        return null;
    }
}
