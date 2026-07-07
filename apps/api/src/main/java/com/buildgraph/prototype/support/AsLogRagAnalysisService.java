package com.buildgraph.prototype.support;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AsLogRagAnalysisService {
    private static final int MAX_ANALYSIS_CHARS = 80_000;
    private static final int MAX_SAMPLE_LINES = 20;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("[A-Za-z]:\\\\[^\\s\"']+");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(?i)(token|password|passwd|secret|authorization)\\s*[:=]\\s*[^\\s,}]+");

    private final JdbcTemplate jdbcTemplate;

    public AsLogRagAnalysisService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> analyze(MultipartFile file, Integer rangeMinutes) {
        String fileName = file == null || file.getOriginalFilename() == null
                ? "agent-log.jsonl"
                : file.getOriginalFilename();
        String logText = readLogText(fileName, file);
        return analyze(fileName, logText, rangeMinutes == null ? 30 : rangeMinutes);
    }

    public Map<String, Object> analyzeText(String fileName, String logText, int rangeMinutes) {
        return analyze(fileName, logText, rangeMinutes);
    }

    Map<String, Object> analyze(String fileName, String logText, int rangeMinutes) {
        String safeLogText = truncate(logText == null ? "" : logText, MAX_ANALYSIS_CHARS);
        String indexText = normalize(safeLogText);
        List<Candidate> candidates = evidenceRows().stream()
                .map(row -> toCandidate(row, indexText))
                .filter(candidate -> !candidate.matchedKeywords().isEmpty())
                .filter(candidate -> candidateEligible(candidate, indexText))
                .sorted(Comparator.comparingDouble(Candidate::rank).reversed()
                        .thenComparing(Comparator.comparingInt(AsLogRagAnalysisService::servicePriority).reversed()))
                .limit(3)
                .toList();

        if (candidates.isEmpty()) {
            Candidate fallback = fallbackCandidate();
            return analysisMap(
                    fileName,
                    rangeMinutes,
                    safeLogText,
                    fallback,
                    List.of(fallback),
                    "LOW"
            );
        }

        Candidate top = candidates.getFirst();
        return analysisMap(
                fileName,
                rangeMinutes,
                safeLogText,
                top,
                candidates,
                confidence(top.rank())
        );
    }

    public static String supportDecision(Map<String, Object> analysis) {
        return string(analysis, "supportDecision", "NEEDS_MORE_INFO");
    }

    public static String recommendedService(Map<String, Object> analysis) {
        return string(analysis, "recommendedService", "DIAGNOSIS_ONLY");
    }

    public static String riskLevel(Map<String, Object> analysis) {
        String decision = supportDecision(analysis);
        if ("VISIT_REQUIRED".equals(decision) || "REPAIR_OR_REPLACE".equals(decision)) {
            return "HIGH";
        }
        if ("REMOTE_POSSIBLE".equals(decision)) {
            return "MEDIUM";
        }
        return "LOW";
    }

    public static Map<String, Object> supportRouting(Map<String, Object> analysis) {
        Object routing = analysis == null ? null : analysis.get("supportRouting");
        if (routing instanceof Map<?, ?> routingMap) {
            return orderedCopy(routingMap);
        }
        return MockData.map(
                "recommendedDecision", "NEEDS_MORE_INFO",
                "confidence", "LOW",
                "reasonCodes", List.of("INSUFFICIENT_EVIDENCE"),
                "remoteActions", List.of(),
                "visitReasons", List.of(),
                "blockingFactors", List.of(),
                "recommendedService", "DIAGNOSIS_ONLY",
                "recommendedServiceLabel", "우선 진단만 받기",
                "adminApprovalRequired", true,
                "requiresAdminApproval", true
        );
    }

    public static List<Map<String, Object>> causeCandidates(Map<String, Object> analysis) {
        Object evidence = analysis == null ? null : analysis.get("evidence");
        if (!(evidence instanceof List<?> evidenceItems) || evidenceItems.isEmpty()) {
            return List.of(MockData.map(
                    "code", "INSUFFICIENT_EVIDENCE",
                    "label", "추가 로그 확인 필요",
                    "confidence", "LOW",
                    "reason", "AS 전용 RAG에서 명확한 반복 신호를 찾지 못했습니다.",
                    "evidenceIds", List.of()
            ));
        }
        return evidenceItems.stream()
                .filter(Map.class::isInstance)
                .map(item -> orderedCopy((Map<?, ?>) item))
                .map(item -> MockData.map(
                        "code", string(item, "reasonCode", "AS_RAG_MATCH"),
                        "label", string(item, "summary", "AS RAG 근거"),
                        "confidence", string(analysis, "confidence", "MEDIUM"),
                        "reason", string(item, "title", string(item, "summary", "AS RAG 근거와 로그가 일치합니다.")),
                        "evidenceIds", List.of(string(item, "id", string(item, "sourceId", "as-rag-evidence")))
                ))
                .toList();
    }

    public static Map<String, Object> logSummary(String ticketId, String symptom, Map<String, Object> analysis) {
        Map<String, Object> userSymptom = MockData.map(
                "text", symptom,
                "recommendedService", recommendedService(analysis),
                "recommendedServiceLabel", string(analysis, "recommendedServiceLabel", "우선 진단만 받기")
        );
        return MockData.map(
                "summaryVersion", "1",
                "ticketId", ticketId,
                "incidentWindow", null,
                "deviceProfile", MockData.map("source", "WEB_UPLOAD"),
                "userSymptom", userSymptom,
                "baseline", MockData.map(
                        "fileName", string(analysis, "fileName", "agent-log.jsonl"),
                        "rangeMinutes", analysis == null ? 30 : analysis.getOrDefault("rangeMinutes", 30)
                ),
                "timeline", List.of(),
                "anomalies", causeCandidates(analysis),
                "correlations", List.of(MockData.map(
                        "type", "AS_RAG_MATCH",
                        "summary", string(analysis, "summaryText", "AS 전용 RAG 분석 결과가 없습니다.")
                )),
                "ruleSignals", List.of(),
                "dataQuality", MockData.map(
                        "level", "LOW".equals(string(analysis, "confidence", "LOW")) ? "PARTIAL" : "ENOUGH",
                        "missingSignals", List.of()
                ),
                "evidenceRefs", evidenceRefs(analysis),
                "rawSamples", rawSamples(analysis),
                "summaryText", string(analysis, "summaryText", "AS 전용 RAG 분석 결과가 없습니다.")
        );
    }

    public static Map<String, Object> aiDiagnosisRequest(
            String ticketId,
            String symptom,
            Map<String, Object> logSummary,
            Map<String, Object> supportRouting
    ) {
        return MockData.map(
                "requestId", "ai-" + UUID.randomUUID(),
                "ticketId", ticketId,
                "userSymptom", MockData.map("text", symptom),
                "logSummary", logSummary,
                "rawSamples", logSummary.getOrDefault("rawSamples", List.of()),
                "supportRouting", supportRouting,
                "locale", "ko-KR",
                "outputContractVersion", "1"
        );
    }

    private Map<String, Object> analysisMap(
            String fileName,
            int rangeMinutes,
            String logText,
            Candidate top,
            List<Candidate> candidates,
            String confidence
    ) {
        String supportDecision = top.supportDecision();
        String recommendedService = top.recommendedService();
        String serviceLabel = serviceLabel(recommendedService);
        String recommendationMessage = recommendationMessage(recommendedService);
        String summaryText = summaryText(top, recommendationMessage);
        List<Map<String, Object>> evidence = candidates.stream()
                .map(this::evidenceMap)
                .toList();
        Map<String, Object> supportRouting = MockData.map(
                "recommendedDecision", supportDecision,
                "confidence", confidence,
                "reasonCodes", candidates.stream().map(Candidate::reasonCode).distinct().toList(),
                "remoteActions", distinctMetadataValues(candidates, "remoteActions"),
                "visitReasons", distinctMetadataValues(candidates, "visitReasons"),
                "blockingFactors", distinctMetadataValues(candidates, "blockingFactors"),
                "recommendedService", recommendedService,
                "recommendedServiceLabel", serviceLabel,
                "adminApprovalRequired", true,
                "requiresAdminApproval", true
        );
        return MockData.map(
                "analysisVersion", "1",
                "retrievalMode", "AS_RAG_KEYWORD",
                "source", "AS_PAGE_LOG_UPLOAD",
                "fileName", fileName,
                "rangeMinutes", rangeMinutes,
                "supportDecision", supportDecision,
                "supportDecisionLabel", supportDecisionLabel(supportDecision),
                "recommendedService", recommendedService,
                "recommendedServiceLabel", serviceLabel,
                "recommendationMessage", recommendationMessage,
                "confidence", confidence,
                "summaryText", summaryText,
                "evidence", evidence,
                "supportRouting", supportRouting,
                "rawSamples", rawSampleMaps(logText, top.matchedKeywords()),
                "createdAt", Instant.now().toString()
        );
    }

    private static List<Map<String, Object>> evidenceRefs(Map<String, Object> analysis) {
        Object evidence = analysis == null ? null : analysis.get("evidence");
        if (!(evidence instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(Map.class::isInstance)
                .map(item -> orderedCopy((Map<?, ?>) item))
                .map(item -> MockData.map(
                        "id", string(item, "id", string(item, "sourceId", "as-rag-evidence")),
                        "sourceId", string(item, "sourceId", "as-rag-evidence"),
                        "summary", string(item, "summary", "AS RAG 근거"),
                        "score", item.get("score")
                ))
                .toList();
    }

    private static List<Object> rawSamples(Map<String, Object> analysis) {
        Object samples = analysis == null ? null : analysis.get("rawSamples");
        if (samples instanceof List<?> list) {
            return list.stream().limit(MAX_SAMPLE_LINES).map(Object.class::cast).toList();
        }
        return List.of();
    }

    private Map<String, Object> evidenceMap(Candidate candidate) {
        return MockData.map(
                "id", candidate.publicId(),
                "sourceId", candidate.sourceId(),
                "sourceType", candidate.sourceType(),
                "symptomType", candidate.symptomType(),
                "recommendedService", candidate.recommendedService(),
                "supportDecision", candidate.supportDecision(),
                "reasonCode", candidate.reasonCode(),
                "title", candidate.title(),
                "summary", candidate.summary(),
                "score", round(candidate.rank()),
                "matchedKeywords", candidate.matchedKeywords()
        );
    }

    private Candidate toCandidate(Map<String, Object> row, String indexText) {
        Map<String, Object> metadata = metadata(row);
        List<String> keywords = stringList(metadata.get("keywords"));
        List<String> matched = keywords.stream()
                .filter(keyword -> containsKeyword(indexText, keyword))
                .distinct()
                .toList();
        double baseScore = number(row.get("score"), 0.65);
        double rank = Math.min(0.99, baseScore + (matched.size() * 0.08));
        return new Candidate(
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "source_id"),
                DbValueMapper.string(row, "symptom_type"),
                DbValueMapper.string(row, "source_type"),
                DbValueMapper.string(row, "recommended_service"),
                DbValueMapper.string(row, "support_decision"),
                DbValueMapper.string(row, "reason_code"),
                DbValueMapper.string(row, "title"),
                DbValueMapper.string(row, "summary"),
                metadata,
                matched,
                rank
        );
    }

    private Candidate fallbackCandidate() {
        return evidenceRows().stream()
                .filter(row -> "as-rag-diagnosis-unclear".equals(DbValueMapper.string(row, "source_id")))
                .findFirst()
                .map(row -> toCandidate(row, "diagnosis"))
                .orElseGet(() -> new Candidate(
                        "as-rag-fallback",
                        "as-rag-diagnosis-unclear",
                        null,
                        "SUPPORT_POLICY",
                        "DIAGNOSIS_ONLY",
                        "NEEDS_MORE_INFO",
                        "INSUFFICIENT_EVIDENCE",
                        "Unclear log signals should start with diagnosis only",
                        "명확한 반복 신호가 부족하면 우선 진단만 받기로 추가 확인하는 것이 적합합니다.",
                        Map.of(),
                        List.of(),
                        0.45
                ));
    }

    private List<Map<String, Object>> evidenceRows() {
        return jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       source_id,
                       symptom_type,
                       source_type,
                       recommended_service,
                       support_decision,
                       reason_code,
                       title,
                       chunk_text,
                       summary,
                       score,
                       metadata::text AS metadata
                FROM as_rag_evidence
                WHERE active = true
                ORDER BY score DESC, id ASC
                """);
    }

    private String readLogText(String fileName, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "";
        }
        try {
            byte[] bytes = file.getBytes();
            if (fileName.toLowerCase(Locale.ROOT).endsWith(".gz")) {
                return readGzip(bytes);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent log file cannot be read.", exception);
        }
    }

    private String readGzip(byte[] bytes) throws IOException {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return new String(gzipInputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Map<String, Object>> rawSampleMaps(String logText, List<String> matchedKeywords) {
        if (logText == null || logText.isBlank()) {
            return List.of();
        }
        Set<String> keywordSet = new LinkedHashSet<>(matchedKeywords);
        List<Map<String, Object>> samples = new ArrayList<>();
        String[] lines = logText.split("\\R");
        for (int index = 0; index < lines.length && samples.size() < MAX_SAMPLE_LINES; index++) {
            String line = lines[index];
            String normalized = normalize(line);
            boolean matched = keywordSet.isEmpty() || keywordSet.stream().anyMatch(keyword -> containsKeyword(normalized, keyword));
            if (matched) {
                samples.add(MockData.map(
                        "sampleId", "web-log-" + (index + 1),
                        "lineNumber", index + 1,
                        "text", mask(truncate(line, 240))
                ));
            }
        }
        return samples;
    }

    private static Map<String, Object> metadata(Map<String, Object> row) {
        Object metadata = DbValueMapper.json(row, "metadata", Map.of());
        if (metadata instanceof Map<?, ?> metadataMap) {
            return orderedCopy(metadataMap);
        }
        return Map.of();
    }

    private static List<String> distinctMetadataValues(List<Candidate> candidates, String key) {
        return candidates.stream()
                .flatMap(candidate -> stringList(candidate.metadata().get(key)).stream())
                .distinct()
                .toList();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(Object::toString)
                    .filter(text -> !text.isBlank())
                    .toList();
        }
        if (value == null) {
            return List.of();
        }
        return List.of(value.toString());
    }

    private static boolean containsKeyword(String indexText, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return false;
        }
        if (normalizedKeyword.matches("[a-z0-9]+")) {
            return Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(normalizedKeyword) + "([^a-z0-9]|$)")
                    .matcher(indexText)
                    .find();
        }
        return indexText.contains(normalizedKeyword);
    }

    private static boolean candidateEligible(Candidate candidate, String indexText) {
        if (!"VISIT_SUPPORT".equals(candidate.recommendedService())) {
            return true;
        }
        return !hasNegatedVisitSignal(candidate, indexText) && hasStrongVisitSignal(candidate, indexText);
    }

    private static boolean hasStrongVisitSignal(Candidate candidate, String indexText) {
        return switch (candidate.symptomType() == null ? "" : candidate.symptomType()) {
            case "VISIT_BOOT_REMOTE_BLOCKED" -> hasAny(
                    indexText,
                    "boot failure",
                    "cannot boot",
                    "no boot",
                    "os boot failure",
                    "automatic repair",
                    "startup repair",
                    "winre",
                    "boot critical",
                    "device offline",
                    "heartbeat missing",
                    "heartbeat gap",
                    "remote unavailable",
                    "remote connection unavailable",
                    "quick assist unavailable",
                    "quick assist failed"
            );
            case "VISIT_DISK_FAILURE" -> hasAny(
                    indexText,
                    "smart critical",
                    "predictive failure",
                    "bad block",
                    "drive disappeared",
                    "reset to device",
                    "controller error",
                    "io operation at logical block",
                    "storahci",
                    "iastora",
                    "file system structure"
            ) || repeatedOrMultiple(indexText, "disk i/o", "i/o error", "io error", "filesystem error", "storage device instability", "ntfs error");
            case "VISIT_WHEA_BSOD" -> hasAny(indexText, "whea uncorrectable", "whea logger", "bugcheck 0x", "0x00000124", "fatal hardware error", "corrected hardware error", "hardware error event")
                    || repeatedOrMultiple(indexText, "whea", "bugcheck", "bsod", "blue screen", "hardware error", "machine check", "livekernelevent");
            case "VISIT_POWER_SHUTDOWN" -> hasAny(indexText, "power loss under load")
                    || repeatedOrMultiple(indexText, "kernel power", "event id 41", "event 41", "previous system shutdown was unexpected", "rebooted without cleanly shutting down", "shutdown was unexpected", "unexpected shutdown", "sudden shutdown", "lost power unexpectedly", "power loss");
            case "VISIT_FAN_THERMAL" -> hasAny(indexText, "thermal shutdown", "fan rpm 0", "fan not spinning", "cooling fan stopped", "acpi thermal zone")
                    || repeatedOrMultiple(indexText, "thermal throttle", "thermal throttling", "thermal event", "processor speed is being limited", "overheat", "overheating", "fan failure");
            default -> repeatedOrMultiple(indexText, "critical", "shutdown", "hardware error", "device offline");
        };
    }

    private static boolean hasNegatedVisitSignal(Candidate candidate, String indexText) {
        return switch (candidate.symptomType() == null ? "" : candidate.symptomType()) {
            case "VISIT_DISK_FAILURE" -> hasAny(
                    indexText,
                    "no smart critical",
                    "smart status normal",
                    "smart monitoring normal",
                    "no i/o error",
                    "no io error",
                    "without i/o error",
                    "without io error"
            );
            case "VISIT_WHEA_BSOD" -> hasAny(indexText, "no crash recorded", "no bsod", "without bsod", "no bugcheck");
            case "VISIT_POWER_SHUTDOWN" -> hasAny(indexText, "without shutdown", "no shutdown", "shutdown completed successfully");
            case "VISIT_FAN_THERMAL" -> hasAny(indexText, "without shutdown", "no thermal shutdown", "fan normal", "temperature normal");
            default -> false;
        };
    }

    private static boolean hasAny(String indexText, String... keywords) {
        for (String keyword : keywords) {
            if (containsKeyword(indexText, keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean repeatedOrMultiple(String indexText, String... keywords) {
        int occurrences = 0;
        for (String keyword : keywords) {
            occurrences += keywordOccurrences(indexText, keyword);
        }
        return occurrences >= 2 || (occurrences >= 1 && containsRepeatMarker(indexText));
    }

    private static int keywordOccurrences(String indexText, String keyword) {
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return 0;
        }
        if (normalizedKeyword.matches("[a-z0-9]+")) {
            var matcher = Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(normalizedKeyword) + "([^a-z0-9]|$)")
                    .matcher(indexText);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }
        int count = 0;
        int fromIndex = 0;
        while (fromIndex < indexText.length()) {
            int foundIndex = indexText.indexOf(normalizedKeyword, fromIndex);
            if (foundIndex < 0) {
                return count;
            }
            count++;
            fromIndex = foundIndex + Math.max(1, normalizedKeyword.length());
        }
        return count;
    }

    private static boolean containsRepeatMarker(String indexText) {
        return hasAny(indexText, "repeated", "repeatedly", "repeat", "recurring", "multiple", "again", "twice", "several", "frequent", "반복", "재발");
    }

    private static int servicePriority(Candidate candidate) {
        return switch (candidate.recommendedService()) {
            case "VISIT_SUPPORT" -> 2;
            case "REMOTE_SUPPORT" -> 1;
            default -> 0;
        };
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String mask(String value) {
        String masked = EMAIL_PATTERN.matcher(value).replaceAll("[EMAIL]");
        masked = WINDOWS_PATH_PATTERN.matcher(masked).replaceAll("[PATH]");
        return TOKEN_PATTERN.matcher(masked).replaceAll("$1=[REDACTED]");
    }

    private static String confidence(double score) {
        if (score >= 0.85) {
            return "HIGH";
        }
        if (score >= 0.65) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String recommendationMessage(String recommendedService) {
        return switch (recommendedService) {
            case "REMOTE_SUPPORT" -> "로그상 원격지원으로 먼저 확인할 가능성이 높습니다.";
            case "VISIT_SUPPORT" -> "로그상 방문 점검이 필요할 가능성이 높아 관리자 검토가 필요합니다.";
            default -> "로그상 명확한 원격/방문 신호가 부족해 우선 진단만 받는 것이 안전합니다.";
        };
    }

    private static String summaryText(Candidate candidate, String recommendationMessage) {
        return candidate.summary() + " " + recommendationMessage;
    }

    private static String serviceLabel(String recommendedService) {
        return switch (recommendedService) {
            case "REMOTE_SUPPORT" -> "원격지원 신청";
            case "VISIT_SUPPORT" -> "방문지원 신청";
            default -> "우선 진단만 받기";
        };
    }

    private static String supportDecisionLabel(String supportDecision) {
        return switch (supportDecision) {
            case "REMOTE_POSSIBLE" -> "원격지원 가능";
            case "VISIT_REQUIRED" -> "방문지원 필요";
            case "REPAIR_OR_REPLACE" -> "수리/교체 필요";
            case "SELF_SOLVABLE" -> "자가 조치 가능";
            case "MONITOR_ONLY" -> "관찰 필요";
            case "UNSUPPORTED" -> "지원 범위 밖";
            default -> "추가 정보 필요";
        };
    }

    private static double round(double value) {
        return Math.round(value * 100_000d) / 100_000d;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        return value == null ? fallback : value.toString();
    }

    private static Map<String, Object> orderedCopy(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private record Candidate(
            String publicId,
            String sourceId,
            String symptomType,
            String sourceType,
            String recommendedService,
            String supportDecision,
            String reasonCode,
            String title,
            String summary,
            Map<String, Object> metadata,
            List<String> matchedKeywords,
            double rank
    ) {
    }
}
