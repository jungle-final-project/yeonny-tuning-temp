package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenHasher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
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
            context.registerBean(PcAgentLogSummaryService.class, PcAgentLogSummaryService::new);
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
        byte[] gzipBytes = gzip("""
                {"schemaVersion":"1","collectedAt":"2026-07-02T00:00:00Z","agentId":"fixture-agent","sequence":1,"kind":"SYSTEM_METRIC","payload":{"cpuUsage":12.5,"memoryUsedPercent":55.0,"diskUsedPercent":93.0,"gpuUsage":20.0,"vramUsage":35.0,"gpuTempCelsius":72.0,"unavailableReason":{"cpuTemp":"CPU temperature sensor unavailable"}},"privacyFlags":{"masked":true,"containsRawPath":false}}
                """);
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
                eq((long) gzipBytes.length),
                eq("agent-logs/device-public-id/upload-job-public-id-agent-log.jsonl.gz"),
                contains("Agent gzip log validated")
        )).thenReturn(MockData.map(
                "log_upload_internal_id", 200L,
                "log_upload_id", "log-upload-public-id",
                "status", "UPLOADED",
                "file_name", "agent-log.jsonl.gz",
                "file_size", (long) gzipBytes.length,
                "range_minutes", 30
        ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_log_bundles"),
                eq(100L),
                eq(200L),
                eq(1),
                eq("agent-logs/device-public-id/upload-job-public-id-agent-log.jsonl.gz"),
                any(),
                eq((long) gzipBytes.length)
        )).thenReturn(MockData.map(
                "bundle_internal_id", 300L,
                "bundle_id", "bundle-public-id"
        ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO as_tickets"),
                eq(20L),
                eq(200L),
                eq("GPU temperature spike")
        )).thenReturn(MockData.map(
                "ticket_internal_id", 400L,
                "ticket_id", "ticket-public-id",
                "status", "OPEN",
                "analysis_status", "RULE_READY",
                "review_status", "REQUIRED",
                "support_decision", "NEEDS_MORE_INFO"
        ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_log_summaries"),
                eq(200L),
                eq(400L),
                contains("\"lineCount\":1"),
                contains("\"maxDiskUsage\":93.0"),
                contains("\"SYSTEM_METRIC\":1"),
                contains("\"storagePressureRisk\":true"),
                contains("\"rawStoredForReprocessingOnly\":true")
        )).thenReturn(MockData.map("summary_id", "summary-public-id"));

        Map<String, Object> response = service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzipBytes),
                MockData.map("rangeMinutes", 30, "symptom", "GPU temperature spike"),
                "upload-key"
        );

        assertThat(response.get("uploadJobId")).isEqualTo("upload-job-public-id");
        assertThat(response.get("logUploadId")).isEqualTo("log-upload-public-id");
        assertThat(response.get("ticketId")).isEqualTo("ticket-public-id");
        assertThat(response.get("logSummaryId")).isEqualTo("summary-public-id");
        assertThat(response.get("analysisStatus")).isEqualTo("RULE_READY");

        verify(jdbcTemplate).queryForMap(
                contains("INSERT INTO as_tickets"),
                eq(20L),
                eq(200L),
                eq("GPU temperature spike")
        );
    }

    @Test
    void uploadLogsRejectsUnmaskedRawPathBeforeCreatingUploadRows() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(1);
        byte[] gzipBytes = gzip("""
                {"schemaVersion":"1","collectedAt":"2026-07-02T00:00:00Z","agentId":"fixture-agent","sequence":1,"kind":"SYSTEM_METRIC","payload":{"diskUsage":70.0},"privacyFlags":{"masked":false,"containsRawPath":true}}
                """);

        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzipBytes),
                MockData.map("rangeMinutes", 30),
                "upload-key"
        ))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("FILE_VALIDATION_ERROR");
    }

    private static byte[] gzip(String content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(bytes)) {
                gzipOutputStream.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
