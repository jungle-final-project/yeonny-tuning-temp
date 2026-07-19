package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class PcAgentDiagnosisRequestServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-13T01:00:00Z"), ZoneOffset.UTC);
    private static final UUID DIAGNOSIS_ID = UUID.fromString("00000000-0000-4000-8000-000000000321");
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            7L, "user-id", "user@example.com", "User", "USER", null
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PcAgentDiagnosisSocketBroker broker = mock(PcAgentDiagnosisSocketBroker.class);
    private final PcAgentDiagnosisRequestService service = new PcAgentDiagnosisRequestService(
            jdbcTemplate, broker, CLOCK, () -> DIAGNOSIS_ID
    );

    @Test
    void createsServerOwnedRequestForConnectedUserDeviceAndReturnsAgentResponse() {
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq(7L)))
                .thenReturn(List.of(MockData.map("device_id", "device-1")));
        when(broker.isConnected("device-1")).thenReturn(true);
        when(jdbcTemplate.update(contains("INSERT INTO pc_agent_diagnosis_requests"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE pc_agent_diagnosis_requests"), any(Object[].class)))
                .thenReturn(1);
        when(broker.dispatchAndAwait(any())).thenReturn(
                new PcAgentDiagnosisSocketBroker.AgentResponse("ACCEPTED", "수신 완료")
        );

        Map<String, Object> result = service.create(USER, new PcAgentDiagnosisRequestService.CreateRequest(
                "게임 실행 후 프레임 저하",
                List.of("cpu", "gpu", "memory", "disk", "cooling"),
                "LIVE"
        ));

        ArgumentCaptor<PcAgentDiagnosisRequest> captor = ArgumentCaptor.forClass(PcAgentDiagnosisRequest.class);
        verify(broker).dispatchAndAwait(captor.capture());
        assertThat(captor.getValue().diagnosisId()).isEqualTo(DIAGNOSIS_ID.toString());
        assertThat(captor.getValue().deviceId()).isEqualTo("device-1");
        assertThat(captor.getValue().expiresAt()).isEqualTo(Instant.parse("2026-07-13T01:02:00Z"));
        assertThat(result).containsEntry("status", "ACCEPTED");
        verify(jdbcTemplate).update(contains("INSERT INTO pc_agent_diagnosis_requests"), any(Object[].class));
        verify(jdbcTemplate).update(contains("SET request_status = ?"), any(Object[].class));
    }

    @Test
    void disconnectedDeviceIsNotReportedAsSuccessfulRequest() {
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq(7L)))
                .thenReturn(List.of(MockData.map("device_id", "device-1")));
        when(broker.isConnected("device-1")).thenReturn(false);

        assertThatThrownBy(() -> service.create(USER, new PcAgentDiagnosisRequestService.CreateRequest(
                "게임 실행 후 프레임 저하", List.of("gpu"), "LIVE"
        )))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("AGENT_DISCONNECTED"));
    }
}
