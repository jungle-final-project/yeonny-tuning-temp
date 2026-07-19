package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisEventEntity;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisEventRepository;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisResultRepository;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PcAgentDiagnosisPersistenceServiceTest {
    private static final UUID DIAGNOSIS_ID = UUID.fromString("00000000-0000-4000-8000-000000000321");
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal(
            11L, "00000000-0000-4000-8000-000000000111", 7L, "ACTIVE"
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PcAgentDiagnosisEventRepository eventRepository = mock(PcAgentDiagnosisEventRepository.class);
    private final PcAgentDiagnosisResultRepository resultRepository = mock(PcAgentDiagnosisResultRepository.class);
    private final PcAgentDiagnosisPersistenceService service = new PcAgentDiagnosisPersistenceService(
            jdbcTemplate, eventRepository, resultRepository
    );

    @BeforeEach
    void ownsRequest() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("diagnosis_id", DIAGNOSIS_ID)));
    }

    @Test
    void storesEventAndUsesEventIdForIdempotency() {
        when(eventRepository.findByEventId("event-1")).thenReturn(Optional.empty());
        when(eventRepository.insertIfAbsent(
                any(), anyString(), any(), anyString(), anyString(), anyInt(), any(), any(), anyString()
        )).thenReturn(1);

        assertThat(service.storeStatus(PRINCIPAL, statusDetail())).isTrue();
        verify(eventRepository).insertIfAbsent(
                eq(DIAGNOSIS_ID),
                eq("event-1"),
                eq("graphics-device-state"),
                eq("TASK_COMPLETED"),
                eq("DIAGNOSING"),
                eq(50),
                eq("그래픽 장치를 확인했습니다."),
                eq(Instant.parse("2026-07-13T01:00:00Z")),
                org.mockito.ArgumentMatchers.contains("\"eventId\":\"event-1\"")
        );
    }

    @Test
    void duplicateEventForSameDiagnosisDoesNotInsertAgain() {
        PcAgentDiagnosisEventEntity existing = mock(PcAgentDiagnosisEventEntity.class);
        when(existing.diagnosisId()).thenReturn(DIAGNOSIS_ID);
        when(eventRepository.findByEventId("event-1")).thenReturn(Optional.of(existing));

        assertThat(service.storeStatus(PRINCIPAL, statusDetail())).isTrue();
        verify(eventRepository, org.mockito.Mockito.never()).insertIfAbsent(
                any(), anyString(), any(), anyString(), anyString(), anyInt(), any(), any(), anyString()
        );
    }

    @Test
    void unknownOrUnownedDiagnosisIsRejected() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertThat(service.storeStatus(PRINCIPAL, statusDetail())).isFalse();
    }

    @Test
    void upsertsDemoResultWithScenarioMetadata() {
        when(resultRepository.findByResultId("result-1")).thenReturn(Optional.empty());
        when(resultRepository.upsert(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyBoolean(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(1);

        assertThat(service.storeResult(PRINCIPAL, resultDetail("DEMO", "GRAPHICS_CODE43_REMOTE_SUPPORT")))
                .isTrue();
        verify(resultRepository).upsert(
                eq(DIAGNOSIS_ID),
                eq("result-1"),
                eq("DEVICE_DRIVER_CONFIGURATION_ISSUE"),
                eq("WARNING"),
                eq("그래픽 장치 구성 이상"),
                eq("원격 점검을 권장합니다."),
                eq("SOFTWARE_RECOVERY"),
                eq(false),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.contains("드라이버 재설치"),
                eq("DEMO"),
                eq("GRAPHICS_CODE43_REMOTE_SUPPORT"),
                org.mockito.ArgumentMatchers.contains("\"dataMode\":\"DEMO\"")
        );
    }

    @Test
    void storesLiveResultWithoutFixtureMetadata() {
        when(resultRepository.findByResultId("result-1")).thenReturn(Optional.empty());
        when(resultRepository.upsert(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyBoolean(), anyString(), anyString(), anyString(), anyString(), any(), anyString()
        )).thenReturn(1);

        assertThat(service.storeResult(PRINCIPAL, resultDetail("LIVE", null))).isTrue();
        verify(resultRepository).upsert(
                eq(DIAGNOSIS_ID), eq("result-1"), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyBoolean(), anyString(), anyString(), anyString(), eq("LIVE"), eq(null), anyString()
        );
    }

    private static Map<String, Object> statusDetail() {
        return Map.of(
                "diagnosisId", DIAGNOSIS_ID.toString(),
                "eventId", "event-1",
                "eventType", "TASK_COMPLETED",
                "taskId", "graphics-device-state",
                "sessionState", "DIAGNOSING",
                "progress", 50,
                "message", "그래픽 장치를 확인했습니다.",
                "timestamp", "2026-07-13T01:00:00Z"
        );
    }

    private static Map<String, Object> resultDetail(String dataMode, String scenarioId) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("diagnosisId", DIAGNOSIS_ID.toString());
        result.put("resultId", "result-1");
        result.put("diagnosisType", "DEVICE_DRIVER_CONFIGURATION_ISSUE");
        result.put("severity", "WARNING");
        result.put("title", "그래픽 장치 구성 이상");
        result.put("summary", "원격 점검을 권장합니다.");
        result.put("resolutionType", "SOFTWARE_RECOVERY");
        result.put("canAutoRecover", false);
        result.put("evidence", List.of(Map.of("metricType", "PNP_PROBLEM_CODE", "value", 43)));
        result.put("findings", List.of(Map.of("code", "GRAPHICS_DEVICE_CODE_43")));
        result.put("recommendedActions", List.of("드라이버 재설치"));
        result.put("evaluatedAt", "2026-07-13T01:00:00Z");
        result.put("dataMode", dataMode);
        if (scenarioId != null) {
            result.put("scenarioId", scenarioId);
        }
        return result;
    }
}
