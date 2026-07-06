package com.buildgraph.prototype.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AsLogRagRealisticLogFixtureTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Path FIXTURE_DIR = Path.of("src/test/resources/as-rag-realistic-logs");
    private static final TypeReference<List<RealisticFixture>> FIXTURE_LIST = new TypeReference<>() {
    };

    @Test
    void realisticWindowsAndAgentLogFixturesProduceExpectedSupportRecommendation() throws Exception {
        AsLogRagAnalysisService service = serviceWithExpandedEvidence();
        List<String> failures = new ArrayList<>();
        int eventLogFixtureCount = 0;
        int agentSignalFixtureCount = 0;

        for (RealisticFixture fixture : readManifest()) {
            String logText = Files.readString(FIXTURE_DIR.resolve(fixture.fileName()));
            List<JsonNode> records = parseAndAssertRawLogShape(fixture.fileName(), logText);
            if (records.stream().anyMatch(record -> "EVENT_LOG".equals(record.path("kind").asText()))) {
                eventLogFixtureCount++;
            }
            if (records.stream().anyMatch(record -> record.path("kind").asText().startsWith("AGENT_")
                    || "STORAGE_HEALTH".equals(record.path("kind").asText())
                    || "THERMAL_SENSOR".equals(record.path("kind").asText()))) {
                agentSignalFixtureCount++;
            }

            Map<String, Object> analysis = service.analyze(fixture.fileName(), logText, 30);
            String actualService = AsLogRagAnalysisService.recommendedService(analysis);
            String actualDecision = AsLogRagAnalysisService.supportDecision(analysis);
            if (!fixture.expectedService().equals(actualService)) {
                failures.add(fixture.id() + ":service:" + actualService);
            }
            if (!fixture.expectedDecision().equals(actualDecision)) {
                failures.add(fixture.id() + ":decision:" + actualDecision);
            }
        }

        System.out.printf(
                "AS_RAG_REALISTIC_FIXTURES cases=%d eventLogCases=%d agentSignalCases=%d failures=%d%n",
                readManifest().size(),
                eventLogFixtureCount,
                agentSignalFixtureCount,
                failures.size()
        );
        if (!failures.isEmpty()) {
            System.out.println("AS_RAG_REALISTIC_FIXTURES_FAILED " + String.join(",", failures));
        }

        assertThat(readManifest()).hasSizeGreaterThanOrEqualTo(15);
        assertThat(eventLogFixtureCount).isGreaterThanOrEqualTo(10);
        assertThat(agentSignalFixtureCount).isGreaterThanOrEqualTo(4);
        assertThat(failures).isEmpty();
    }

    @Test
    void largeRealisticSignalCorpusMeetsRecommendationThresholds() throws Exception {
        AsLogRagAnalysisService service = serviceWithExpandedEvidence();
        List<String> failures = new ArrayList<>();
        int servicePass = 0;
        int decisionPass = 0;
        int visitExpected = 0;
        int visitActual = 0;
        int visitTruePositive = 0;
        int unsupportedCases = 0;
        int unsupportedEscapes = 0;
        int weakVisitFalsePositives = 0;
        List<CorpusCase> corpus = realisticCorpus();

        for (CorpusCase corpusCase : corpus) {
            String logText = jsonl(corpusCase.records());
            parseAndAssertRawLogShape(corpusCase.id(), logText);
            Map<String, Object> analysis = service.analyze(corpusCase.id() + ".jsonl", logText, 30);
            String actualService = AsLogRagAnalysisService.recommendedService(analysis);
            String actualDecision = AsLogRagAnalysisService.supportDecision(analysis);

            if (corpusCase.expectedService().equals(actualService)) {
                servicePass++;
            } else {
                failures.add(corpusCase.id() + ":service:" + actualService);
            }
            if (corpusCase.expectedDecision().equals(actualDecision)) {
                decisionPass++;
            } else {
                failures.add(corpusCase.id() + ":decision:" + actualDecision);
            }

            boolean expectedVisit = "VISIT_SUPPORT".equals(corpusCase.expectedService());
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
            if (corpusCase.tags().contains("UNSUPPORTED")) {
                unsupportedCases++;
                if (!"DIAGNOSIS_ONLY".equals(actualService)) {
                    unsupportedEscapes++;
                }
            }
            if (corpusCase.tags().contains("WEAK") && actualVisit) {
                weakVisitFalsePositives++;
            }
        }

        double serviceAccuracy = rate(servicePass, corpus.size());
        double decisionAccuracy = rate(decisionPass, corpus.size());
        double visitPrecision = rate(visitTruePositive, visitActual);
        double visitRecall = rate(visitTruePositive, visitExpected);
        double unsupportedEscapeRate = rate(unsupportedEscapes, unsupportedCases);

        System.out.printf(
                "AS_RAG_REALISTIC_CORPUS cases=%d serviceAccuracy=%.3f decisionAccuracy=%.3f visitPrecision=%.3f visitRecall=%.3f unsupportedEscapeRate=%.3f weakVisitFalsePositives=%d failures=%d%n",
                corpus.size(),
                serviceAccuracy,
                decisionAccuracy,
                visitPrecision,
                visitRecall,
                unsupportedEscapeRate,
                weakVisitFalsePositives,
                failures.size()
        );
        if (!failures.isEmpty()) {
            System.out.println("AS_RAG_REALISTIC_CORPUS_FAILED " + String.join(",", failures));
        }

        assertThat(corpus).hasSizeGreaterThanOrEqualTo(90);
        assertThat(serviceAccuracy).isGreaterThanOrEqualTo(0.95);
        assertThat(decisionAccuracy).isGreaterThanOrEqualTo(0.95);
        assertThat(visitPrecision).isGreaterThanOrEqualTo(0.95);
        assertThat(visitRecall).isGreaterThanOrEqualTo(0.95);
        assertThat(unsupportedEscapeRate).isLessThanOrEqualTo(0.05);
        assertThat(weakVisitFalsePositives).isZero();
        assertThat(failures).isEmpty();
    }

    private static AsLogRagAnalysisService serviceWithExpandedEvidence() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("FROM as_rag_evidence"))).thenReturn(expandedEvidenceRows());
        return new AsLogRagAnalysisService(jdbcTemplate);
    }

    private static List<RealisticFixture> readManifest() throws Exception {
        return OBJECT_MAPPER.readValue(Files.readString(FIXTURE_DIR.resolve("manifest.json")), FIXTURE_LIST);
    }

    private static List<CorpusCase> realisticCorpus() {
        List<CorpusCase> cases = new ArrayList<>();
        addEventCases(cases, "remote_driver", "REMOTE_SUPPORT", "REMOTE_POSSIBLE", "REMOTE", "System", "Display", 4101, "Warning", List.of(
                "Display driver nvlddmkm stopped responding and has successfully recovered.",
                "Display driver amdwddmg stopped responding and has recovered after TDR.",
                "Display driver igdkmdn64 stopped responding and has recovered after graphics workload.",
                "Display driver atikmdag stopped responding; event id 4101 display driver reset.",
                "Graphics driver reset after device manager code 43 warning.",
                "Device Setup Manager reports device not migrated after Windows Update.",
                "WindowsUpdateClient installation failure while installing GPU driver package.",
                "The driver \\Driver\\WudfRd failed to load for the device ROOT\\SYSTEM\\0001.",
                "Device not started after driver package update; driver rollback available.",
                "Windows Update failed with driver install failure and rollback recommended."
        ));
        addEventCases(cases, "remote_app", "REMOTE_SUPPORT", "REMOTE_POSSIBLE", "REMOTE", "Application", "Application Error", 1000, "Error", List.of(
                "Faulting application name: launcher.exe, exception code: 0xc0000005.",
                "Application Error 1000: faulting application name updater.exe, faulting module KERNELBASE.dll.",
                "Application Hang 1002: hung application launcher.exe stopped interacting with Windows.",
                ".NET Runtime 1026: process terminated due to an unhandled exception.",
                "SideBySide activation context generation failed for the launcher.",
                "VCRUNTIME140.dll missing and MSVCP runtime missing for app launch.",
                "Application failed to start correctly 0xc000007b.",
                "MsiInstaller repair failed; Windows Installer reported installer error.",
                "Permission denied while launching app from protected directory.",
                "Fault bucket reported launcher crash after cache corruption."
        ));
        addEventCases(cases, "remote_storage_memory_event", "REMOTE_SUPPORT", "REMOTE_POSSIBLE", "REMOTE", "System", "Microsoft-Windows-Resource-Exhaustion-Detector", 2004, "Warning", List.of(
                "Windows successfully diagnosed a low virtual memory condition.",
                "Resource-Exhaustion-Detector reported available memory low.",
                "Low virtual memory condition; commit charge near commit limit.",
                "Pagefile usage spike observed while memory pressure high."
        ));
        addRecordCases(cases, "remote_storage_memory_metric", "REMOTE_SUPPORT", "REMOTE_POSSIBLE", "REMOTE", "SYSTEM_METRIC", List.of(
                metric("memory pressure high and pagefile usage spike", 38.0, 94.0, 42.0, 5.0),
                metric("free space low on system drive; temporary cleanup recommended", 22.0, 72.0, 64.0, 3.0),
                metric("disk active time high but no i/o error found", 18.0, 66.0, 98.0, 2.0),
                metric("disk queue length high during startup app resource exhaustion", 61.0, 83.0, 91.0, 8.0)
        ));
        addEventCases(cases, "remote_startup_service", "REMOTE_SUPPORT", "REMOTE_POSSIBLE", "REMOTE", "System", "Service Control Manager", 7031, "Error", List.of(
                "The Example Updater service terminated unexpectedly. It has done this 3 time(s).",
                "The background service terminated unexpectedly and service restart count increased.",
                "Event ID 7034: the telemetry service terminated unexpectedly.",
                "Event ID 7000: service failed to start due to a logon failure.",
                "Event ID 7009: timeout was reached while waiting for the service to connect.",
                "Event ID 7011: service did not respond after timeout was reached.",
                "Background service crash loop detected after startup.",
                "Startup app spike and startup impact high after user logon."
        ));
        addEventCases(cases, "remote_network", "REMOTE_SUPPORT", "REMOTE_POSSIBLE", "REMOTE", "System", "Microsoft-Windows-DNS-Client", 1014, "Warning", List.of(
                "Name resolution for the name diag.buildgraph.local timed out after none of the configured DNS servers responded.",
                "DNS Client Events name resolution failed for diagnostic endpoint.",
                "DHCP-Client address lease could not be renewed; dhcp failure observed.",
                "TCPIP duplicate address detected on the local network.",
                "Default gateway not available after resume from sleep.",
                "Network adapter disabled by configuration.",
                "Netwtw NIC driver warning after update.",
                "Limited connectivity and media disconnected on active adapter."
        ));
        addRecordCases(cases, "remote_agent", "REMOTE_SUPPORT", "REMOTE_POSSIBLE", "REMOTE", "AGENT_HEALTH", List.of(
                agentHealth("AUTH_401", "agent upload failed auth 401 unauthorized; last upload error stored"),
                agentHealth("FORBIDDEN", "agent upload failed 403 forbidden; token invalid"),
                agentHealth("CONFLICT", "409 conflict idempotency key mismatch during upload"),
                agentHealth("CONFIG_SCHEMA", "config schema mismatch; config parse failed"),
                agentHealth("PERMISSION", "permission error reading log directory; agent permission denied"),
                agentHealth("TOKEN_EXPIRED", "token expired; token status invalid"),
                agentHealth("SERVICE_STOPPED", "service status stopped; agent restart required"),
                agentHealth("HEARTBEAT_TIMEOUT", "heartbeat timeout; heartbeat problem after update")
        ));

        addRecordCases(cases, "visit_boot_remote_blocked", "VISIT_SUPPORT", "VISIT_REQUIRED", "VISIT", "AGENT_HEALTH", List.of(
                agentHealth("HEARTBEAT_TIMEOUT", "device offline; heartbeat missing; heartbeat gap detected"),
                agentHealth("REMOTE_UNAVAILABLE", "remote unavailable; quick assist unavailable; remote connection unavailable"),
                agentHealth("BOOT_FAILURE", "boot failure; cannot boot into Windows"),
                agentHealth("NO_BOOT", "no boot; os boot failure"),
                agentHealth("STARTUP_REPAIR", "automatic repair loop; startup repair failed"),
                agentHealth("WINRE", "WinRE boot critical state; remote unavailable"),
                agentHealth("DEVICE_OFFLINE", "device offline and heartbeat missing after power cycle")
        ));
        addEventCases(cases, "visit_disk", "VISIT_SUPPORT", "VISIT_REQUIRED", "VISIT", "System", "Disk", 7, "Error", List.of(
                "The device, \\Device\\Harddisk0\\DR0, has a bad block.",
                "Disk has a bad block and storage device instability repeated.",
                "The IO operation at logical block address was retried.",
                "Reset to device, \\Device\\RaidPort0, was issued by storahci.",
                "The driver detected a controller error on \\Device\\Harddisk1\\DR1.",
                "NTFS error: A corruption was discovered in the file system structure.",
                "Drive disappeared during write; device disappeared from storage bus.",
                "Disk i/o error repeated; i/o error again during metadata write."
        ));
        addRecordCases(cases, "visit_smart", "VISIT_SUPPORT", "VISIT_REQUIRED", "VISIT", "STORAGE_HEALTH", List.of(
                storageHealth("CRITICAL", "smart critical predictive failure reported by disk health monitor"),
                storageHealth("CRITICAL", "predictive failure with io operation at logical block retried"),
                storageHealth("CRITICAL", "smart critical and controller error after write workload")
        ));
        addEventCases(cases, "visit_whea_bsod", "VISIT_SUPPORT", "VISIT_REQUIRED", "VISIT", "System", "Microsoft-Windows-WHEA-Logger", 18, "Error", List.of(
                "A fatal hardware error has occurred. Reported by component: Processor Core.",
                "A corrected hardware error has occurred. Hardware error source: Machine Check Exception.",
                "WHEA-Logger hardware error event repeated after memory pressure.",
                "BugCheck 1001: The computer has rebooted from a bugcheck. The bugcheck was: 0x00000124.",
                "Blue screen stop code WHEA_UNCORRECTABLE_ERROR after hardware error event.",
                "LiveKernelEvent 141 hardware error after GPU reset.",
                "Machine check exception and bugcheck 0x124 occurred.",
                "BSOD repeated after memory hardware error."
        ));
        addEventCases(cases, "visit_power", "VISIT_SUPPORT", "VISIT_REQUIRED", "VISIT", "System", "Microsoft-Windows-Kernel-Power", 41, "Critical", List.of(
                "The system has rebooted without cleanly shutting down first.",
                "Kernel-Power Event ID 41: lost power unexpectedly under load.",
                "Previous system shutdown was unexpected and Event ID 41 repeated.",
                "Unexpected shutdown repeated; BugcheckCode 0; power supply suspected.",
                "Power loss under load while GPU usage high.",
                "Sudden shutdown again during stress workload.",
                "Kernel power critical event; shutdown was unexpected."
        ));
        addRecordCases(cases, "visit_thermal", "VISIT_SUPPORT", "VISIT_REQUIRED", "VISIT", "THERMAL_SENSOR", List.of(
                thermal("fan rpm 0 detected; thermal shutdown risk", 104.0, 92.0, 0, true),
                thermal("fan not spinning and cooling fan stopped", 101.0, 90.0, 0, true),
                thermal("thermal throttling repeated with overheating", 99.0, 87.0, 640, true),
                thermal("processor speed is being limited by system firmware due to thermal event", 96.0, 85.0, 900, true),
                thermal("ACPI thermal zone critical; overheat warning", 105.0, 91.0, 700, true),
                thermal("fan failure and thermal throttle repeated", 100.0, 88.0, 0, true),
                thermal("thermal shutdown detected after fan rpm 0", 108.0, 93.0, 0, true)
        ));

        addRecordCases(cases, "unsupported_scope", "DIAGNOSIS_ONLY", "NEEDS_MORE_INFO", "UNSUPPORTED", "PROCESS_CATEGORY", List.of(
                process("GAME", "valorant fps drops and game fps tuning request only"),
                process("GAME", "frame time spikes; game optimization request"),
                process("SYSTEM_TUNING", "overclock stability tuning request"),
                process("NETWORK_DEVICE", "router configuration and ISP outage suspected"),
                process("PERIPHERAL", "printer driver and peripheral setup request"),
                process("RECOVERY", "data recovery and file recovery request"),
                process("POLICY", "illegal software activation support request"),
                process("DAMAGE", "broken screen and physical damage reported"),
                process("DAMAGE", "water damage and liquid damage reported")
        ));
        addEventCases(cases, "weak_clean", "DIAGNOSIS_ONLY", "NEEDS_MORE_INFO", "WEAK", "System", "EventLog", 6006, "Information", List.of(
                "The Event log service was stopped.",
                "User32 Event ID 1074 planned restart by user.",
                "Windows Update completed successfully.",
                "SMART monitoring normal and no smart critical event.",
                "DNS cache flushed successfully.",
                "Service started successfully without error."
        ));
        return cases;
    }

    private static void addEventCases(
            List<CorpusCase> cases,
            String prefix,
            String expectedService,
            String expectedDecision,
            String tag,
            String logName,
            String source,
            int eventId,
            String level,
            List<String> messages
    ) {
        int index = 1;
        for (String message : messages) {
            String id = "%s_%03d".formatted(prefix, index++);
            cases.add(new CorpusCase(
                    id,
                    expectedService,
                    expectedDecision,
                    List.of(event(logName, source, eventId, level, message)),
                    List.of(tag, "EVENT_LOG")
            ));
        }
    }

    private static void addRecordCases(
            List<CorpusCase> cases,
            String prefix,
            String expectedService,
            String expectedDecision,
            String tag,
            String kind,
            List<Map<String, Object>> payloads
    ) {
        int index = 1;
        for (Map<String, Object> payload : payloads) {
            String id = "%s_%03d".formatted(prefix, index++);
            cases.add(new CorpusCase(
                    id,
                    expectedService,
                    expectedDecision,
                    List.of(new RawRecord(kind, payload)),
                    List.of(tag, kind)
            ));
        }
    }

    private static RawRecord event(String logName, String source, int eventId, String level, String message) {
        return new RawRecord("EVENT_LOG", MockData.map(
                "logName", logName,
                "source", source,
                "eventId", eventId,
                "level", level,
                "messageMasked", message,
                "eventTime", "2026-07-02T09:50:00Z"
        ));
    }

    private static Map<String, Object> metric(String summary, double cpu, double memory, double disk, double gpu) {
        return MockData.map(
                "cpuUsagePercent", cpu,
                "memoryUsedPercent", memory,
                "diskActivePercent", disk,
                "gpuUsagePercent", gpu,
                "sampleIntervalSeconds", 5,
                "summary", summary
        );
    }

    private static Map<String, Object> agentHealth(String errorCode, String summary) {
        return MockData.map(
                "agentVersion", "0.1.0",
                "serviceStatus", "RUNNING",
                "trayStatus", "RUNNING",
                "lastUploadErrorCode", errorCode,
                "configSchemaVersion", "1",
                "summary", summary
        );
    }

    private static Map<String, Object> storageHealth(String status, String summary) {
        return MockData.map(
                "diskIdHash", "disk-hash",
                "smartStatus", status,
                "criticalWarnings", List.of("smart critical", "predictive failure"),
                "ioLatencyMs", 1300,
                "freeSpacePercent", 37,
                "summary", summary
        );
    }

    private static Map<String, Object> thermal(String summary, double cpuTemp, double gpuTemp, int fanRpm, boolean throttle) {
        return MockData.map(
                "cpuTemperatureCelsius", cpuTemp,
                "gpuTemperatureCelsius", gpuTemp,
                "fanRpm", fanRpm,
                "thermalThrottle", throttle,
                "unavailableReason", null,
                "summary", summary
        );
    }

    private static Map<String, Object> process(String category, String summary) {
        return MockData.map(
                "category", category,
                "cpuUsagePercent", 42,
                "memoryUsedMb", 2048,
                "processNameHash", "process-hash",
                "allowlistedName", "masked-process",
                "summary", summary
        );
    }

    private static String jsonl(List<RawRecord> records) throws Exception {
        StringBuilder builder = new StringBuilder();
        long sequence = 1L;
        for (RawRecord record : records) {
            builder.append(OBJECT_MAPPER.writeValueAsString(MockData.map(
                    "schemaVersion", "1",
                    "collectedAt", "2026-07-02T09:50:00Z",
                    "agentId", "agent-public-id",
                    "deviceIdHash", "device-hash",
                    "sequence", sequence++,
                    "kind", record.kind(),
                    "payload", record.payload(),
                    "privacyFlags", MockData.map("masked", true, "containsRawPath", false)
            ))).append('\n');
        }
        return builder.toString();
    }

    private static List<Map<String, Object>> expandedEvidenceRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : AsLogRagEvaluationTest.evidenceRows()) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            String sourceId = String.valueOf(row.get("source_id"));
            switch (sourceId) {
                case "as-rag-remote-driver-os" -> copy.put("metadata", metadataJson(
                        List.of("display driver reset", "display driver stopped responding", "display driver", "gpu driver", "graphics driver", "nvlddmkm", "amdwddmg", "igfx", "igdkmdn64", "atikmdag", "event id 4101", "tdr", "device manager", "device setup manager", "device not migrated", "device not started", "code 43", "windowsupdateclient", "windows update failure", "windows update failed", "windows update error", "installation failure", "driver install failure", "driver failed to load", "driver package", "driver rollback", "rollback", "wudfrd failed to load", "드라이버 오류"),
                        List.of("DRIVER_ROLLBACK", "WINDOWS_UPDATE_CHECK", "DEVICE_RESET"),
                        List.of(),
                        List.of()
                ));
                case "as-rag-remote-app-launcher" -> copy.put("metadata", metadataJson(
                        List.of("app crash", "application crash", "faulting application", "application error", "application hang", "hung application", "fault bucket", "apphang", "launcher crash", "launcher", "runtime missing", ".net runtime", "sidebyside", "side-by-side", "activation context generation failed", "vcruntime", "msvcp", "0xc000007b", "0xc0000005", "msiinstaller", "windows installer", "installer error", "permission denied", "cache corruption"),
                        List.of("APP_REPAIR", "RUNTIME_INSTALL", "CACHE_CLEANUP"),
                        List.of(),
                        List.of()
                ));
                case "as-rag-remote-storage-memory" -> copy.put("metadata", metadataJson(
                        List.of("free space low", "storage low", "low disk space", "memory pressure", "virtual memory", "low virtual memory", "resource-exhaustion-detector", "resource exhaustion detector", "diagnosed a low virtual memory condition", "pagefile", "pagefile usage", "disk active time", "disk queue length", "commit charge", "commit limit", "resource exhaustion", "available memory low"),
                        List.of("TEMP_FILE_CLEANUP", "STARTUP_APP_REVIEW", "PAGEFILE_CHECK"),
                        List.of(),
                        List.of()
                ));
                case "as-rag-remote-local-network" -> copy.put("metadata", metadataJson(
                        List.of("dns failure", "dns timeout", "name resolution failed", "name resolution timed out", "dns client events", "dns-client", "gateway unreachable", "default gateway not available", "adapter disabled", "network adapter disabled", "nic driver", "netwtw", "e1rexpress", "ndis", "dhcp-client", "dhcp failure", "address lease", "ip configuration error", "duplicate address", "media disconnected", "limited connectivity"),
                        List.of("DNS_RESET", "ADAPTER_RESET", "NIC_DRIVER_CHECK"),
                        List.of(),
                        List.of()
                ));
                case "as-rag-remote-agent" -> copy.put("metadata", metadataJson(
                        List.of("agent registration failed", "registration failed", "agent register failed", "upload error", "upload failed", "last upload error", "agent upload failure", "auth 401", "unauthorized", "403 forbidden", "409 conflict", "idempotency key", "permission error", "agent permission denied", "config parse failed", "config schema", "token expired", "token invalid", "service status", "tray status", "token status", "agent install", "agent restart", "agent config", "heartbeat problem", "heartbeat timeout"),
                        List.of("CHECK_AGENT_CONFIG"),
                        List.of(),
                        List.of()
                ));
                case "as-rag-remote-startup-service" -> copy.put("metadata", metadataJson(
                        List.of("startup app", "startup impact", "startup spike", "background service", "service crash loop", "service restart", "service restart count", "service terminated unexpectedly", "service control manager", "service failed to start", "service did not respond", "timeout was reached", "event id 7000", "event id 7009", "event id 7011", "event id 7031", "event id 7034", "idle high cpu", "background task", "background service pressure", "시작프로그램", "백그라운드 서비스"),
                        List.of("CHECK_STARTUP_APPS"),
                        List.of(),
                        List.of()
                ));
                case "as-rag-visit-disk-failure" -> copy.put("metadata", metadataJson(
                        List.of("smart critical", "predictive failure", "bad block", "has a bad block", "disk has a bad block", "disk i/o", "i/o error", "io error", "io operation at logical block", "reset to device", "controller error", "storahci", "iastora", "ntfs error", "file system structure", "filesystem error", "drive disappeared", "device disappeared", "storage device instability", "디스크 오류", "배드블록", "smart", "저장장치 장애"),
                        List.of(),
                        List.of("STORAGE_REPLACEMENT_SUSPECTED"),
                        List.of()
                ));
                case "as-rag-visit-whea-bsod" -> copy.put("metadata", metadataJson(
                        List.of("whea", "whea logger", "whea-logger", "whea uncorrectable", "corrected hardware error", "fatal hardware error", "bugcheck", "bugcheck 0x", "0x00000124", "bsod", "blue screen", "stop code", "hardware error", "hardware error event", "machine check", "livekernelevent", "memory hardware", "블루스크린", "하드웨어 오류", "메모리 오류"),
                        List.of(),
                        List.of("WHEA_ERROR_REPEAT", "BSOD_REPEAT"),
                        List.of()
                ));
                case "as-rag-visit-power-thermal" -> copy.put("metadata", metadataJson(
                        List.of("kernel-power", "kernel power", "event id 41", "event 41", "unexpected shutdown", "previous system shutdown was unexpected", "rebooted without cleanly shutting down", "shutdown was unexpected", "sudden shutdown", "lost power unexpectedly", "power loss", "power loss under load", "bugcheckcode 0", "psu", "power supply", "전원 꺼짐"),
                        List.of(),
                        List.of("PSU_OR_POWER_PATH_RISK"),
                        List.of()
                ));
                case "as-rag-visit-fan-thermal" -> copy.put("metadata", metadataJson(
                        List.of("thermal shutdown", "fan rpm 0", "fan not spinning", "cooling fan stopped", "thermal throttle", "thermal throttling", "thermal event", "processor speed is being limited", "system firmware", "acpi thermal zone", "overheat", "overheating", "fan failure", "팬 미동작", "과열"),
                        List.of(),
                        List.of("THERMAL_SERVICE_REQUIRED"),
                        List.of()
                ));
                case "as-rag-visit-boot-remote-blocked" -> copy.put("metadata", metadataJson(
                        List.of("boot failure", "cannot boot", "no boot", "os boot failure", "automatic repair", "startup repair", "winre", "boot critical", "device offline", "heartbeat missing", "heartbeat gap", "remote unavailable", "remote connection unavailable", "quick assist unavailable", "quick assist failed", "부팅 불가", "원격 연결 불가"),
                        List.of(),
                        List.of("DEVICE_OFFLINE", "REMOTE_HELP_NOT_AVAILABLE"),
                        List.of()
                ));
                case "as-rag-unsupported-scope" -> copy.put("metadata", metadataJson(
                        List.of("fps tuning", "fps drops", "frame time", "frame-time", "game fps", "valorant fps", "game optimization", "overclock", "overclock stability", "isp", "router", "printer", "peripheral", "data recovery", "file recovery", "illegal software", "physical damage", "broken screen", "water damage", "liquid damage", "오버클럭", "공유기", "프린터", "주변기기", "데이터 복구", "물리 파손"),
                        List.of(),
                        List.of(),
                        List.of("UNSUPPORTED_SCOPE")
                ));
                default -> {
                    // Keep fallback/diagnosis rows as-is.
                }
            }
            rows.add(copy);
        }
        return rows;
    }

    private static String metadataJson(
            List<String> keywords,
            List<String> remoteActions,
            List<String> visitReasons,
            List<String> blockingFactors
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("keywords", keywords);
        metadata.put("remoteActions", remoteActions);
        metadata.put("visitReasons", visitReasons);
        if (!blockingFactors.isEmpty()) {
            metadata.put("blockingFactors", blockingFactors);
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize AS RAG test metadata", exception);
        }
    }

    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / (double) denominator;
    }

    private static List<JsonNode> parseAndAssertRawLogShape(String fileName, String logText) throws Exception {
        List<JsonNode> records = new ArrayList<>();
        int lineNumber = 0;
        for (String line : logText.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            lineNumber++;
            JsonNode record = OBJECT_MAPPER.readTree(line);
            assertThat(record.path("schemaVersion").asText()).as(fileName + ":" + lineNumber).isEqualTo("1");
            assertThat(record.path("collectedAt").asText()).as(fileName + ":" + lineNumber).isNotBlank();
            assertThat(record.path("agentId").asText()).as(fileName + ":" + lineNumber).isNotBlank();
            assertThat(record.path("sequence").isNumber()).as(fileName + ":" + lineNumber).isTrue();
            assertThat(record.path("kind").asText()).as(fileName + ":" + lineNumber).isNotBlank();
            assertThat(record.path("payload").isObject()).as(fileName + ":" + lineNumber).isTrue();
            assertThat(record.path("privacyFlags").path("masked").asBoolean()).as(fileName + ":" + lineNumber).isTrue();
            assertThat(record.path("privacyFlags").path("containsRawPath").asBoolean()).as(fileName + ":" + lineNumber).isFalse();
            if ("EVENT_LOG".equals(record.path("kind").asText())) {
                JsonNode payload = record.path("payload");
                assertThat(payload.path("source").asText()).as(fileName + ":" + lineNumber).isNotBlank();
                assertThat(payload.path("eventId").isNumber()).as(fileName + ":" + lineNumber).isTrue();
                assertThat(payload.path("level").asText()).as(fileName + ":" + lineNumber).isNotBlank();
                assertThat(payload.path("messageMasked").asText()).as(fileName + ":" + lineNumber).isNotBlank();
                assertThat(payload.path("eventTime").asText()).as(fileName + ":" + lineNumber).isNotBlank();
            }
            records.add(record);
        }
        assertThat(records).as(fileName).isNotEmpty();
        return records;
    }

    private record RealisticFixture(
            String id,
            String fileName,
            String expectedService,
            String expectedDecision,
            String basis
    ) {
    }

    private record CorpusCase(
            String id,
            String expectedService,
            String expectedDecision,
            List<RawRecord> records,
            List<String> tags
    ) {
    }

    private record RawRecord(
            String kind,
            Map<String, Object> payload
    ) {
    }
}
