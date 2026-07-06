package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenHasher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class PcAgentAsServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-02T00:00:00Z"), ZoneOffset.UTC);
    private static final AgentPrincipal AGENT = new AgentPrincipal(10L, "device-public-id", 20L, "ACTIVE");
    private static final String VALID_ACTIVATION_TOKEN = "valid-agent-activation-token";

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
    void issueActivationTokenStoresHashAndReturnsRawTokenOnce() {
        Instant expiresAt = Instant.parse("2026-07-09T00:00:00Z");
        when(jdbcTemplate.queryForList(contains("FROM users"), eq("user@example.com")))
                .thenReturn(List.of(MockData.map("id", 20L)));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_activation_tokens"),
                eq(20L),
                any(String.class),
                eq(Timestamp.from(expiresAt))
        )).thenReturn(MockData.map(
                "id", "activation-public-id",
                "expires_at", expiresAt
        ));

        Map<String, Object> response = service.issueActivationToken(MockData.map(
                "userEmail", "user@example.com",
                "ttlDays", 7
        ));

        String rawActivationToken = String.valueOf(response.get("activationToken"));
        assertThat(rawActivationToken).isNotBlank();
        assertThat(response.get("id")).isEqualTo("activation-public-id");
        assertThat(response.get("tokenType")).isEqualTo("Activation");
        assertThat(response).containsOnlyKeys("id", "activationToken", "tokenType", "expiresAt");

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForMap(
                contains("INSERT INTO agent_activation_tokens"),
                eq(20L),
                hashCaptor.capture(),
                eq(Timestamp.from(expiresAt))
        );
        assertThat(hashCaptor.getValue()).isEqualTo(tokenHasher.sha256Hex(rawActivationToken));
        assertThat(hashCaptor.getValue()).isNotEqualTo(rawActivationToken);
    }

    @Test
    void issueActivationTokenRejectsMissingUserSelectorBeforeGeneratingToken() {
        assertThatThrownBy(() -> service.issueActivationToken(MockData.map(
                "ttlDays", 7
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void issueActivationTokenRejectsInvalidUserIdBeforeQuery() {
        assertThatThrownBy(() -> service.issueActivationToken(MockData.map(
                "userId", "not-a-uuid",
                "ttlDays", 7
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void registerStoresHashedAgentTokenAndReturnsRawTokenOnce() {
        String tokenHash = tokenHasher.sha256Hex("raw-agent-token");
        when(jdbcTemplate.queryForList(
                contains("FROM agent_activation_tokens"),
                eq("register-1"),
                eq(tokenHasher.sha256Hex(VALID_ACTIVATION_TOKEN))
        )).thenReturn(List.of(validActivationRow(null, null)));
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq(5L), eq("register-1")))
                .thenReturn(List.of());
        when(jdbcTemplate.update(contains("UPDATE agent_activation_tokens"), eq(5L))).thenReturn(1);
        when(jdbcTemplate.queryForMap(
                contains("agent_token_hash"),
                eq(20L),
                eq(5L),
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
                "activationToken", VALID_ACTIVATION_TOKEN,
                "deviceFingerprintHash", "fingerprint-hash",
                "hostnameHash", "host-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        ));

        assertThat(response.get("agentToken")).isEqualTo("raw-agent-token");
        assertThat(response.get("deviceId")).isEqualTo("device-public-id");
        assertThat(response).containsOnlyKeys("deviceId", "status", "agentToken", "tokenType");
        assertThat(response.get("tokenType")).isEqualTo("Bearer");
        assertThat(tokenHash).isNotEqualTo("raw-agent-token");
    }

    @Test
    void registerRejectsMissingActivationTokenBeforeIssuingToken() {
        PcAgentAsService guardedService = new PcAgentAsService(
                jdbcTemplate,
                tokenHasher,
                CLOCK,
                () -> {
                    throw new AssertionError("token must not be generated when activationToken is missing");
                }
        );

        assertThatThrownBy(() -> guardedService.register(MockData.map(
                "deviceFingerprintHash", "fingerprint-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void registerRejectsMissingRequiredFieldsBeforeIssuingToken() {
        assertThatThrownBy(() -> service.register(MockData.map(
                "activationToken", VALID_ACTIVATION_TOKEN,
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void registerRejectsInvalidActivationToken() {
        when(jdbcTemplate.queryForList(
                contains("FROM agent_activation_tokens"),
                eq("register-1"),
                eq(tokenHasher.sha256Hex("invalid-token"))
        )).thenReturn(List.of());

        assertThatThrownBy(() -> service.register(MockData.map(
                "activationToken", "invalid-token",
                "deviceFingerprintHash", "fingerprint-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void registerRefreshesExistingDeviceForSameRegistrationKeyWithoutRawTokenStorage() {
        String tokenHash = tokenHasher.sha256Hex("raw-agent-token");
        when(jdbcTemplate.queryForList(
                contains("FROM agent_activation_tokens"),
                eq("register-1"),
                eq(tokenHasher.sha256Hex(VALID_ACTIVATION_TOKEN))
        )).thenReturn(List.of(validActivationRow(10L, Instant.parse("2026-07-02T00:00:00Z"))));
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq(5L), eq("register-1")))
                .thenReturn(List.of(MockData.map(
                        "device_internal_id", 10L,
                        "device_id", "device-public-id",
                        "status", "ACTIVE"
                )));
        when(jdbcTemplate.queryForMap(
                contains("UPDATE agent_devices"),
                eq("fingerprint-hash"),
                eq("host-hash"),
                eq(tokenHash),
                eq("Windows 11"),
                eq("0.1.1"),
                eq("policy-v2"),
                eq(10L)
        )).thenReturn(MockData.map(
                "device_internal_id", 10L,
                "device_id", "device-public-id",
                "status", "ACTIVE"
        ));

        Map<String, Object> response = service.register(MockData.map(
                "activationToken", VALID_ACTIVATION_TOKEN,
                "deviceFingerprintHash", "fingerprint-hash",
                "hostnameHash", "host-hash",
                "registrationIdempotencyKey", "register-1",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.1",
                "policyVersion", "policy-v2"
        ));

        assertThat(response.get("deviceId")).isEqualTo("device-public-id");
        assertThat(response.get("agentToken")).isEqualTo("raw-agent-token");
        assertThat(tokenHash).isNotEqualTo("raw-agent-token");
    }

    @Test
    void registerRejectsUsedActivationTokenForDifferentRegistrationKeyBeforeIssuingAgentToken() {
        PcAgentAsService guardedService = new PcAgentAsService(
                jdbcTemplate,
                tokenHasher,
                CLOCK,
                () -> {
                    throw new AssertionError("token must not be generated for used activation token");
                }
        );
        when(jdbcTemplate.queryForList(
                contains("FROM agent_activation_tokens"),
                eq("register-2"),
                eq(tokenHasher.sha256Hex(VALID_ACTIVATION_TOKEN))
        )).thenReturn(List.of(validActivationRow(null, Instant.parse("2026-07-02T00:00:00Z"))));

        assertThatThrownBy(() -> guardedService.register(MockData.map(
                "activationToken", VALID_ACTIVATION_TOKEN,
                "deviceFingerprintHash", "fingerprint-hash",
                "registrationIdempotencyKey", "register-2",
                "osVersion", "Windows 11",
                "agentVersion", "0.1.0",
                "policyVersion", "policy-v1"
        )))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void saveConsentStoresExplicitRevokeState() {
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_consents"),
                eq(20L),
                eq(10L),
                eq("SERVER_UPLOAD"),
                eq("policy-v1"),
                eq("consent-key"),
                eq(false),
                eq(false),
                eq(false)
        )).thenReturn(MockData.map(
                "id", "consent-public-id",
                "consent_type", "SERVER_UPLOAD",
                "policy_version", "policy-v1",
                "accepted", false,
                "accepted_at", null,
                "revoked_at", Instant.parse("2026-07-02T00:00:00Z")
        ));

        Map<String, Object> response = service.saveConsent(
                AGENT,
                MockData.map(
                        "consentType", "SERVER_UPLOAD",
                        "policyVersion", "policy-v1",
                        "accepted", false
                ),
                "consent-key"
        );

        assertThat(response.get("accepted")).isEqualTo(false);
        assertThat(response.get("revokedAt")).isEqualTo(Instant.parse("2026-07-02T00:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REMOTE_CONNECTION", "REMOTE_FULL_CONTROL", "HIGH_RISK_REMOTE_ACTION"})
    void saveConsentAcceptsFinalScenarioStepConsentTypes(String consentType) {
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_consents"),
                eq(20L),
                eq(10L),
                eq(consentType),
                eq("policy-v1"),
                eq("consent-key-" + consentType),
                eq(true),
                eq(true),
                eq(true)
        )).thenReturn(MockData.map(
                "id", "consent-public-id",
                "consent_type", consentType,
                "policy_version", "policy-v1",
                "accepted", true,
                "accepted_at", Instant.parse("2026-07-02T00:00:00Z"),
                "revoked_at", null
        ));

        Map<String, Object> response = service.saveConsent(
                AGENT,
                MockData.map(
                        "consentType", consentType,
                        "policyVersion", "policy-v1",
                        "accepted", true,
                        "asTicketId", "ticket-public-id",
                        "remoteSessionId", "remote-session-public-id"
                ),
                "consent-key-" + consentType
        );

        assertThat(response.get("consentType")).isEqualTo(consentType);
        assertThat(response.get("accepted")).isEqualTo(true);
    }

    @Test
    void saveConsentRejectsUnknownConsentType() {
        assertThatThrownBy(() -> service.saveConsent(
                AGENT,
                MockData.map(
                        "consentType", "REMOTE_CONTROL",
                        "policyVersion", "policy-v1",
                        "accepted", true
                ),
                "consent-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void saveConsentRejectsMissingAcceptedFlag() {
        assertThatThrownBy(() -> service.saveConsent(
                AGENT,
                MockData.map(
                        "consentType", "SERVER_UPLOAD",
                        "policyVersion", "policy-v1"
                ),
                "consent-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void heartbeatUpdatesDeviceLastSeenAndStoresHeartbeat() {
        Instant seenAt = Instant.parse("2026-07-02T00:00:00Z");
        when(jdbcTemplate.queryForMap(
                contains("UPDATE agent_devices"),
                eq("0.1.1"),
                eq("policy-v2"),
                eq(10L),
                eq(20L)
        )).thenReturn(MockData.map(
                "status", "ACTIVE",
                "last_seen_at", seenAt
        ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_heartbeats"),
                eq(10L),
                eq("0.1.1"),
                eq("RUNNING"),
                eq("VISIBLE"),
                eq("policy-v2"),
                eq("heartbeat-key")
        )).thenReturn(MockData.map(
                "id", "heartbeat-public-id",
                "received_at", seenAt
        ));

        Map<String, Object> response = service.heartbeat(
                AGENT,
                MockData.map(
                        "agentVersion", "0.1.1",
                        "serviceStatus", "RUNNING",
                        "trayStatus", "VISIBLE",
                        "policyVersion", "policy-v2"
                ),
                "heartbeat-key"
        );

        assertThat(response.get("deviceId")).isEqualTo("device-public-id");
        assertThat(response.get("status")).isEqualTo("ACTIVE");
        assertThat(response.get("lastSeenAt")).isEqualTo(seenAt);
        assertThat(response).containsOnlyKeys(
                "id",
                "deviceId",
                "status",
                "lastSeenAt",
                "receivedAt",
                "pendingCommands"
        );
    }

    @Test
    void repeatedHeartbeatUpdatesDeviceAndStoresSeparateHeartbeatRows() {
        Instant firstSeenAt = Instant.parse("2026-07-02T00:00:00Z");
        Instant secondSeenAt = Instant.parse("2026-07-02T00:00:05Z");
        when(jdbcTemplate.queryForMap(
                contains("UPDATE agent_devices"),
                eq("0.1.1"),
                eq("policy-v2"),
                eq(10L),
                eq(20L)
        )).thenReturn(
                MockData.map("status", "ACTIVE", "last_seen_at", firstSeenAt),
                MockData.map("status", "ACTIVE", "last_seen_at", secondSeenAt)
        );
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_heartbeats"),
                eq(10L),
                eq("0.1.1"),
                eq("RUNNING"),
                eq("VISIBLE"),
                eq("policy-v2"),
                eq("heartbeat-key")
        )).thenReturn(
                MockData.map("id", "heartbeat-public-id-1", "received_at", firstSeenAt),
                MockData.map("id", "heartbeat-public-id-2", "received_at", secondSeenAt)
        );

        Map<String, Object> firstResponse = service.heartbeat(
                AGENT,
                MockData.map(
                        "agentVersion", "0.1.1",
                        "serviceStatus", "RUNNING",
                        "trayStatus", "VISIBLE",
                        "policyVersion", "policy-v2"
                ),
                "heartbeat-key"
        );
        Map<String, Object> secondResponse = service.heartbeat(
                AGENT,
                MockData.map(
                        "agentVersion", "0.1.1",
                        "serviceStatus", "RUNNING",
                        "trayStatus", "VISIBLE",
                        "policyVersion", "policy-v2"
                ),
                "heartbeat-key"
        );

        assertThat(firstResponse.get("lastSeenAt")).isEqualTo(firstSeenAt);
        assertThat(secondResponse.get("lastSeenAt")).isEqualTo(secondSeenAt);
        verify(jdbcTemplate, times(2)).queryForMap(
                contains("UPDATE agent_devices"),
                eq("0.1.1"),
                eq("policy-v2"),
                eq(10L),
                eq(20L)
        );
        verify(jdbcTemplate, times(2)).queryForMap(
                contains("INSERT INTO agent_heartbeats"),
                eq(10L),
                eq("0.1.1"),
                eq("RUNNING"),
                eq("VISIBLE"),
                eq("policy-v2"),
                eq("heartbeat-key")
        );
    }

    @Test
    void uploadLogsCreatesUploadJobLogUploadAndTicketWithDiagnosisStatus() {
        String symptom = "게임 중 화면 드라이버 경고와 발열이 있습니다.";
        String multipartDecodedSymptom = iso88591Mojibake(symptom);
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
                eq(20),
                eq("agent-log.jsonl.gz"),
                any(Long.class),
                eq("agent-logs/device-public-id/agent-log.jsonl.gz"),
                any(String.class),
                any(String.class),
                any(Timestamp.class),
                any(Timestamp.class)
        )).thenReturn(MockData.map(
                "log_upload_internal_id", 200L,
                "log_upload_id", "log-upload-public-id",
                "status", "UPLOADED",
                "file_name", "agent-log.jsonl.gz",
                "file_size", 65L,
                "range_minutes", 20,
                "delete_after", Instant.parse("2026-08-01T00:00:00Z")
        ));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO agent_log_bundles"),
                eq(100L),
                eq(200L),
                eq(1),
                eq("agent-logs/device-public-id/agent-log.jsonl.gz"),
                any(String.class),
                any(Long.class),
                eq(Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")))
        )).thenReturn(MockData.map("log_bundle_id", "bundle-public-id"));
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO as_tickets"),
                any(String.class),
                eq(20L),
                eq(200L),
                eq(symptom),
                eq("REMOTE_POSSIBLE"),
                eq("MEDIUM"),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                eq("NONE"),
                any(String.class)
        )).thenReturn(MockData.map(
                "ticket_id", "ticket-public-id",
                "status", "OPEN",
                "analysis_status", "RULE_READY",
                "review_status", "REQUIRED",
                "support_decision", "REMOTE_POSSIBLE",
                "risk_level", "MEDIUM"
        ));

        Map<String, Object> response = service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip(driverErrorLogs())),
                MockData.map("symptomType", "REMOTE_DRIVER_OS", "symptom", multipartDecodedSymptom),
                "upload-key"
        );

        assertThat(response.get("uploadJobId")).isEqualTo("upload-job-public-id");
        assertThat(response.get("logUploadId")).isEqualTo("log-upload-public-id");
        assertThat(response.get("ticketId")).isEqualTo("ticket-public-id");
        assertThat(response.get("analysisStatus")).isEqualTo("RULE_READY");
        assertThat(response.get("reviewStatus")).isEqualTo("REQUIRED");
        assertThat(response.get("supportDecision")).isEqualTo("REMOTE_POSSIBLE");
        assertThat(response.get("riskLevel")).isEqualTo("MEDIUM");
        assertThat(response.get("deleteAfter")).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(response.get("rangeMinutes")).isEqualTo(20);
        assertThat(response.get("rawSamplesCount")).isEqualTo(2);

        verify(jdbcTemplate).queryForMap(contains("INSERT INTO agent_upload_jobs"), eq(10L), eq("upload-key"), any(), any());
        verify(jdbcTemplate).queryForMap(
                contains("INSERT INTO agent_log_uploads"),
                eq(20L),
                eq(10L),
                eq(100L),
                eq(20),
                eq("agent-log.jsonl.gz"),
                any(Long.class),
                eq("agent-logs/device-public-id/agent-log.jsonl.gz"),
                any(String.class),
                any(String.class),
                any(Timestamp.class),
                any(Timestamp.class)
        );
        verify(jdbcTemplate).queryForMap(
                contains("INSERT INTO as_tickets"),
                any(String.class),
                eq(20L),
                eq(200L),
                eq(symptom),
                eq("REMOTE_POSSIBLE"),
                eq("MEDIUM"),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                any(String.class),
                eq("NONE"),
                any(String.class)
        );
    }

    @Test
    void previewAsRagAnalyzesAgentGzipWithoutCreatingTicket() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(1);
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence"))).thenReturn(List.of(asRagEvidence(
                "00000000-0000-4000-8000-000000058101",
                "as-rag-remote-driver-os",
                "REMOTE_DRIVER_OS",
                "REMOTE_SUPPORT",
                "REMOTE_POSSIBLE",
                "DRIVER_ERROR_REPEAT",
                "드라이버 오류 반복",
                "{\"keywords\":[\"display driver\",\"nvlddmkm\"],\"remoteActions\":[\"DRIVER_ROLLBACK\"],\"visitReasons\":[]}",
                0.92
        )));

        Map<String, Object> response = service.previewAsRag(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip(driverErrorLogs())),
                MockData.map("symptomType", "REMOTE_DRIVER_OS"),
                "preview-key"
        );

        assertThat(response.get("previewSource")).isEqualTo("PC_AGENT_LOG_UPLOAD");
        assertThat(response.get("recommendedService")).isEqualTo("REMOTE_SUPPORT");
        assertThat(response.get("supportDecision")).isEqualTo("REMOTE_POSSIBLE");
        assertThat(response.get("rangeMinutes")).isEqualTo(20);
        assertThat(response.get("rawSamplesCount")).isEqualTo(2);
        assertThat(response.get("incidentWindow")).isInstanceOf(Map.class);
        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO agent_upload_jobs"), any(), any(), any(), any());
        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    @Test
    void uploadLogsRejectsMissingServerUploadConsent() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip(singleRawLog())),
                MockData.map("symptomType", "REMOTE_AGENT"),
                "upload-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void uploadLogsRejectsInvalidIdempotencyKeyBeforeReadingConsentOrCreatingRows() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip(singleRawLog())),
                MockData.map("symptomType", "REMOTE_AGENT"),
                "bad key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(jdbcTemplate, never()).queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L));
        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    @Test
    void uploadLogsRejectsInvalidGzipBeforeCreatingRows() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", "not-gzip".getBytes()),
                MockData.map("symptomType", "REMOTE_AGENT"),
                "upload-key"
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException apiException = (ApiException) exception;
                    assertThat(apiException.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiException.code()).isEqualTo("FILE_VALIDATION_ERROR");
                });
    }

    @Test
    void uploadLogsRejectsMissingRawLogRequiredFieldBeforeCreatingTicket() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip("{\"schemaVersion\":\"1\"}\n")),
                MockData.map("symptomType", "REMOTE_DRIVER_OS", "symptom", "GPU temperature spike"),
                "upload-key"
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("FILE_VALIDATION_ERROR"));

        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    @Test
    void uploadLogsDoesNotRejectCustomIncidentWindowBeforeConsentCheck() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip(singleRawLog())),
                MockData.map(
                        "symptomType", "REMOTE_DRIVER_OS",
                        "incidentStartedAt", "2026-07-01T23:40:00Z",
                        "incidentEndedAt", "2026-07-02T00:25:00Z"
                ),
                "upload-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(jdbcTemplate).queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L));
    }

    @Test
    void uploadLogsRejectsNonGzipExtensionBeforeCreatingTicket() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl", "application/json", gzip(singleRawLog())),
                MockData.map("symptomType", "REMOTE_AGENT"),
                "upload-key"
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("FILE_VALIDATION_ERROR"));

        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    @Test
    void uploadLogsRejectsEmptyGzipContentBeforeCreatingTicket() {
        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip("")),
                MockData.map("symptomType", "REMOTE_AGENT"),
                "upload-key"
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("FILE_VALIDATION_ERROR"));

        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    @Test
    void uploadLogsRejectsMissingConsentBeforeCreatingTicket() {
        when(jdbcTemplate.queryForObject(contains("FROM agent_consents"), eq(Integer.class), eq(10L)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.uploadLogs(
                AGENT,
                new MockMultipartFile("file", "agent-log.jsonl.gz", "application/gzip", gzip(singleRawLog())),
                MockData.map("symptomType", "REMOTE_AGENT"),
                "upload-key"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
    }

    private static Map<String, Object> asRagEvidence(
            String id,
            String sourceId,
            String symptomType,
            String recommendedService,
            String supportDecision,
            String reasonCode,
            String summary,
            String metadata,
            double score
    ) {
        return MockData.map(
                "id", id,
                "source_id", sourceId,
                "symptom_type", symptomType,
                "source_type", "SUPPORT_POLICY",
                "recommended_service", recommendedService,
                "support_decision", supportDecision,
                "reason_code", reasonCode,
                "title", summary,
                "chunk_text", summary,
                "summary", summary,
                "score", score,
                "metadata", metadata
        );
    }

    private static byte[] gzip(String content) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutput = new GZIPOutputStream(output)) {
                gzipOutput.write(content.getBytes());
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String driverErrorLogs() {
        return rawLog(1, "2026-07-01T23:50:00Z", "WINDOWS_EVENT", "display driver nvlddmkm reset at C:\\Users\\kim\\driver.log")
                + rawLog(2, "2026-07-01T23:55:00Z", "WINDOWS_EVENT", "display driver install failure token=secret-token");
    }

    private static String singleRawLog() {
        return rawLog(1, "2026-07-01T23:50:00Z", "AGENT_EVENT", "agent upload diagnostic event");
    }

    private static String rawLog(long sequence, String collectedAt, String kind, String message) {
        return """
                {"schemaVersion":"1","collectedAt":"%s","agentId":"agent-public-id","sequence":%d,"kind":"%s","payload":{"message":"%s"},"privacyFlags":{"masked":true,"containsRawPath":false}}
                """.formatted(collectedAt, sequence, kind, message.replace("\\", "\\\\").replace("\"", "\\\""));
    }

    private static String iso88591Mojibake(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static Map<String, Object> validActivationRow(Long existingDeviceInternalId, Instant usedAt) {
        return MockData.map(
                "activation_token_id", 5L,
                "user_id", 20L,
                "expires_at", Instant.parse("2026-07-09T00:00:00Z"),
                "used_at", usedAt,
                "revoked_at", null,
                "existing_device_internal_id", existingDeviceInternalId
        );
    }
}
