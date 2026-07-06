package com.buildgraph.prototype.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AsLogRagAnalysisServiceTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final AsLogRagAnalysisService service = new AsLogRagAnalysisService(jdbcTemplate);

    @Test
    void driverLogRecommendsRemoteSupportWithoutUsingExistingRagPackage() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(
                        evidence(
                                "00000000-0000-4000-8000-000000058101",
                                "as-rag-remote-driver-os",
                                "REMOTE_DRIVER_OS",
                                "REMOTE_SUPPORT",
                                "REMOTE_POSSIBLE",
                                "DRIVER_ERROR_REPEAT",
                                "드라이버 오류 반복",
                                "{\"keywords\":[\"display driver\",\"nvlddmkm\"],\"remoteActions\":[\"DRIVER_ROLLBACK\"],\"visitReasons\":[]}",
                                0.92
                        ),
                        evidence(
                                "00000000-0000-4000-8000-000000058102",
                                "as-rag-unsupported-scope",
                                null,
                                "DIAGNOSIS_ONLY",
                                "UNSUPPORTED",
                                "UNSUPPORTED_SCOPE",
                                "지원 범위 밖",
                                "{\"keywords\":[\"isp\"],\"remoteActions\":[],\"visitReasons\":[]}",
                                0.58
                        )
                ));

        Map<String, Object> result = service.analyze(
                "agent-log.jsonl",
                "{\"kind\":\"EVENT\",\"payload\":{\"message\":\"nvlddmkm display driver reset repeated\"}}",
                30
        );

        assertThat(result)
                .containsEntry("retrievalMode", "AS_RAG_KEYWORD")
                .containsEntry("recommendedService", "REMOTE_SUPPORT")
                .containsEntry("supportDecision", "REMOTE_POSSIBLE");
        assertThat((List<?>) result.get("evidence")).hasSize(1);
        assertThat(AsLogRagAnalysisService.supportRouting(result).get("remoteActions"))
                .asList()
                .contains("DRIVER_ROLLBACK");
    }

    @Test
    void hardwareFailureLogRecommendsVisitSupport() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(evidence(
                        "00000000-0000-4000-8000-000000058201",
                        "as-rag-visit-power-thermal",
                        "VISIT_POWER_SHUTDOWN",
                        "VISIT_SUPPORT",
                        "VISIT_REQUIRED",
                        "POWER_OR_THERMAL_SHUTDOWN",
                        "전원 또는 열 차단",
                        "{\"keywords\":[\"kernel-power\",\"thermal shutdown\"],\"remoteActions\":[],\"visitReasons\":[\"KERNEL_POWER_REPEAT\"]}",
                        0.95
                )));

        Map<String, Object> result = service.analyze(
                "agent-log.jsonl",
                "event id 41 kernel-power repeated after load",
                30
        );

        assertThat(result)
                .containsEntry("recommendedService", "VISIT_SUPPORT")
                .containsEntry("supportDecision", "VISIT_REQUIRED");
        assertThat(AsLogRagAnalysisService.riskLevel(result)).isEqualTo("HIGH");
    }

    @Test
    void weakDiskIoSignalFallsBackToDiagnosisOnly() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(
                        evidence(
                                "00000000-0000-4000-8000-000000058210",
                                "as-rag-visit-disk-failure",
                                "VISIT_DISK_FAILURE",
                                "VISIT_SUPPORT",
                                "VISIT_REQUIRED",
                                "DISK_FAILURE_SIGNAL",
                                "Disk failure signal",
                                "{\"keywords\":[\"smart critical\",\"bad block\",\"disk i/o\",\"i/o error\"],\"remoteActions\":[],\"visitReasons\":[\"STORAGE_REPLACEMENT_SUSPECTED\"]}",
                                0.96
                        ),
                        evidence(
                                "00000000-0000-4000-8000-000000058211",
                                "as-rag-diagnosis-unclear",
                                null,
                                "DIAGNOSIS_ONLY",
                                "NEEDS_MORE_INFO",
                                "INSUFFICIENT_EVIDENCE",
                                "More information required",
                                "{\"keywords\":[\"unknown\"]}",
                                0.60
                        )
                ));

        Map<String, Object> result = service.analyze(
                "agent-log.jsonl",
                "single disk i/o warning once",
                30
        );

        assertThat(result)
                .containsEntry("recommendedService", "DIAGNOSIS_ONLY")
                .containsEntry("supportDecision", "NEEDS_MORE_INFO");
    }

    @Test
    void smartCriticalDiskSignalUsesVisitRequiredDecision() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(evidence(
                        "00000000-0000-4000-8000-000000058220",
                        "as-rag-visit-disk-failure",
                        "VISIT_DISK_FAILURE",
                        "VISIT_SUPPORT",
                        "VISIT_REQUIRED",
                        "DISK_FAILURE_SIGNAL",
                        "Disk failure signal",
                        "{\"keywords\":[\"smart critical\",\"bad block\",\"disk i/o\",\"i/o error\"],\"remoteActions\":[],\"visitReasons\":[\"STORAGE_REPLACEMENT_SUSPECTED\"]}",
                        0.96
                )));

        Map<String, Object> result = service.analyze(
                "agent-log.jsonl",
                "smart critical predictive failure detected",
                30
        );

        assertThat(result)
                .containsEntry("recommendedService", "VISIT_SUPPORT")
                .containsEntry("supportDecision", "VISIT_REQUIRED");
        assertThat(result).doesNotContainEntry("supportDecision", "REPAIR_OR_REPLACE");
    }

    @Test
    void unsupportedScopeStaysDiagnosisOnlyWithBlockingFactor() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(evidence(
                        "00000000-0000-4000-8000-000000058230",
                        "as-rag-unsupported-scope",
                        null,
                        "DIAGNOSIS_ONLY",
                        "NEEDS_MORE_INFO",
                        "UNSUPPORTED_SCOPE",
                        "Out of scope",
                        "{\"keywords\":[\"isp\",\"router\"],\"remoteActions\":[],\"visitReasons\":[],\"blockingFactors\":[\"UNSUPPORTED_SCOPE\"]}",
                        0.58
                )));

        Map<String, Object> result = service.analyze(
                "agent-log.jsonl",
                "isp router outage suspected",
                30
        );

        assertThat(result)
                .containsEntry("recommendedService", "DIAGNOSIS_ONLY")
                .containsEntry("supportDecision", "NEEDS_MORE_INFO");
        assertThat(AsLogRagAnalysisService.supportRouting(result).get("blockingFactors"))
                .asList()
                .contains("UNSUPPORTED_SCOPE");
    }

    @Test
    void fileNameDoesNotCreateRepeatedVisitSignal() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(
                        evidence(
                                "00000000-0000-4000-8000-000000058240",
                                "as-rag-visit-fan-thermal",
                                "VISIT_FAN_THERMAL",
                                "VISIT_SUPPORT",
                                "VISIT_REQUIRED",
                                "THERMAL_SHUTDOWN_SIGNAL",
                                "Fan thermal signal",
                                "{\"keywords\":[\"thermal shutdown\",\"fan rpm 0\",\"thermal throttle\"],\"remoteActions\":[],\"visitReasons\":[\"THERMAL_SERVICE_REQUIRED\"]}",
                                0.94
                        ),
                        evidence(
                                "00000000-0000-4000-8000-000000058241",
                                "as-rag-diagnosis-unclear",
                                null,
                                "DIAGNOSIS_ONLY",
                                "NEEDS_MORE_INFO",
                                "INSUFFICIENT_EVIDENCE",
                                "More information required",
                                "{\"keywords\":[\"unknown\"]}",
                                0.60
                        )
                ));

        Map<String, Object> result = service.analyze(
                "weak_thermal_throttle_once.jsonl",
                "thermal throttle observed once without shutdown",
                30
        );

        assertThat(result)
                .containsEntry("recommendedService", "DIAGNOSIS_ONLY")
                .containsEntry("supportDecision", "NEEDS_MORE_INFO");
    }

    @Test
    void startupServiceLogRecommendsRemoteSupport() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(evidence(
                        "00000000-0000-4000-8000-000000058250",
                        "as-rag-remote-startup-service",
                        "REMOTE_STARTUP_SERVICE",
                        "REMOTE_SUPPORT",
                        "REMOTE_POSSIBLE",
                        "BACKGROUND_SERVICE_PRESSURE",
                        "Startup service pressure",
                        "{\"keywords\":[\"startup app\",\"service crash loop\",\"idle high cpu\"],\"remoteActions\":[\"CHECK_STARTUP_APPS\"],\"visitReasons\":[]}",
                        0.86
                )));

        Map<String, Object> result = service.analyze(
                "agent-log.jsonl",
                "startup app spike service crash loop idle high cpu background service pressure",
                30
        );

        assertThat(result)
                .containsEntry("recommendedService", "REMOTE_SUPPORT")
                .containsEntry("supportDecision", "REMOTE_POSSIBLE");
        assertThat(AsLogRagAnalysisService.supportRouting(result).get("remoteActions"))
                .asList()
                .contains("CHECK_STARTUP_APPS");
    }

    @Test
    void agentRegistrationFailurePrefersAgentRemoteEvidence() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(
                        evidence(
                                "00000000-0000-4000-8000-000000058260",
                                "as-rag-remote-agent",
                                "REMOTE_AGENT",
                                "REMOTE_SUPPORT",
                                "REMOTE_POSSIBLE",
                                "AGENT_INSTALL_OR_UPLOAD_FAILURE",
                                "Agent registration failure",
                                "{\"keywords\":[\"agent registration failed\",\"upload error\",\"config parse failed\",\"permission error\"],\"remoteActions\":[\"CHECK_AGENT_CONFIG\"],\"visitReasons\":[]}",
                                0.90
                        ),
                        evidence(
                                "00000000-0000-4000-8000-000000058261",
                                "as-rag-remote-app-launcher",
                                "REMOTE_APP_LAUNCHER",
                                "REMOTE_SUPPORT",
                                "REMOTE_POSSIBLE",
                                "APP_CRASH",
                                "App permission issue",
                                "{\"keywords\":[\"permission denied\"],\"remoteActions\":[\"APP_REPAIR\"],\"visitReasons\":[]}",
                                0.87
                        )
                ));

        Map<String, Object> result = service.analyze(
                "agent-log.jsonl",
                "agent registration failed upload error permission error config parse failed",
                30
        );

        assertThat(result)
                .containsEntry("recommendedService", "REMOTE_SUPPORT")
                .containsEntry("supportDecision", "REMOTE_POSSIBLE");
        assertThat((List<?>) result.get("evidence"))
                .extracting(item -> ((Map<?, ?>) item).get("sourceId"))
                .first()
                .isEqualTo("as-rag-remote-agent");
    }

    @Test
    void unclearLogFallsBackToDiagnosisOnly() {
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence")))
                .thenReturn(List.of(evidence(
                        "00000000-0000-4000-8000-000000058301",
                        "as-rag-diagnosis-unclear",
                        null,
                        "DIAGNOSIS_ONLY",
                        "NEEDS_MORE_INFO",
                        "INSUFFICIENT_EVIDENCE",
                        "추가 정보 필요",
                        "{\"keywords\":[\"unknown\"]}",
                        0.60
                )));

        Map<String, Object> result = service.analyze("agent-log.jsonl", "ordinary metric row", 30);

        assertThat(result)
                .containsEntry("recommendedService", "DIAGNOSIS_ONLY")
                .containsEntry("supportDecision", "NEEDS_MORE_INFO");
        assertThat(AsLogRagAnalysisService.causeCandidates(result)).isNotEmpty();
    }

    private static Map<String, Object> evidence(
            String id,
            String sourceId,
            String symptomType,
            String service,
            String decision,
            String reasonCode,
            String summary,
            String metadata,
            double score
    ) {
        return MockData.map(
                "id", id,
                "source_id", sourceId,
                "symptom_type", symptomType,
                "source_type", "TROUBLESHOOTING",
                "recommended_service", service,
                "support_decision", decision,
                "reason_code", reasonCode,
                "title", summary,
                "summary", summary,
                "score", score,
                "metadata", metadata
        );
    }
}
