package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PcAgentLogAnalyzerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-02T10:00:00Z"), ZoneOffset.UTC);
    private static final AgentPrincipal AGENT = new AgentPrincipal(10L, "device-public-id", 20L, "ACTIVE");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "REMOTE_AGENT",
            "REMOTE_DRIVER_OS",
            "REMOTE_APP_LAUNCHER",
            "REMOTE_STORAGE_MEMORY",
            "REMOTE_STARTUP_SERVICE",
            "REMOTE_LOCAL_NETWORK"
    })
    void remoteSixFixturesRouteToRemotePossible(String symptomType) {
        PcAgentLogAnalyzer.AnalysisResult result = analyze(symptomType, "remote symptom", remoteLogFor(symptomType));

        assertThat(result.supportRouting().get("recommendedDecision")).isEqualTo("REMOTE_POSSIBLE");
    }

    @ParameterizedTest
    @CsvSource({
            "VISIT_BOOT_REMOTE_BLOCKED,VISIT_REQUIRED",
            "VISIT_DISK_FAILURE,VISIT_REQUIRED",
            "VISIT_WHEA_BSOD,VISIT_REQUIRED",
            "VISIT_POWER_SHUTDOWN,VISIT_REQUIRED",
            "VISIT_FAN_THERMAL,VISIT_REQUIRED"
    })
    void visitFiveFixturesRouteToVisitRequired(String symptomType, String expectedDecision) {
        PcAgentLogAnalyzer.AnalysisResult result = analyze(symptomType, "visit symptom", visitLogFor(symptomType));

        assertThat(result.supportRouting().get("recommendedDecision")).isEqualTo(expectedDecision);
        assertThat(result.supportRouting().get("supportDecision")).isEqualTo(expectedDecision);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GAME_FPS_TUNING",
            "OVERCLOCK_STABILITY",
            "ISP_ROUTER",
            "PERIPHERAL_PRINTER",
            "DATA_RECOVERY",
            "ILLEGAL_SOFTWARE",
            "PHYSICAL_DAMAGE"
    })
    void unsupportedFixturesRouteToNeedsMoreInfoWithBlockingFactors(String symptomType) {
        PcAgentLogAnalyzer.AnalysisResult result = analyze(symptomType, "unsupported symptom", rawLog(1, "2026-07-02T09:55:00Z", "USER_EVENT", "unsupported issue"));

        assertThat(result.supportRouting().get("recommendedDecision")).isEqualTo("NEEDS_MORE_INFO");
        assertThat(result.supportRouting().get("supportDecision")).isEqualTo("NEEDS_MORE_INFO");
        assertThat(result.supportRouting().get("blockingFactors").toString())
                .containsAnyOf("UNSUPPORTED_SCOPE", "OUT_OF_PC_SCOPE", "DATA_RECOVERY_REQUIRED", "UNSUPPORTED_SOFTWARE", "PHYSICAL_DAMAGE_POLICY_REQUIRED");
    }

    @Test
    void storageMemoryRoutesToVisitWithSafetyAdviceWhenDiskFailureSignalExists() {
        PcAgentLogAnalyzer.AnalysisResult result = analyze(
                "REMOTE_STORAGE_MEMORY",
                "slow storage",
                rawLog(1, "2026-07-02T09:50:00Z", "WINDOWS_EVENT", "SMART critical predictive failure")
        );

        assertThat(result.supportRouting().get("recommendedDecision")).isEqualTo("VISIT_REQUIRED");
        assertThat(result.supportRouting().get("visitReasons").toString()).contains("STORAGE_REPLACEMENT_SUSPECTED");
        assertThat(result.supportRouting().get("safetyAdviceLevel")).isEqualTo("STOP_USE_UNTIL_REVIEW");
        assertThat(result.supportRouting().get("safetyNotices").toString()).contains("DATA_LOSS_RISK");
    }

    @Test
    void incidentWindowFiltersOutLogsOutsideWindow() {
        PcAgentLogAnalyzer.IncidentWindow window = PcAgentLogAnalyzer.resolveIncidentWindow(Map.of(
                "symptomType", "REMOTE_DRIVER_OS",
                "detectedAt", "2026-07-02T10:00:00Z"
        ), CLOCK);
        PcAgentLogAnalyzer.RawLogBundle bundle = PcAgentLogAnalyzer.validateJsonl(
                rawLog(1, "2026-07-02T09:20:00Z", "WINDOWS_EVENT", "SMART critical outside window")
                        + rawLog(2, "2026-07-02T09:50:00Z", "WINDOWS_EVENT", "display driver reset")
                        + rawLog(3, "2026-07-02T09:55:00Z", "WINDOWS_EVENT", "display driver reset"),
                window
        );
        PcAgentLogAnalyzer.AnalysisResult result = PcAgentLogAnalyzer.analyze(AGENT, "driver symptom", window, bundle);
        Map<?, ?> dataQuality = (Map<?, ?>) result.logSummary().get("dataQuality");

        assertThat(bundle.windowRecords()).hasSize(2);
        assertThat(dataQuality.get("filteredOutOfWindow")).isEqualTo(1);
        assertThat(result.supportRouting().get("recommendedDecision")).isEqualTo("REMOTE_POSSIBLE");
        assertThat(result.supportRouting().get("reasonCodes").toString()).contains("DRIVER_CRASH_LOG");
        assertThat(result.supportRouting().get("reasonCodes").toString()).doesNotContain("SMART_CRITICAL");
    }

    @Test
    void rawSamplesAreLimitedToTwentyAndSelectedOnlyFromEvidenceRefs() {
        String logs = IntStream.rangeClosed(1, 30)
                .mapToObj(index -> rawLog(index, "2026-07-02T09:50:%02dZ".formatted(index % 60), "WINDOWS_EVENT", "display driver reset " + index))
                .collect(Collectors.joining());
        PcAgentLogAnalyzer.AnalysisResult result = analyze("REMOTE_DRIVER_OS", "driver symptom", logs);
        Map<?, ?> summary = result.logSummary();
        List<?> evidenceRefs = (List<?>) summary.get("evidenceRefs");
        List<?> rawSamples = (List<?>) summary.get("rawSamples");
        Set<String> evidenceRefIds = evidenceRefs.stream()
                .map(ref -> String.valueOf(((Map<?, ?>) ref).get("refId")))
                .collect(Collectors.toSet());

        assertThat(rawSamples).hasSize(20);
        assertThat(rawSamples)
                .allSatisfy(sample -> assertThat(evidenceRefIds).contains(String.valueOf(((Map<?, ?>) sample).get("refId"))));
    }

    @Test
    void aiDiagnosisRequestDoesNotContainWholeRawLogOrUnmaskedSensitiveText() throws Exception {
        String logs = IntStream.rangeClosed(1, 25)
                .mapToObj(index -> rawLog(index, "2026-07-02T09:50:%02dZ".formatted(index % 60), "WINDOWS_EVENT",
                        "display driver reset full-raw-secret-" + index + " C:\\Users\\kim\\secret.log token=abc" + index))
                .collect(Collectors.joining());
        PcAgentLogAnalyzer.AnalysisResult result = analyze("REMOTE_DRIVER_OS", "driver symptom", logs);
        Map<String, Object> logSummary = PcAgentLogAnalyzer.withTicketId(result.logSummary(), "ticket-public-id");
        @SuppressWarnings("unchecked")
        Map<String, Object> userSymptom = (Map<String, Object>) logSummary.get("userSymptom");
        String aiRequestJson = OBJECT_MAPPER.writeValueAsString(PcAgentLogAnalyzer.aiDiagnosisRequest(
                "ticket-public-id",
                userSymptom,
                logSummary,
                result.supportRouting()
        ));

        assertThat(aiRequestJson).doesNotContain("full-raw-secret-25");
        assertThat(aiRequestJson).doesNotContain("C:\\Users\\kim");
        assertThat(aiRequestJson).doesNotContain("token=abc");
        assertThat(aiRequestJson).contains("[PATH]");
        assertThat(aiRequestJson).contains("token=[REDACTED]");
    }

    @Test
    void aiDiagnosisRequestDoesNotContainWholeProcessLists() throws Exception {
        String logs = rawLogWithProcesses(1, "2026-07-02T09:50:00Z", IntStream.rangeClosed(1, 30)
                .mapToObj(index -> "proc-" + index)
                .toList())
                + rawLog(2, "2026-07-02T09:55:00Z", "WINDOWS_EVENT", "display driver reset");
        PcAgentLogAnalyzer.AnalysisResult result = analyze("REMOTE_DRIVER_OS", "driver symptom", logs);
        Map<String, Object> logSummary = PcAgentLogAnalyzer.withTicketId(result.logSummary(), "ticket-public-id");
        @SuppressWarnings("unchecked")
        Map<String, Object> userSymptom = (Map<String, Object>) logSummary.get("userSymptom");
        String aiRequestJson = OBJECT_MAPPER.writeValueAsString(PcAgentLogAnalyzer.aiDiagnosisRequest(
                "ticket-public-id",
                userSymptom,
                logSummary,
                result.supportRouting()
        ));

        assertThat(aiRequestJson).doesNotContain("proc-25");
        assertThat(aiRequestJson).contains("originalCount", "truncated");
    }

    @Test
    void rawPathWithoutMaskingRejectsWholeUpload() {
        assertThatThrownBy(() -> PcAgentLogAnalyzer.validateJsonl("""
                {"schemaVersion":"1","collectedAt":"2026-07-02T09:50:00Z","agentId":"agent-public-id","sequence":1,"kind":"WINDOWS_EVENT","payload":{"message":"C:\\Users\\kim\\secret.log"},"privacyFlags":{"masked":false,"containsRawPath":true}}
                """, window("REMOTE_DRIVER_OS")))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("FILE_VALIDATION_ERROR"));
    }

    @Test
    void invalidJsonlLineRejectsWholeUpload() {
        assertThatThrownBy(() -> PcAgentLogAnalyzer.validateJsonl(rawLog(1, "2026-07-02T09:50:00Z", "WINDOWS_EVENT", "ok") + "{bad-json}\n", window("REMOTE_DRIVER_OS")))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("FILE_VALIDATION_ERROR"));
    }

    private static PcAgentLogAnalyzer.AnalysisResult analyze(String symptomType, String symptom, String logs) {
        PcAgentLogAnalyzer.IncidentWindow window = window(symptomType);
        PcAgentLogAnalyzer.RawLogBundle bundle = PcAgentLogAnalyzer.validateJsonl(logs, window);
        return PcAgentLogAnalyzer.analyze(AGENT, symptom, window, bundle);
    }

    private static PcAgentLogAnalyzer.IncidentWindow window(String symptomType) {
        return PcAgentLogAnalyzer.resolveIncidentWindow(Map.of(
                "symptomType", symptomType,
                "detectedAt", "2026-07-02T10:00:00Z"
        ), CLOCK);
    }

    private static String remoteLogFor(String symptomType) {
        return switch (symptomType) {
            case "REMOTE_AGENT" -> rawLog(1, "2026-07-02T09:50:00Z", "AGENT_EVENT", "agent upload failed auth 401");
            case "REMOTE_DRIVER_OS" -> rawLog(1, "2026-07-02T09:50:00Z", "WINDOWS_EVENT", "display driver reset")
                    + rawLog(2, "2026-07-02T09:55:00Z", "WINDOWS_EVENT", "display driver reset");
            case "REMOTE_APP_LAUNCHER" -> rawLog(1, "2026-07-02T09:50:00Z", "APP_EVENT", "app crash launcher")
                    + rawLog(2, "2026-07-02T09:55:00Z", "APP_EVENT", "app crash launcher");
            case "REMOTE_STORAGE_MEMORY" -> rawLog(1, "2026-07-02T09:50:00Z", "SYSTEM_METRIC", "memory pressure high");
            case "REMOTE_STARTUP_SERVICE" -> rawLog(1, "2026-07-02T09:50:00Z", "SERVICE_EVENT", "service restart count high");
            case "REMOTE_LOCAL_NETWORK" -> rawLog(1, "2026-07-02T09:50:00Z", "NETWORK_EVENT", "dns failure");
            default -> throw new IllegalArgumentException(symptomType);
        };
    }

    private static String visitLogFor(String symptomType) {
        return switch (symptomType) {
            case "VISIT_BOOT_REMOTE_BLOCKED" -> rawLog(1, "2026-07-02T09:50:00Z", "AGENT_EVENT", "heartbeat missing boot failure");
            case "VISIT_DISK_FAILURE" -> rawLog(1, "2026-07-02T09:50:00Z", "WINDOWS_EVENT", "SMART critical predictive failure");
            case "VISIT_WHEA_BSOD" -> rawLog(1, "2026-07-02T09:50:00Z", "WINDOWS_EVENT", "WHEA hardware error")
                    + rawLog(2, "2026-07-02T09:55:00Z", "WINDOWS_EVENT", "WHEA hardware error");
            case "VISIT_POWER_SHUTDOWN" -> rawLog(1, "2026-07-02T09:50:00Z", "WINDOWS_EVENT", "Kernel-Power event id 41")
                    + rawLog(2, "2026-07-02T09:55:00Z", "WINDOWS_EVENT", "unexpected shutdown");
            case "VISIT_FAN_THERMAL" -> rawLog(1, "2026-07-02T09:50:00Z", "THERMAL_EVENT", "thermal shutdown fan rpm 0");
            default -> throw new IllegalArgumentException(symptomType);
        };
    }

    private static String rawLog(long sequence, String collectedAt, String kind, String message) {
        return """
                {"schemaVersion":"1","collectedAt":"%s","agentId":"agent-public-id","sequence":%d,"kind":"%s","payload":{"message":"%s"},"privacyFlags":{"masked":true,"containsRawPath":false}}
                """.formatted(collectedAt, sequence, kind, message.replace("\\", "\\\\").replace("\"", "\\\""));
    }

    private static String rawLogWithProcesses(long sequence, String collectedAt, List<String> processes) {
        String processJson = processes.stream()
                .map(process -> "\"" + process + "\"")
                .collect(Collectors.joining(","));
        return """
                {"schemaVersion":"1","collectedAt":"%s","agentId":"agent-public-id","sequence":%d,"kind":"WINDOWS_EVENT","payload":{"message":"display driver reset","processes":[%s]},"privacyFlags":{"masked":true,"containsRawPath":false}}
                """.formatted(collectedAt, sequence, processJson);
    }
}
