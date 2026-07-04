package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PcAgentLogSummaryService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long MAX_GZIP_SIZE = 10L * 1024L * 1024L;
    private static final int MAX_LINE_COUNT = 20_000;
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "schemaVersion",
            "collectedAt",
            "agentId",
            "sequence",
            "kind",
            "payload",
            "privacyFlags"
    );
    private static final Set<String> KNOWN_KINDS = Set.of(
            "SYSTEM_METRIC",
            "AGENT_HEALTH",
            "PROCESS_CATEGORY",
            "STORAGE_HEALTH",
            "THERMAL_THROTTLE",
            "DRIVER_EVENT",
            "APP_CRASH",
            "WINDOWS_EVENT",
            "NETWORK_EVENT",
            "BOOT_EVENT"
    );

    ValidatedAgentLog validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw fileValidationError("MISSING_FILE", "Agent log gzip file is required.");
        }
        if (file.getSize() > MAX_GZIP_SIZE) {
            throw fileValidationError("FILE_SIZE_EXCEEDED", "Agent log gzip file must be 10MiB or smaller.");
        }
        String fileName = fileName(file);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".gz")) {
            throw fileValidationError("INVALID_EXTENSION", "Agent log upload must be gzip.");
        }

        byte[] gzipBytes;
        try {
            gzipBytes = file.getBytes();
        } catch (IOException error) {
            throw fileValidationError("READ_FAILED", "Agent log gzip file could not be read.");
        }
        String content = unzip(gzipBytes);
        SummaryAccumulator summary = parseJsonl(content);
        return new ValidatedAgentLog(
                fileName,
                gzipBytes.length,
                gzipBytes,
                sha256(gzipBytes),
                summary.schemaVersion(),
                summary.summaryText(),
                summary.summaryPayload(),
                summary.featurePayload(),
                summary.eventCounts(),
                summary.riskFlags(),
                summary.privacySummary()
        );
    }

    private SummaryAccumulator parseJsonl(String content) {
        SummaryAccumulator summary = new SummaryAccumulator();
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int index = 0; index < lines.length; index += 1) {
            String line = lines[index];
            boolean finalTrailingLine = index == lines.length - 1 && line.isEmpty();
            if (finalTrailingLine) {
                continue;
            }
            int lineNumber = index + 1;
            if (line.isBlank()) {
                throw fileValidationError("INVALID_JSONL", "Blank JSONL lines are not allowed.", Map.of("line", lineNumber));
            }
            summary.incrementLineCount();
            if (summary.lineCount > MAX_LINE_COUNT) {
                throw fileValidationError("LINE_LIMIT_EXCEEDED", "Agent log upload supports at most 20000 lines.");
            }
            JsonNode node;
            try {
                node = OBJECT_MAPPER.readTree(line);
            } catch (IOException error) {
                throw fileValidationError("INVALID_JSONL", "JSONL line could not be parsed.", Map.of("line", lineNumber));
            }
            if (node == null || !node.isObject()) {
                throw fileValidationError("INVALID_JSONL", "JSONL line must be an object.", Map.of("line", lineNumber));
            }
            validateEnvelope(node, lineNumber);
            summary.accept(node);
        }
        if (summary.lineCount == 0) {
            throw fileValidationError("EMPTY_JSONL", "Agent log gzip contains no JSONL rows.");
        }
        return summary;
    }

    private static void validateEnvelope(JsonNode node, int lineNumber) {
        for (String field : REQUIRED_FIELDS) {
            if (!node.hasNonNull(field)) {
                throw fileValidationError("MISSING_ENVELOPE_FIELD", "Agent log envelope field is missing.", Map.of(
                        "line", lineNumber,
                        "field", field
                ));
            }
        }
        if (!node.get("payload").isObject()) {
            throw fileValidationError("INVALID_PAYLOAD", "payload must be an object.", Map.of("line", lineNumber));
        }
        JsonNode privacyFlags = node.get("privacyFlags");
        if (!privacyFlags.isObject()) {
            throw fileValidationError("INVALID_PRIVACY_FLAGS", "privacyFlags must be an object.", Map.of("line", lineNumber));
        }
        boolean containsRawPath = privacyFlags.path("containsRawPath").asBoolean(false);
        boolean masked = privacyFlags.path("masked").asBoolean(false);
        if (containsRawPath && !masked) {
            throw fileValidationError("UNMASKED_RAW_PATH", "Raw paths must be masked before server upload.", Map.of("line", lineNumber));
        }
    }

    private static String unzip(byte[] gzipBytes) {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(gzipBytes))) {
            return new String(gzipInputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw fileValidationError("INVALID_GZIP", "Agent log upload must be a valid gzip JSONL file.");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 digest is unavailable.", error);
        }
    }

    private static String fileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        String fallback = "agent-log.jsonl.gz";
        if (original == null || original.isBlank()) {
            return fallback;
        }
        String normalized = original.replace("\\", "/");
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        return name.isBlank() ? fallback : name;
    }

    private static ApiException fileValidationError(String reason, String message) {
        return fileValidationError(reason, message, Map.of());
    }

    private static ApiException fileValidationError(String reason, String message, Map<String, Object> extraDetails) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("field", "file");
        details.put("reason", reason);
        details.putAll(extraDetails);
        return new ApiException(HttpStatus.BAD_REQUEST, "FILE_VALIDATION_ERROR", message, details);
    }

    record ValidatedAgentLog(
            String fileName,
            long fileSize,
            byte[] gzipBytes,
            String sha256,
            int schemaVersion,
            String summaryText,
            Map<String, Object> summaryPayload,
            Map<String, Object> featurePayload,
            Map<String, Object> eventCounts,
            Map<String, Object> riskFlags,
            Map<String, Object> privacySummary
    ) {
    }

    private static final class SummaryAccumulator {
        private int lineCount;
        private int schemaVersion = 1;
        private String agentId;
        private Long minSequence;
        private Long maxSequence;
        private Instant firstCollectedAt;
        private Instant lastCollectedAt;
        private int unknownKindCount;
        private int rawPathRows;
        private final Map<String, Integer> eventCounts = new LinkedHashMap<>();
        private final Map<String, Integer> unavailableReasonCounts = new LinkedHashMap<>();
        private final Set<String> knownSignals = new LinkedHashSet<>();
        private final MetricStats cpuUsage = new MetricStats();
        private final MetricStats memoryUsage = new MetricStats();
        private final MetricStats diskUsage = new MetricStats();
        private final MetricStats gpuUsage = new MetricStats();
        private final MetricStats vramUsage = new MetricStats();
        private final MetricStats gpuTemp = new MetricStats();
        private final MetricStats freeSpacePercent = new MetricStats();

        void incrementLineCount() {
            lineCount += 1;
        }

        void accept(JsonNode node) {
            schemaVersion = intValue(node.get("schemaVersion"), schemaVersion);
            agentId = firstNonBlank(agentId, text(node.get("agentId")));
            long sequence = longValue(node.get("sequence"), lineCount);
            minSequence = minSequence == null ? sequence : Math.min(minSequence, sequence);
            maxSequence = maxSequence == null ? sequence : Math.max(maxSequence, sequence);
            Instant collectedAt = instant(text(node.get("collectedAt")));
            if (collectedAt != null) {
                firstCollectedAt = firstCollectedAt == null || collectedAt.isBefore(firstCollectedAt) ? collectedAt : firstCollectedAt;
                lastCollectedAt = lastCollectedAt == null || collectedAt.isAfter(lastCollectedAt) ? collectedAt : lastCollectedAt;
            }

            String kind = text(node.get("kind"));
            eventCounts.merge(kind, 1, Integer::sum);
            if (!KNOWN_KINDS.contains(kind)) {
                unknownKindCount += 1;
            }
            JsonNode privacyFlags = node.get("privacyFlags");
            if (privacyFlags.path("containsRawPath").asBoolean(false)) {
                rawPathRows += 1;
            }

            JsonNode payload = node.get("payload");
            cpuUsage.add(number(payload, "cpuUsage", "cpuUsagePercent"));
            memoryUsage.add(number(payload, "memoryUsage", "memoryUsagePercent", "memoryUsedPercent", "ramUsage"));
            diskUsage.add(number(payload, "diskUsage", "diskUsagePercent", "diskUsedPercent"));
            gpuUsage.add(number(payload, "gpuUsage", "gpuUsagePercent"));
            vramUsage.add(number(payload, "vramUsage", "vramUsagePercent"));
            gpuTemp.add(number(payload, "gpuTemp", "gpuTemperatureC", "gpuTempCelsius"));
            freeSpacePercent.add(number(payload, "freeSpacePercent"));

            acceptUnavailableReasons(payload.get("unavailableReason"));
            collectKnownSignals(kind, payload);
        }

        int schemaVersion() {
            return schemaVersion;
        }

        String summaryText() {
            List<String> signals = knownSignals.stream().limit(3).toList();
            String signalText = signals.isEmpty() ? "특이 신호 없음" : String.join(", ", signals);
            return "Agent gzip log validated (" + lineCount + " lines, " + signalText + ").";
        }

        Map<String, Object> summaryPayload() {
            return map(
                    "lineCount", lineCount,
                    "agentId", agentId,
                    "schemaVersion", schemaVersion,
                    "firstCollectedAt", firstCollectedAt == null ? null : firstCollectedAt.toString(),
                    "lastCollectedAt", lastCollectedAt == null ? null : lastCollectedAt.toString(),
                    "sequenceStart", minSequence,
                    "sequenceEnd", maxSequence,
                    "knownSignals", List.copyOf(knownSignals),
                    "unknownKindCount", unknownKindCount
            );
        }

        Map<String, Object> featurePayload() {
            return map(
                    "lineCount", lineCount,
                    "sequenceStart", minSequence,
                    "sequenceEnd", maxSequence,
                    "maxCpuUsage", cpuUsage.maxOrNull(),
                    "avgCpuUsage", cpuUsage.avgOrNull(),
                    "maxMemoryUsage", memoryUsage.maxOrNull(),
                    "avgMemoryUsage", memoryUsage.avgOrNull(),
                    "maxDiskUsage", diskUsage.maxOrNull(),
                    "avgDiskUsage", diskUsage.avgOrNull(),
                    "maxGpuUsage", gpuUsage.maxOrNull(),
                    "maxVramUsage", vramUsage.maxOrNull(),
                    "maxGpuTemp", gpuTemp.maxOrNull(),
                    "minFreeSpacePercent", freeSpacePercent.minOrNull(),
                    "gpuMetricAvailable", gpuUsage.hasValue() || vramUsage.hasValue() || gpuTemp.hasValue(),
                    "unavailableReasonCounts", Map.copyOf(unavailableReasonCounts),
                    "unknownKindCount", unknownKindCount
            );
        }

        Map<String, Object> eventCounts() {
            return Map.copyOf(eventCounts);
        }

        Map<String, Object> riskFlags() {
            boolean thermalRisk = valueAtLeast(gpuTemp, 90.0) || knownSignals.contains("THERMAL_RISK");
            boolean storagePressureRisk = valueAtLeast(diskUsage, 90.0)
                    || (freeSpacePercent.hasValue() && freeSpacePercent.min <= 10.0)
                    || knownSignals.contains("STORAGE_HEALTH_RISK");
            boolean memoryPressureRisk = valueAtLeast(memoryUsage, 90.0);
            return map(
                    "thermalRisk", thermalRisk,
                    "storagePressureRisk", storagePressureRisk,
                    "memoryPressureRisk", memoryPressureRisk,
                    "gpuMetricAvailable", gpuUsage.hasValue() || vramUsage.hasValue() || gpuTemp.hasValue()
            );
        }

        Map<String, Object> privacySummary() {
            return map(
                    "rawPathRows", rawPathRows,
                    "unmaskedRawPathRows", 0,
                    "containsRawPath", rawPathRows > 0,
                    "rawStoredForReprocessingOnly", true
            );
        }

        private void collectKnownSignals(String kind, JsonNode payload) {
            Double gpuTemperature = number(payload, "gpuTemp", "gpuTemperatureC", "gpuTempCelsius");
            Double diskPercent = number(payload, "diskUsage", "diskUsagePercent", "diskUsedPercent");
            Double freeSpace = number(payload, "freeSpacePercent");
            Double memoryPercent = number(payload, "memoryUsage", "memoryUsagePercent", "memoryUsedPercent", "ramUsage");
            if ("THERMAL_THROTTLE".equals(kind) || gpuTemperature != null && gpuTemperature >= 90.0) {
                knownSignals.add("THERMAL_RISK");
            }
            if ("STORAGE_HEALTH".equals(kind)
                    || diskPercent != null && diskPercent >= 90.0
                    || freeSpace != null && freeSpace <= 10.0) {
                knownSignals.add("STORAGE_HEALTH_RISK");
            }
            if (memoryPercent != null && memoryPercent >= 90.0) {
                knownSignals.add("MEMORY_PRESSURE_RISK");
            }
        }

        private void acceptUnavailableReasons(JsonNode unavailableReason) {
            if (unavailableReason == null || unavailableReason.isNull()) {
                return;
            }
            if (unavailableReason.isTextual()) {
                String reason = text(unavailableReason);
                if (reason != null) {
                    unavailableReasonCounts.merge(reason, 1, Integer::sum);
                }
                return;
            }
            if (unavailableReason.isObject()) {
                unavailableReason.fields().forEachRemaining(entry -> {
                    String reason = text(entry.getValue());
                    if (reason != null) {
                        unavailableReasonCounts.merge(entry.getKey() + ":" + reason, 1, Integer::sum);
                    }
                });
            }
        }

        private static boolean valueAtLeast(MetricStats stats, double threshold) {
            return stats.hasValue() && stats.max >= threshold;
        }
    }

    private static final class MetricStats {
        private int count;
        private double sum;
        private double min;
        private double max;

        void add(Double value) {
            if (value == null) {
                return;
            }
            if (count == 0) {
                min = value;
                max = value;
            } else {
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            count += 1;
            sum += value;
        }

        boolean hasValue() {
            return count > 0;
        }

        Double minOrNull() {
            return hasValue() ? min : null;
        }

        Double maxOrNull() {
            return hasValue() ? max : null;
        }

        Double avgOrNull() {
            return hasValue() ? sum / count : null;
        }
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            map.put(keyValues[index].toString(), keyValues[index + 1]);
        }
        return map;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstNonBlank(String current, String next) {
        return current == null || current.isBlank() ? next : current;
    }

    private static int intValue(JsonNode node, int fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long longValue(JsonNode node, long fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asText());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Double number(JsonNode payload, String... keys) {
        for (String key : keys) {
            JsonNode value = payload.get(key);
            if (value != null && value.isNumber()) {
                return value.asDouble();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText());
                } catch (NumberFormatException ignored) {
                    // Try the next key.
                }
            }
        }
        return null;
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
