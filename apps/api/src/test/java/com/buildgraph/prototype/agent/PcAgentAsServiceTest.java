package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenHasher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class PcAgentAsServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentPrincipal AGENT = new AgentPrincipal(10L, "device-public-id", 20L, "ACTIVE");

    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final AgentTokenHasher tokenHasher = new AgentTokenHasher();
    private final PcAgentAsService service = new PcAgentAsService(
            jdbcTemplate,
            tokenHasher,
            CLOCK,
            () -> "raw-agent-token"
    );

    @Test
    void springCanInstantiateServiceWithProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbcTemplate);
            context.registerBean(AgentTokenHasher.class, () -> tokenHasher);
            context.register(PcAgentAsService.class);
            context.refresh();

            assertThat(context.getBean(PcAgentAsService.class)).isNotNull();
        }
    }

    @Test
    void registerStoresHashedAgentTokenAndReturnsRawTokenOnce() {
        String tokenHash = tokenHasher.sha256Hex("raw-agent-token");
        when(jdbcTemplate.queryForList(contains("FROM users"), eq("user@example.com")))
                .thenReturn(List.of(Map.of("id", 20L)));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_devices"),
                eq(20L),
                eq("fingerprint-hash"),
                eq("host-hash"),
                eq(tokenHash),
                eq("register-1"),
                eq("Windows 11"),
                eq("0.1.0"),
                eq("policy-v1")
        )).thenReturn(MockData.map(
                "device_internal_id", 10L,
                "device_id", "device-public-id",
                "status", "ACTIVE"
        ));

        Map<String, Object> response = service.register(MockData.map(
                "activationToken", "demo-agent-activation-token",
                "deviceFingerprintHash", "fingerprint-hash",
                "hostnameHash", "host-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1",
                "userEmail", "user@example.com"
        ));

        assertThat(response.get("agentToken")).isEqualTo("raw-agent-token");
        assertThat(response.get("deviceId")).isEqualTo("device-public-id");
        assertThat(tokenHash).isNotEqualTo("raw-agent-token");
    }

    @Test
    void uploadLogsCreatesUploadJobLogUploadAndTicketWithDiagnosisStatus() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(1);
        when(jdbcTemplate.queryForMap(contains("INSERT INTO agent_upload_jobs"), eq(10L), eq("upload-key"), any(), any()))
                .thenReturn(MockData.map(
                        "upload_job_internal_id", 100L,
                        "upload_job_id", "upload-job-public-id",
                        "status", "UPLOADED"
                ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_log_uploads"),
                eq(20L),
                eq(10L),
                eq(100L),
                eq(30),
                eq("agent-log.jsonl.gz"),
                eq(4L),
                eq("agent-logs/device-public-id/agent-log.jsonl.gz")
        )).thenReturn(MockData.map(
                "log_upload_internal_id", 200L,
                "log_upload_id", "log-upload-public-id",
                "status", "UPLOADED",
                "file_name", "agent-log.jsonl.gz",
                "file_size", 4L,
                "range_minutes", 30
        ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO as_tickets"),
                eq(20L),
                eq(200L),
                eq("GPU temperature spike")
        )).thenReturn(MockData.map(
                "ticket_id", "ticket-public-id",
                "status", "OPEN",
                "analysis_status", "RULE_READY",
                "review_status", "REQUIRED",
                "support_decision", "NEEDS_MORE_INFO"
        ));

        Map<String, Object> response = service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", "demo".getBytes()),
                MockData.map("rangeMinutes", 30, "symptom", "GPU temperature spike"),
                "upload-key"
        );

        assertThat(response.get("uploadJobId")).isEqualTo("upload-job-public-id");
        assertThat(response.get("logUploadId")).isEqualTo("log-upload-public-id");
        assertThat(response.get("ticketId")).isEqualTo("ticket-public-id");
        assertThat(response.get("analysisStatus")).isEqualTo("RULE_READY");

        verify(jdbcTemplate).queryForMap(
                contains("INSERT INTO as_tickets"),
                eq(20L),
                eq(200L),
                eq("GPU temperature spike")
        );
    }
}
