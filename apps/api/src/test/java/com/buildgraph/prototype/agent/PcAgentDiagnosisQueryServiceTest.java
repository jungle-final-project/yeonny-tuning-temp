package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisEventEntity;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisEventRepository;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisResultEntity;
import com.buildgraph.prototype.agent.persistence.PcAgentDiagnosisResultRepository;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PcAgentDiagnosisQueryServiceTest {
    private static final UUID DIAGNOSIS_ID = UUID.fromString("00000000-0000-4000-8000-000000000321");
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            7L, "user-id", "user@example.com", "User", "USER", null
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PcAgentDiagnosisSocketBroker broker = mock(PcAgentDiagnosisSocketBroker.class);
    private final PcAgentDiagnosisEventRepository eventRepository = mock(PcAgentDiagnosisEventRepository.class);
    private final PcAgentDiagnosisResultRepository resultRepository = mock(PcAgentDiagnosisResultRepository.class);
    private final PcAgentDiagnosisQueryService service = new PcAgentDiagnosisQueryService(
            jdbcTemplate, broker, eventRepository, resultRepository
    );

    @Test
    void rebuildsCompletedDemoResponseFromDatabaseAfterBrokerMemoryIsEmpty() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "diagnosis_id", DIAGNOSIS_ID.toString(),
                "device_id", "00000000-0000-4000-8000-000000000111",
                "request_status", "ACCEPTED",
                "connection_status", "CONNECTED",
                "accepted_at", Instant.parse("2026-07-13T00:59:00Z"),
                "created_at", Instant.parse("2026-07-13T00:58:00Z"),
                "updated_at", Instant.parse("2026-07-13T00:59:00Z")
        )));
        PcAgentDiagnosisEventEntity event = mock(PcAgentDiagnosisEventEntity.class);
        when(event.eventId()).thenReturn("event-1");
        when(event.taskId()).thenReturn("graphics-device-state");
        when(event.eventType()).thenReturn("TASK_COMPLETED");
        when(event.status()).thenReturn("COMPLETED");
        when(event.progressPercent()).thenReturn(100);
        when(event.occurredAt()).thenReturn(Instant.parse("2026-07-13T01:00:00Z"));
        when(event.createdAt()).thenReturn(Instant.parse("2026-07-13T01:00:01Z"));
        when(event.rawPayload()).thenReturn(Map.of("dataMode", "DEMO"));
        when(eventRepository.findAllByDiagnosisIdOrderByOccurredAtAscIdAsc(DIAGNOSIS_ID))
                .thenReturn(List.of(event));
        PcAgentDiagnosisResultEntity result = result();
        when(resultRepository.findByDiagnosisId(DIAGNOSIS_ID)).thenReturn(Optional.of(result));
        when(broker.isConnected(anyString())).thenReturn(false);

        Map<String, Object> response = service.get(USER, DIAGNOSIS_ID.toString());

        assertThat(response)
                .containsEntry("status", "ACCEPTED")
                .containsEntry("accepted", true)
                .containsEntry("agentConnected", false)
                .containsEntry("currentProgress", 100)
                .containsEntry("currentTask", "graphics-device-state")
                .containsEntry("completed", true)
                .containsEntry("resolutionType", "SOFTWARE_RECOVERY")
                .containsEntry("dataMode", "DEMO")
                .containsEntry("scenarioId", "GRAPHICS_CODE43_REMOTE_SUPPORT");
        assertThat((List<?>) response.get("events")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> resultResponse = (Map<String, Object>) response.get("result");
        assertThat(resultResponse)
                .containsEntry("title", "그래픽 장치 구성 이상")
                .containsEntry("canAutoRecover", false);
    }

    @Test
    void returnsOngoingProgressAndEventsInPersistedOrder() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(requestRow()));
        PcAgentDiagnosisEventEntity first = event(
                "event-1", "hardware-overview", "COLLECTING", 20,
                "하드웨어 정보를 수집하고 있습니다.", "2026-07-13T01:00:00Z"
        );
        PcAgentDiagnosisEventEntity second = event(
                "event-2", "graphics-device-state", "DIAGNOSING", 55,
                "그래픽 장치 상태를 확인하고 있습니다.", "2026-07-13T01:00:02Z"
        );
        when(eventRepository.findAllByDiagnosisIdOrderByOccurredAtAscIdAsc(DIAGNOSIS_ID))
                .thenReturn(List.of(first, second));
        when(resultRepository.findByDiagnosisId(DIAGNOSIS_ID)).thenReturn(Optional.empty());

        Map<String, Object> response = service.get(USER, DIAGNOSIS_ID.toString());

        assertThat(response)
                .containsEntry("currentProgress", 55)
                .containsEntry("currentTask", "graphics-device-state")
                .containsEntry("completed", false)
                .containsEntry("result", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.get("events");
        assertThat(events).extracting(event -> event.get("eventId"))
                .containsExactly("event-1", "event-2");
        assertThat(events).extracting(event -> event.get("status"))
                .containsExactly("COLLECTING", "DIAGNOSING");
    }

    @Test
    void returnsTerminalFailureWithoutInventingAResult() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(requestRow()));
        PcAgentDiagnosisEventEntity failed = event(
                "event-failed", "graphics-device-state", "FAILED", 42,
                "그래픽 장치 상태 확인에 실패했습니다.", "2026-07-13T01:00:02Z"
        );
        when(eventRepository.findAllByDiagnosisIdOrderByOccurredAtAscIdAsc(DIAGNOSIS_ID))
                .thenReturn(List.of(failed));
        when(resultRepository.findByDiagnosisId(DIAGNOSIS_ID)).thenReturn(Optional.empty());

        Map<String, Object> response = service.get(USER, DIAGNOSIS_ID.toString());

        assertThat(response)
                .containsEntry("completed", true)
                .containsEntry("result", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) response.get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("status", "FAILED");
    }

    @Test
    void anotherUsersDiagnosisIsNotExposed() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.get(USER, DIAGNOSIS_ID.toString()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiError = (ApiException) error;
                    assertThat(apiError.status().value()).isEqualTo(404);
                    assertThat(apiError.code()).isEqualTo("DIAGNOSIS_NOT_FOUND");
                });
    }

    @Test
    void invalidDiagnosisIdIsHandledAsNotFoundBeforeDatabaseLookup() {
        assertThatThrownBy(() -> service.get(USER, "not-a-diagnosis-id"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiError = (ApiException) error;
                    assertThat(apiError.status().value()).isEqualTo(404);
                    assertThat(apiError.code()).isEqualTo("DIAGNOSIS_NOT_FOUND");
                });
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void latestReturnsAnOwnedDiagnosisWithItsLinkedTicket() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("ORDER BY requested_at DESC")) {
                return List.of(Map.of("diagnosis_id", DIAGNOSIS_ID.toString()));
            }
            if (sql.contains("FROM as_tickets")) {
                return List.of(Map.of(
                        "id", "00000000-0000-4000-8000-000000000777",
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "created_at", Instant.parse("2026-07-13T01:01:00Z")
                ));
            }
            return List.of(requestRow());
        });
        PcAgentDiagnosisEventEntity completedEvent = event(
                "event-completed", "evidence-finalize", "COMPLETED", 100,
                "진단을 완료했습니다.", "2026-07-13T01:00:02Z"
        );
        when(eventRepository.findAllByDiagnosisIdOrderByOccurredAtAscIdAsc(DIAGNOSIS_ID))
                .thenReturn(List.of(completedEvent));
        PcAgentDiagnosisResultEntity completedResult = result();
        when(resultRepository.findByDiagnosisId(DIAGNOSIS_ID)).thenReturn(Optional.of(completedResult));

        Map<String, Object> response = service.latest(USER);

        @SuppressWarnings("unchecked")
        Map<String, Object> diagnosis = (Map<String, Object>) response.get("diagnosis");
        assertThat(diagnosis).containsEntry("diagnosisId", DIAGNOSIS_ID.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> ticket = (Map<String, Object>) diagnosis.get("asTicket");
        assertThat(ticket)
                .containsEntry("id", "00000000-0000-4000-8000-000000000777")
                .containsEntry("status", "OPEN")
                .containsEntry("reviewStatus", "REQUIRED")
                .containsEntry("supportDecision", null);
        verify(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("FROM pc_agent_diagnosis_requests")
                        && sql.contains("WHERE user_id = ?")
                        && sql.contains("ORDER BY requested_at DESC")),
                eq(USER.internalId())
        );
        verify(jdbcTemplate).queryForList(
                argThat(sql -> sql.contains("FROM as_tickets") && sql.contains("AND user_id = ?")),
                eq(DIAGNOSIS_ID),
                eq(USER.internalId())
        );
    }

    @Test
    void latestReturnsANormalEmptyResponseWhenTheUserHasNoDiagnosis() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> response = service.latest(USER);

        assertThat(response).containsEntry("diagnosis", null);
        verify(eventRepository, never()).findAllByDiagnosisIdOrderByOccurredAtAscIdAsc(any());
        verify(resultRepository, never()).findByDiagnosisId(any());
    }

    private static Map<String, Object> requestRow() {
        return Map.of(
                "diagnosis_id", DIAGNOSIS_ID.toString(),
                "device_id", "00000000-0000-4000-8000-000000000111",
                "request_status", "ACCEPTED",
                "connection_status", "CONNECTED",
                "accepted_at", Instant.parse("2026-07-13T00:59:00Z"),
                "requested_at", Instant.parse("2026-07-13T00:58:00Z"),
                "created_at", Instant.parse("2026-07-13T00:58:00Z"),
                "updated_at", Instant.parse("2026-07-13T00:59:00Z")
        );
    }

    private static PcAgentDiagnosisEventEntity event(
            String eventId,
            String taskId,
            String status,
            int progress,
            String message,
            String occurredAt
    ) {
        PcAgentDiagnosisEventEntity event = mock(PcAgentDiagnosisEventEntity.class);
        when(event.eventId()).thenReturn(eventId);
        when(event.taskId()).thenReturn(taskId);
        when(event.eventType()).thenReturn("PROGRESS_UPDATED");
        when(event.status()).thenReturn(status);
        when(event.progressPercent()).thenReturn(progress);
        when(event.message()).thenReturn(message);
        when(event.occurredAt()).thenReturn(Instant.parse(occurredAt));
        when(event.createdAt()).thenReturn(Instant.parse(occurredAt).plusSeconds(1));
        when(event.rawPayload()).thenReturn(Map.of());
        return event;
    }

    private static PcAgentDiagnosisResultEntity result() {
        PcAgentDiagnosisResultEntity result = mock(PcAgentDiagnosisResultEntity.class);
        when(result.resultId()).thenReturn("result-1");
        when(result.diagnosisType()).thenReturn("DEVICE_DRIVER_CONFIGURATION_ISSUE");
        when(result.severity()).thenReturn("WARNING");
        when(result.title()).thenReturn("그래픽 장치 구성 이상");
        when(result.summary()).thenReturn("원격 점검을 권장합니다.");
        when(result.resolutionType()).thenReturn("SOFTWARE_RECOVERY");
        when(result.canAutoRecover()).thenReturn(false);
        when(result.evidence()).thenReturn(List.of(Map.of("metricType", "PNP_PROBLEM_CODE", "value", 43)));
        when(result.findings()).thenReturn(List.of(Map.of("code", "GRAPHICS_DEVICE_CODE_43")));
        when(result.actions()).thenReturn(List.of("드라이버 재설치"));
        when(result.dataMode()).thenReturn("DEMO");
        when(result.scenarioId()).thenReturn("GRAPHICS_CODE43_REMOTE_SUPPORT");
        when(result.rawPayload()).thenReturn(Map.of("dataMode", "DEMO"));
        when(result.createdAt()).thenReturn(Instant.parse("2026-07-13T01:00:02Z"));
        when(result.updatedAt()).thenReturn(Instant.parse("2026-07-13T01:00:02Z"));
        return result;
    }
}
