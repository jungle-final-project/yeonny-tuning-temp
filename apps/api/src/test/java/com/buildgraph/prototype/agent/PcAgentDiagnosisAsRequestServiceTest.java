package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.ticket.SupportChatRoomProvisioner;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class PcAgentDiagnosisAsRequestServiceTest {
    private static final String DIAGNOSIS_ID = "9a0e3c21-6648-41e7-a88e-17be1761b806";
    private static final String RESULT_ID = "result-1";
    private static final String DEVICE_ID = "6a0e3c21-6648-41e7-a88e-17be1761b806";
    private static final String EVALUATED_AT = "2026-07-14T01:00:00Z";
    private static final String SYMPTOM = "게임 실행 후 프레임 저하";
    private static final String TITLE = "GPU 냉각 계통 이상 가능성이 높습니다.";
    private static final String SUMMARY = "고온과 팬 정지, 열 제한이 함께 확인되었습니다.";
    private static final AgentPrincipal PRINCIPAL = new AgentPrincipal(10L, DEVICE_ID, 20L, "ACTIVE");

    @Test
    void createsTicketFromStoredPhysicalDiagnosisAndServerConsent() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        StubBroker broker = eligibleBroker();
        PcAgentDiagnosisAsRequestService service = service(jdbc, broker);

        Map<String, Object> response = service.create(PRINCIPAL, validRequest(), DIAGNOSIS_ID);

        assertThat(response)
                .containsEntry("requestId", "ticket-public-id")
                .containsEntry("requestNumber", "AS-20260714-000001")
                .containsEntry("status", "OPEN")
                .containsEntry("requestType", "PHYSICAL_INSPECTION")
                .containsEntry("mode", "LIVE")
                .containsEntry("webPath", "/support/ticket-public-id");
        assertThat(jdbc.insertCount).isEqualTo(1);
        assertThat(jdbc.lastInsertSql).contains("nextval('as_ticket_request_number_seq')");
    }

    @Test
    void sameDiagnosisReturnsExistingTicketWithoutRecreatingIt() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.existingRows = List.of(jdbc.ticketRow());
        PcAgentDiagnosisAsRequestService service = service(jdbc, new StubBroker());

        Map<String, Object> response = service.create(PRINCIPAL, validRequest(), DIAGNOSIS_ID);

        assertThat(response).containsEntry("requestNumber", "AS-20260714-000001");
        assertThat(jdbc.insertCount).isZero();
    }

    @Test
    void softDeletedRequestStillPreventsASecondTicketForTheSameDiagnosis() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        Map<String, Object> deleted = new LinkedHashMap<>(jdbc.ticketRow());
        deleted.put("deleted_at", Instant.parse(EVALUATED_AT));
        jdbc.existingRows = List.of(deleted);

        assertCode(() -> service(jdbc, new StubBroker()).create(PRINCIPAL, validRequest(), DIAGNOSIS_ID),
                "AS_REQUEST_ALREADY_EXISTS");
        assertThat(jdbc.insertCount).isZero();
    }

    @Test
    void rejectsResultIdThatDoesNotMatchBrokerResult() {
        PcAgentDiagnosisAsRequestService.CreateRequest request = new PcAgentDiagnosisAsRequestService.CreateRequest(
                DIAGNOSIS_ID, "other-result", DEVICE_ID, "PHYSICAL_INSPECTION", SYMPTOM,
                TITLE, SUMMARY, evidence(), EVALUATED_AT, "LIVE", true
        );

        assertCode(() -> service(new StubJdbcTemplate(), eligibleBroker()).create(PRINCIPAL, request, DIAGNOSIS_ID),
                "INVALID_AS_REQUEST");
    }

    @Test
    void acceptsDiagnosisThatIsNotPhysicalInspection() {
        // 이상 근거가 없는 결과(정상·자동 복구 가능)로도 사용자가 AS를 접수할 수 있어야 한다.
        StubBroker software = broker("SOFTWARE_RECOVERY", evidence(), DEVICE_ID, "LIVE");
        StubJdbcTemplate jdbc = new StubJdbcTemplate();

        Map<String, Object> created = service(jdbc, software).create(PRINCIPAL, validRequest(), DIAGNOSIS_ID);

        assertThat(created.get("requestType")).isEqualTo("PHYSICAL_INSPECTION");
    }

    @Test
    void rejectsDiagnosisWithoutEvidence() {
        StubBroker noEvidence = broker("PHYSICAL_INSPECTION", List.of(), DEVICE_ID, "LIVE");
        assertCode(() -> service(new StubJdbcTemplate(), noEvidence).create(PRINCIPAL, validRequest(), DIAGNOSIS_ID),
                "AS_NOT_ELIGIBLE");
    }

    @Test
    void rejectsUnsupportedRequestType() {
        PcAgentDiagnosisAsRequestService.CreateRequest remote = new PcAgentDiagnosisAsRequestService.CreateRequest(
                DIAGNOSIS_ID, RESULT_ID, DEVICE_ID, "REMOTE_SUPPORT", SYMPTOM,
                TITLE, SUMMARY, evidence(), EVALUATED_AT, "LIVE", true
        );
        assertCode(() -> service(new StubJdbcTemplate(), eligibleBroker()).create(PRINCIPAL, remote, DIAGNOSIS_ID),
                "AS_NOT_ELIGIBLE");
    }

    @Test
    void rejectsMissingExplicitOrStoredConsent() {
        PcAgentDiagnosisAsRequestService.CreateRequest notAccepted = new PcAgentDiagnosisAsRequestService.CreateRequest(
                DIAGNOSIS_ID, RESULT_ID, DEVICE_ID, "PHYSICAL_INSPECTION", SYMPTOM,
                TITLE, SUMMARY, evidence(), EVALUATED_AT, "LIVE", false
        );
        assertCode(() -> service(new StubJdbcTemplate(), eligibleBroker()).create(PRINCIPAL, notAccepted, DIAGNOSIS_ID),
                "CONSENT_REQUIRED");

        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.consentCount = 0;
        assertCode(() -> service(jdbc, eligibleBroker()).create(PRINCIPAL, validRequest(), DIAGNOSIS_ID),
                "CONSENT_REQUIRED");
    }

    @Test
    void rejectsDeviceModeAndIdempotencyMismatches() {
        PcAgentDiagnosisAsRequestService.CreateRequest wrongDevice = new PcAgentDiagnosisAsRequestService.CreateRequest(
                DIAGNOSIS_ID, RESULT_ID, "other-device", "PHYSICAL_INSPECTION", SYMPTOM,
                TITLE, SUMMARY, evidence(), EVALUATED_AT, "LIVE", true
        );
        assertCode(() -> service(new StubJdbcTemplate(), eligibleBroker()).create(PRINCIPAL, wrongDevice, DIAGNOSIS_ID),
                "DEVICE_MISMATCH");

        StubBroker demoBroker = broker("PHYSICAL_INSPECTION", evidence(), DEVICE_ID, "DEMO");
        assertCode(() -> service(new StubJdbcTemplate(), demoBroker).create(PRINCIPAL, validRequest(), DIAGNOSIS_ID),
                "INVALID_AS_REQUEST");
        assertCode(() -> service(new StubJdbcTemplate(), eligibleBroker()).create(PRINCIPAL, validRequest(), "other-key"),
                "INVALID_AS_REQUEST");
    }

    @Test
    void storageFailureDoesNotReturnARequestNumber() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.failInsert = true;

        assertThatThrownBy(() -> service(jdbc, eligibleBroker()).create(PRINCIPAL, validRequest(), DIAGNOSIS_ID))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("simulated AS ticket insert failure");
        assertThat(jdbc.insertCount).isEqualTo(1);
    }

    @Test
    void createsAnotherTicketWhenTheUserAlreadyHasAnActiveSupportChat() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();

        Map<String, Object> response = service(jdbc, eligibleBroker()).create(
                PRINCIPAL,
                validRequest(),
                DIAGNOSIS_ID
        );

        assertThat(response)
                .containsEntry("requestId", "ticket-public-id")
                .containsEntry("supportChatRoomId", "support-room-1");
        assertThat(jdbc.insertCount).isEqualTo(1);
    }

    @Test
    void recoversTheServerOwnedRequestFromTheLedgerAfterBrokerRestart() {
        StubJdbcTemplate jdbc = new StubJdbcTemplate();
        jdbc.ledgerRows = List.of(jdbc.ledgerRow());
        StubBroker broker = eligibleBroker();
        broker.request = null;

        Map<String, Object> response = service(jdbc, broker).create(PRINCIPAL, validRequest(), DIAGNOSIS_ID);

        assertThat(response).containsEntry("requestNumber", "AS-20260714-000001");
        assertThat(jdbc.insertCount).isEqualTo(1);
    }

    @Test
    void rejectsEvidenceSummaryThatWasNotInTheStoredDiagnosisResult() {
        Map<String, Object> forged = new LinkedHashMap<>(evidence().get(0));
        forged.put("value", 40);
        PcAgentDiagnosisAsRequestService.CreateRequest request = new PcAgentDiagnosisAsRequestService.CreateRequest(
                DIAGNOSIS_ID, RESULT_ID, DEVICE_ID, "PHYSICAL_INSPECTION", SYMPTOM,
                TITLE, SUMMARY, List.of(forged), EVALUATED_AT, "LIVE", true
        );

        assertCode(() -> service(new StubJdbcTemplate(), eligibleBroker()).create(PRINCIPAL, request, DIAGNOSIS_ID),
                "INVALID_AS_REQUEST");
    }

    private static PcAgentDiagnosisAsRequestService service(StubJdbcTemplate jdbc, StubBroker broker) {
        SupportChatRoomProvisioner rooms = new SupportChatRoomProvisioner(jdbc) {
            @Override
            public String ensureRoom(Long userInternalId, Long ticketInternalId) {
                return "support-room-1";
            }
        };
        return new PcAgentDiagnosisAsRequestService(jdbc, broker, rooms);
    }

    private static StubBroker eligibleBroker() {
        return broker("PHYSICAL_INSPECTION", evidence(), DEVICE_ID, "LIVE");
    }

    private static StubBroker broker(String resolutionType, List<Map<String, Object>> evidence, String deviceId, String mode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diagnosisId", DIAGNOSIS_ID);
        result.put("resultId", RESULT_ID);
        result.put("severity", "CRITICAL");
        result.put("resolutionType", resolutionType);
        result.put("title", TITLE);
        result.put("summary", SUMMARY);
        result.put("evidence", evidence);
        result.put("findings", List.of(Map.of("code", "GPU_COOLING")));
        result.put("evaluatedAt", EVALUATED_AT);
        StubBroker broker = new StubBroker();
        broker.result = new PcAgentDiagnosisSocketBroker.DiagnosisResultRecord(deviceId, DIAGNOSIS_ID, RESULT_ID, result);
        broker.request = new PcAgentDiagnosisRequest(
                DIAGNOSIS_ID,
                deviceId,
                SYMPTOM,
                List.of("gpu", "cooling"),
                Instant.parse("2026-07-14T00:58:00Z"),
                Instant.parse("2026-07-14T01:02:00Z"),
                mode
        );
        return broker;
    }

    private static PcAgentDiagnosisAsRequestService.CreateRequest validRequest() {
        return new PcAgentDiagnosisAsRequestService.CreateRequest(
                DIAGNOSIS_ID,
                RESULT_ID,
                DEVICE_ID,
                "PHYSICAL_INSPECTION",
                SYMPTOM,
                TITLE,
                SUMMARY,
                evidence(),
                EVALUATED_AT,
                "LIVE",
                true
        );
    }

    private static List<Map<String, Object>> evidence() {
        return List.of(Map.ofEntries(
                Map.entry("taskId", "gpu-temperature"),
                Map.entry("component", "gpu"),
                Map.entry("metricType", "temperature"),
                Map.entry("value", 95),
                Map.entry("unit", "C"),
                Map.entry("availability", "AVAILABLE"),
                Map.entry("status", "ABNORMAL"),
                Map.entry("source", "nvidia-smi"),
                Map.entry("sampledAt", EVALUATED_AT)
        ));
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }

    private static final class StubBroker extends PcAgentDiagnosisSocketBroker {
        private DiagnosisResultRecord result;
        private PcAgentDiagnosisRequest request;

        @Override
        DiagnosisResultRecord latestResult(String diagnosisId) {
            return result;
        }

        @Override
        PcAgentDiagnosisRequest latestRequest(String diagnosisId) {
            return request;
        }
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private List<Map<String, Object>> existingRows = List.of();
        private List<Map<String, Object>> ledgerRows = List.of();
        private int consentCount = 1;
        private int insertCount;
        private String lastInsertSql;
        private boolean failInsert;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FROM as_tickets")) {
                return existingRows;
            }
            if (sql.contains("FROM agent_devices")) {
                return List.of(Map.of("id", 10L));
            }
            if (sql.contains("FROM users")) {
                return List.of(Map.of("id", 20L));
            }
            if (sql.contains("FROM pc_agent_diagnosis_requests")) {
                return ledgerRows;
            }
            throw new AssertionError("Unexpected queryForList SQL: " + sql);
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(consentCount);
        }

        @Override
        public Map<String, Object> queryForMap(String sql, Object... args) {
            insertCount += 1;
            lastInsertSql = sql;
            if (failInsert) {
                throw new DataAccessResourceFailureException("simulated AS ticket insert failure");
            }
            return ticketRow();
        }

        private Map<String, Object> ticketRow() {
            return Map.ofEntries(
                    Map.entry("ticket_internal_id", 30L),
                    Map.entry("request_id", "ticket-public-id"),
                    Map.entry("request_number", "AS-20260714-000001"),
                    Map.entry("status", "OPEN"),
                    Map.entry("request_type", "PHYSICAL_INSPECTION"),
                    Map.entry("symptom", SYMPTOM),
                    Map.entry("diagnosis_title", TITLE),
                    Map.entry("diagnosis_summary", SUMMARY),
                    Map.entry("evidence_summary", evidence()),
                    Map.entry("diagnosed_at", Instant.parse(EVALUATED_AT)),
                    Map.entry("diagnosis_mode", "LIVE"),
                    Map.entry("diagnosis_consent_accepted_at", Instant.parse(EVALUATED_AT)),
                    Map.entry("created_at", Instant.parse(EVALUATED_AT))
            );
        }

        private Map<String, Object> ledgerRow() {
            return Map.ofEntries(
                    Map.entry("device_id", DEVICE_ID),
                    Map.entry("symptom", SYMPTOM),
                    Map.entry("requested_checks", List.of("gpu", "cooling")),
                    Map.entry("requested_at", Instant.parse("2026-07-14T00:58:00Z")),
                    Map.entry("expires_at", Instant.parse("2026-07-14T01:02:00Z")),
                    Map.entry("mode", "LIVE")
            );
        }
    }
}
