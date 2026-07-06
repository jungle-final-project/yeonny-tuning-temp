package com.buildgraph.prototype.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AsLogRagEvaluationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<EvalCase>> CASE_LIST = new TypeReference<>() {
    };

    @Test
    void fixedCaseSetMeetsProductionLikeThresholds() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence"))).thenReturn(evidenceRows());
        AsLogRagAnalysisService service = new AsLogRagAnalysisService(jdbcTemplate);
        EvalCounters counters = new EvalCounters();

        for (EvalCase evalCase : readCases()) {
            long started = System.nanoTime();
            Map<String, Object> analysis = service.analyze(evalCase.id() + ".jsonl", jsonl(evalCase.messages()), 30);
            long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            counters.record(evalCase, analysis, latencyMs);
        }

        EvaluationResult result = counters.result();
        System.out.printf(
                "AS_RAG_EVAL cases=%d serviceAccuracy=%.3f decisionAccuracy=%.3f visitPrecision=%.3f visitRecall=%.3f unsupportedEscapeRate=%.3f weakVisitFalsePositives=%d p50Ms=%d p95Ms=%d%n",
                result.caseCount(),
                result.serviceAccuracy(),
                result.decisionAccuracy(),
                result.visitPrecision(),
                result.visitRecall(),
                result.unsupportedEscapeRate(),
                result.weakVisitFalsePositiveCount(),
                result.p50LatencyMs(),
                result.p95LatencyMs()
        );
        if (!result.failedCaseIds().isEmpty()) {
            System.out.println("AS_RAG_EVAL_FAILED " + String.join(",", result.failedCaseIds()));
        }

        assertThat(result.caseCount()).isGreaterThanOrEqualTo(100);
        assertThat(result.serviceAccuracy()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.decisionAccuracy()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.visitPrecision()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.visitRecall()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.unsupportedEscapeRate()).isLessThanOrEqualTo(0.05);
        assertThat(result.weakVisitFalsePositiveCount()).isZero();
        assertThat(result.p95LatencyMs()).isLessThan(500L);
    }

    private static List<EvalCase> readCases() throws Exception {
        Path path = Path.of("src/test/resources/as-rag-eval-cases.json");
        return OBJECT_MAPPER.readValue(Files.readString(path), CASE_LIST);
    }

    private static String jsonl(List<String> messages) throws Exception {
        StringBuilder builder = new StringBuilder();
        long sequence = 1L;
        for (String message : messages) {
            builder.append(OBJECT_MAPPER.writeValueAsString(MockData.map(
                    "schemaVersion", "1",
                    "collectedAt", "2026-07-02T09:50:00Z",
                    "agentId", "agent-public-id",
                    "sequence", sequence++,
                    "kind", "WINDOWS_EVENT",
                    "payload", MockData.map("message", message),
                    "privacyFlags", MockData.map("masked", true, "containsRawPath", false)
            ))).append('\n');
        }
        return builder.toString();
    }

    static List<Map<String, Object>> evidenceRows() {
        return List.of(
                evidence(
                        "00000000-0000-4000-8000-000000061001",
                        "as-rag-remote-driver-os",
                        "REMOTE_DRIVER_OS",
                        "REMOTE_SUPPORT",
                        "REMOTE_POSSIBLE",
                        "DRIVER_ERROR_REPEAT",
                        "Driver and OS errors are remote-support first cases",
                        "{\"keywords\":[\"display driver reset\",\"display driver stopped responding\",\"display driver\",\"gpu driver\",\"graphics driver\",\"nvlddmkm\",\"device manager\",\"device not migrated\",\"windows update failure\",\"windows update failed\",\"windows update error\",\"driver install failure\",\"driver failed to load\",\"driver rollback\",\"rollback\",\"wudfrd failed to load\",\"드라이버 오류\"],\"remoteActions\":[\"DRIVER_ROLLBACK\",\"WINDOWS_UPDATE_CHECK\",\"DEVICE_RESET\"],\"visitReasons\":[]}",
                        0.92
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061002",
                        "as-rag-remote-agent",
                        "REMOTE_AGENT",
                        "REMOTE_SUPPORT",
                        "REMOTE_POSSIBLE",
                        "AGENT_INSTALL_OR_UPLOAD_FAILURE",
                        "Agent install or upload failure",
                        "{\"keywords\":[\"agent registration failed\",\"registration failed\",\"agent register failed\",\"upload error\",\"upload failed\",\"last upload error\",\"agent upload failure\",\"auth 401\",\"unauthorized\",\"409 conflict\",\"idempotency key\",\"permission error\",\"agent permission denied\",\"config parse failed\",\"config schema\",\"service status\",\"tray status\",\"token status\",\"agent install\",\"agent restart\",\"agent config\",\"heartbeat problem\"],\"remoteActions\":[\"CHECK_AGENT_CONFIG\"],\"visitReasons\":[]}",
                        0.90
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061003",
                        "as-rag-remote-app-launcher",
                        "REMOTE_APP_LAUNCHER",
                        "REMOTE_SUPPORT",
                        "REMOTE_POSSIBLE",
                        "APP_CRASH",
                        "Application or launcher failure",
                        "{\"keywords\":[\"app crash\",\"application crash\",\"faulting application\",\"application error\",\"launcher crash\",\"launcher\",\"runtime missing\",\".net runtime\",\"sidebyside\",\"side-by-side\",\"vcruntime\",\"msvcp\",\"0xc000007b\",\"installer error\",\"permission denied\",\"cache corruption\"],\"remoteActions\":[\"APP_REPAIR\",\"RUNTIME_INSTALL\",\"CACHE_CLEANUP\"],\"visitReasons\":[]}",
                        0.87
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061004",
                        "as-rag-remote-startup-service",
                        "REMOTE_STARTUP_SERVICE",
                        "REMOTE_SUPPORT",
                        "REMOTE_POSSIBLE",
                        "BACKGROUND_SERVICE_PRESSURE",
                        "Startup or background service load",
                        "{\"keywords\":[\"startup app\",\"startup impact\",\"startup spike\",\"background service\",\"service crash loop\",\"service restart\",\"service restart count\",\"service terminated unexpectedly\",\"service control manager\",\"idle high cpu\",\"background task\",\"background service pressure\",\"시작프로그램\",\"백그라운드 서비스\"],\"remoteActions\":[\"CHECK_STARTUP_APPS\"],\"visitReasons\":[]}",
                        0.86
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061005",
                        "as-rag-remote-storage-memory",
                        "REMOTE_STORAGE_MEMORY",
                        "REMOTE_SUPPORT",
                        "REMOTE_POSSIBLE",
                        "STORAGE_MEMORY_PRESSURE",
                        "Storage or memory pressure",
                        "{\"keywords\":[\"free space low\",\"storage low\",\"low disk space\",\"memory pressure\",\"virtual memory\",\"low virtual memory\",\"pagefile\",\"pagefile usage\",\"disk active time\",\"disk queue length\",\"commit charge\",\"commit limit\",\"resource exhaustion\"],\"remoteActions\":[\"TEMP_FILE_CLEANUP\",\"STARTUP_APP_REVIEW\",\"PAGEFILE_CHECK\"],\"visitReasons\":[]}",
                        0.82
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061006",
                        "as-rag-remote-local-network",
                        "REMOTE_LOCAL_NETWORK",
                        "REMOTE_SUPPORT",
                        "REMOTE_POSSIBLE",
                        "DNS_OR_ADAPTER_FAILURE",
                        "Local network issue",
                        "{\"keywords\":[\"dns failure\",\"dns timeout\",\"name resolution failed\",\"name resolution timed out\",\"dns client events\",\"gateway unreachable\",\"default gateway not available\",\"adapter disabled\",\"network adapter disabled\",\"nic driver\",\"ip configuration error\",\"dhcp failure\"],\"remoteActions\":[\"DNS_RESET\",\"ADAPTER_RESET\",\"NIC_DRIVER_CHECK\"],\"visitReasons\":[]}",
                        0.80
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061007",
                        "as-rag-visit-disk-failure",
                        "VISIT_DISK_FAILURE",
                        "VISIT_SUPPORT",
                        "VISIT_REQUIRED",
                        "DISK_FAILURE_SIGNAL",
                        "Disk failure signal",
                        "{\"keywords\":[\"smart critical\",\"predictive failure\",\"bad block\",\"has a bad block\",\"disk has a bad block\",\"disk i/o\",\"i/o error\",\"io error\",\"ntfs error\",\"filesystem error\",\"drive disappeared\",\"device disappeared\",\"storage device instability\",\"디스크 오류\",\"배드블록\",\"smart\",\"저장장치 장애\"],\"remoteActions\":[],\"visitReasons\":[\"STORAGE_REPLACEMENT_SUSPECTED\"]}",
                        0.96
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061008",
                        "as-rag-visit-power-thermal",
                        "VISIT_POWER_SHUTDOWN",
                        "VISIT_SUPPORT",
                        "VISIT_REQUIRED",
                        "POWER_OR_THERMAL_SHUTDOWN",
                        "Power shutdown signal",
                        "{\"keywords\":[\"kernel-power\",\"kernel power\",\"event id 41\",\"event 41\",\"unexpected shutdown\",\"previous system shutdown was unexpected\",\"shutdown was unexpected\",\"sudden shutdown\",\"power loss\",\"power loss under load\",\"psu\",\"전원 꺼짐\"],\"remoteActions\":[],\"visitReasons\":[\"PSU_OR_POWER_PATH_RISK\"]}",
                        0.95
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061009",
                        "as-rag-visit-whea-bsod",
                        "VISIT_WHEA_BSOD",
                        "VISIT_SUPPORT",
                        "VISIT_REQUIRED",
                        "HARDWARE_CRASH_REPEAT",
                        "WHEA or BSOD signal",
                        "{\"keywords\":[\"whea\",\"whea logger\",\"whea-logger\",\"whea uncorrectable\",\"bugcheck\",\"bugcheck 0x\",\"bsod\",\"blue screen\",\"stop code\",\"hardware error\",\"hardware error event\",\"memory hardware\",\"블루스크린\",\"하드웨어 오류\",\"메모리 오류\"],\"remoteActions\":[],\"visitReasons\":[\"WHEA_ERROR_REPEAT\",\"BSOD_REPEAT\"]}",
                        0.94
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061010",
                        "as-rag-visit-fan-thermal",
                        "VISIT_FAN_THERMAL",
                        "VISIT_SUPPORT",
                        "VISIT_REQUIRED",
                        "THERMAL_SHUTDOWN_SIGNAL",
                        "Fan or thermal shutdown signal",
                        "{\"keywords\":[\"thermal shutdown\",\"fan rpm 0\",\"fan not spinning\",\"cooling fan stopped\",\"thermal throttle\",\"thermal throttling\",\"overheat\",\"overheating\",\"fan failure\",\"팬 미동작\",\"과열\"],\"remoteActions\":[],\"visitReasons\":[\"THERMAL_SERVICE_REQUIRED\"]}",
                        0.94
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061011",
                        "as-rag-visit-boot-remote-blocked",
                        "VISIT_BOOT_REMOTE_BLOCKED",
                        "VISIT_SUPPORT",
                        "VISIT_REQUIRED",
                        "BOOT_OR_REMOTE_BLOCKED",
                        "Boot or remote blocked",
                        "{\"keywords\":[\"boot failure\",\"cannot boot\",\"no boot\",\"os boot failure\",\"device offline\",\"heartbeat missing\",\"heartbeat gap\",\"remote unavailable\",\"remote connection unavailable\",\"quick assist unavailable\",\"quick assist failed\",\"부팅 불가\",\"원격 연결 불가\"],\"remoteActions\":[],\"visitReasons\":[\"DEVICE_OFFLINE\",\"REMOTE_HELP_NOT_AVAILABLE\"]}",
                        0.93
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061012",
                        "as-rag-diagnosis-unclear",
                        null,
                        "DIAGNOSIS_ONLY",
                        "NEEDS_MORE_INFO",
                        "INSUFFICIENT_EVIDENCE",
                        "Unclear log signal",
                        "{\"keywords\":[\"unknown\",\"unclear\",\"no signal\",\"insufficient\",\"정보 부족\",\"불명확\",\"추가 정보\",\"진단\"],\"remoteActions\":[],\"visitReasons\":[]}",
                        0.60
                ),
                evidence(
                        "00000000-0000-4000-8000-000000061013",
                        "as-rag-unsupported-scope",
                        null,
                        "DIAGNOSIS_ONLY",
                        "NEEDS_MORE_INFO",
                        "UNSUPPORTED_SCOPE",
                        "Unsupported scope",
                        "{\"keywords\":[\"fps tuning\",\"fps drops\",\"game fps\",\"valorant fps\",\"game optimization\",\"overclock\",\"overclock stability\",\"isp\",\"router\",\"printer\",\"peripheral\",\"data recovery\",\"illegal software\",\"physical damage\",\"broken screen\",\"water damage\",\"오버클럭\",\"공유기\",\"프린터\",\"주변기기\",\"데이터 복구\",\"물리 파손\"],\"remoteActions\":[],\"visitReasons\":[],\"blockingFactors\":[\"UNSUPPORTED_SCOPE\"]}",
                        0.58
                )
        );
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

    private record EvalCase(
            String id,
            String group,
            String expectedService,
            String expectedDecision,
            List<String> messages,
            List<String> tags
    ) {
        boolean hasTag(String tag) {
            return tags != null && tags.contains(tag);
        }
    }

    private static final class EvalCounters {
        private int caseCount;
        private int servicePass;
        private int decisionPass;
        private int visitExpected;
        private int visitActual;
        private int visitTruePositive;
        private int unsupportedCases;
        private int unsupportedEscapes;
        private int weakVisitFalsePositives;
        private final List<Long> latencies = new ArrayList<>();
        private final List<String> failedCaseIds = new ArrayList<>();

        void record(EvalCase evalCase, Map<String, Object> analysis, long latencyMs) {
            caseCount++;
            latencies.add(latencyMs);
            String actualService = AsLogRagAnalysisService.recommendedService(analysis);
            String actualDecision = AsLogRagAnalysisService.supportDecision(analysis);

            if (evalCase.expectedService().equals(actualService)) {
                servicePass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":service:" + actualService);
            }
            if (evalCase.expectedDecision().equals(actualDecision)) {
                decisionPass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":decision:" + actualDecision);
            }

            boolean expectedVisit = "VISIT_SUPPORT".equals(evalCase.expectedService());
            boolean actualVisit = "VISIT_SUPPORT".equals(actualService);
            if (expectedVisit) {
                visitExpected++;
            }
            if (actualVisit) {
                visitActual++;
            }
            if (expectedVisit && actualVisit) {
                visitTruePositive++;
            }
            if (evalCase.hasTag("UNSUPPORTED")) {
                unsupportedCases++;
                if (!"DIAGNOSIS_ONLY".equals(actualService)) {
                    unsupportedEscapes++;
                }
            }
            if (evalCase.hasTag("WEAK") && actualVisit) {
                weakVisitFalsePositives++;
            }
        }

        EvaluationResult result() {
            return new EvaluationResult(
                    caseCount,
                    rate(servicePass, caseCount),
                    rate(decisionPass, caseCount),
                    rate(visitTruePositive, visitActual),
                    rate(visitTruePositive, visitExpected),
                    rate(unsupportedEscapes, unsupportedCases),
                    weakVisitFalsePositives,
                    percentile(0.50),
                    percentile(0.95),
                    List.copyOf(failedCaseIds)
            );
        }

        private long percentile(double percentile) {
            if (latencies.isEmpty()) {
                return 0L;
            }
            List<Long> sorted = latencies.stream().sorted(Comparator.naturalOrder()).toList();
            int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1);
            return sorted.get(Math.max(0, index));
        }

        private static double rate(int numerator, int denominator) {
            return denominator == 0 ? 1.0 : (double) numerator / (double) denominator;
        }
    }

    private record EvaluationResult(
            int caseCount,
            double serviceAccuracy,
            double decisionAccuracy,
            double visitPrecision,
            double visitRecall,
            double unsupportedEscapeRate,
            int weakVisitFalsePositiveCount,
            long p50LatencyMs,
            long p95LatencyMs,
            List<String> failedCaseIds
    ) {
    }
}
