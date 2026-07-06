package com.buildgraph.prototype.log;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.support.AsLogRagAnalysisService;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentLogQueryService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;
    private static final int MAX_LINE_COUNT = 20_000;
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/json",
            "application/x-ndjson",
            "text/plain",
            "application/octet-stream"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:\\+82[- ]?)?0\\d{1,2}[- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)"
    );
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(
            "(?i)Authorization\\s*:\\s*Bearer\\s+[^\\s\"'}]+"
    );
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile(
            "(?i)(access[-_ ]?token\\s*[:=]\\s*)[^\\s\"'}]+"
    );
    private static final Pattern REFRESH_TOKEN_PATTERN = Pattern.compile(
            "(?i)(refresh[-_ ]?token\\s*[:=]\\s*)[^\\s\"'}]+"
    );

    private final JdbcTemplate jdbcTemplate;
    private final AsLogRagAnalysisService asLogRagAnalysisService;

    public AgentLogQueryService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new AsLogRagAnalysisService(jdbcTemplate));
    }

    @Autowired
    public AgentLogQueryService(JdbcTemplate jdbcTemplate, AsLogRagAnalysisService asLogRagAnalysisService) {
        this.jdbcTemplate = jdbcTemplate;
        this.asLogRagAnalysisService = asLogRagAnalysisService;
    }

    public Map<String, Object> upload(MultipartFile file, Integer rangeMinutes, Boolean consentAccepted) {
        if (!Boolean.TRUE.equals(consentAccepted)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Log upload consent is required.");
        }
        ValidatedLogFile validated = validateLogFile(file);
        Integer minutes = rangeMinutes == null ? 30 : rangeMinutes;
        Map<String, Object> asRagAnalysis = asLogRagAnalysisService.analyze(file, minutes);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO agent_log_uploads (
                  user_id,
                  range_minutes,
                  status,
                  file_name,
                  file_size,
                  storage_path,
                  summary,
                  as_rag_analysis,
                  consent_accepted_at,
                  delete_after
                )
                VALUES (
                  (SELECT id FROM users WHERE email = 'user@example.com'),
                  ?,
                  'UPLOADED',
                  ?,
                  ?,
                  'seed/agent-logs/' || ?,
                  ?,
                  ?::jsonb,
                  now(),
                  now() + interval '30 days'
                )
                RETURNING public_id::text AS id, status, file_name, file_size, range_minutes, summary, as_rag_analysis::text AS as_rag_analysis, created_at, delete_after
                """,
                minutes,
                validated.fileName(),
                validated.fileSize(),
                validated.fileName(),
                asRagAnalysis.get("summaryText"),
                toJson(asRagAnalysis));
        return logMap(row);
    }

    public Map<String, Object> upload(
            MultipartFile file,
            Integer rangeMinutes,
            Boolean consentAccepted,
            CurrentUserService.CurrentUser user
    ) {
        if (!Boolean.TRUE.equals(consentAccepted)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Log upload consent is required.");
        }
        ValidatedLogFile validated = validateLogFile(file);
        Integer minutes = rangeMinutes == null ? 30 : rangeMinutes;
        String summary = "Validated JSONL log upload (" + validated.lineCount() + " lines).";
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO agent_log_uploads (
                  user_id,
                  range_minutes,
                  status,
                  file_name,
                  file_size,
                  storage_path,
                  summary,
                  consent_accepted_at,
                  delete_after
                )
                VALUES (
                  ?,
                  ?,
                  'UPLOADED',
                  ?,
                  ?,
                  'seed/agent-logs/' || ?,
                  ?,
                  now(),
                  now() + interval '30 days'
                )
                RETURNING public_id::text AS id, status, file_name, file_size, range_minutes, summary, created_at, delete_after
                """, user.internalId(), minutes, validated.fileName(), validated.fileSize(), validated.fileName(), summary);
        return logMap(row);
    }

    public Map<String, Object> previewAsRag(MultipartFile file, Integer rangeMinutes) {
        Integer minutes = rangeMinutes == null ? 30 : rangeMinutes;
        return asLogRagAnalysisService.analyze(file, minutes);
    }

    public Map<String, Object> detail(String id) {
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id, status, file_name, file_size, range_minutes, summary, as_rag_analysis::text AS as_rag_analysis, created_at, delete_after
                        FROM agent_log_uploads
                        WHERE public_id = ?::uuid
                        """, id)
                .stream()
                .findFirst()
                .map(this::logMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Log upload not found."));
    }

    public Map<String, Object> detail(String id, CurrentUserService.CurrentUser user) {
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id, status, file_name, file_size, range_minutes, summary, as_rag_analysis::text AS as_rag_analysis, created_at, delete_after
                        FROM agent_log_uploads
                        WHERE public_id = ?::uuid
                          AND user_id = ?
                        """, id, user.internalId())
                .stream()
                .findFirst()
                .map(this::logMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Log upload not found."));
    }

    private Map<String, Object> logMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "status", DbValueMapper.string(row, "status"),
                "fileName", DbValueMapper.string(row, "file_name"),
                "fileSize", DbValueMapper.integer(row, "file_size"),
                "rangeMinutes", DbValueMapper.integer(row, "range_minutes"),
                "summary", DbValueMapper.string(row, "summary"),
                "asRagAnalysis", DbValueMapper.json(row, "as_rag_analysis", null),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "deleteAfter", DbValueMapper.timestamp(row, "delete_after")
        );
    }

    static ValidatedLogFile validateLogFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw fileValidationError("MISSING_FILE", "A log file is required.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw fileValidationError("FILE_SIZE_EXCEEDED", "Log files must be 10MiB or smaller.");
        }
        String fileName = normalizeFileName(file.getOriginalFilename());
        String lowerFileName = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!lowerFileName.endsWith(".jsonl") && !lowerFileName.endsWith(".ndjson")) {
            throw fileValidationError("INVALID_EXTENSION", "Only .jsonl or .ndjson log files are allowed.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()
                && !ALLOWED_MIME_TYPES.contains(contentType.toLowerCase(java.util.Locale.ROOT))) {
            throw fileValidationError("INVALID_MIME", "Unsupported log file MIME type.");
        }

        String rawContent;
        try {
            rawContent = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw fileValidationError("READ_FAILED", "Could not read log file.");
        }

        int lineCount = 0;
        StringBuilder sanitized = new StringBuilder(rawContent.length());
        String[] lines = rawContent.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
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
            lineCount += 1;
            if (lineCount > MAX_LINE_COUNT) {
                throw fileValidationError("LINE_LIMIT_EXCEEDED", "Log files can contain at most 20000 lines.");
            }
            validateJsonObjectLine(line, lineNumber);
            sanitized.append(maskSensitive(line)).append('\n');
        }
        if (lineCount == 0) {
            throw fileValidationError("EMPTY_JSONL", "The log file does not contain JSONL lines.");
        }
        String sanitizedContent = sanitized.toString();
        if (containsSensitiveValue(sanitizedContent)) {
            throw fileValidationError("PII_MASKING_FAILED", "Failed to mask sensitive log values.");
        }
        return new ValidatedLogFile(fileName, file.getSize(), lineCount, sanitizedContent);
    }

    private static void validateJsonObjectLine(String line, int lineNumber) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(line);
            if (node == null || !node.isObject()) {
                throw fileValidationError("INVALID_JSONL", "JSONL lines must be JSON objects.", Map.of("line", lineNumber));
            }
        } catch (IOException exception) {
            throw fileValidationError("INVALID_JSONL", "Failed to parse JSONL line.", Map.of("line", lineNumber));
        }
    }

    private static String maskSensitive(String value) {
        String masked = AUTHORIZATION_PATTERN.matcher(value).replaceAll("[REDACTED_AUTHORIZATION]");
        masked = REFRESH_TOKEN_PATTERN.matcher(masked).replaceAll("[REDACTED_REFRESH_TOKEN]");
        masked = ACCESS_TOKEN_PATTERN.matcher(masked).replaceAll("[REDACTED_ACCESS_TOKEN]");
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("[REDACTED_EMAIL]");
        return PHONE_PATTERN.matcher(masked).replaceAll("[REDACTED_PHONE]");
    }

    private static boolean containsSensitiveValue(String value) {
        return AUTHORIZATION_PATTERN.matcher(value).find()
                || REFRESH_TOKEN_PATTERN.matcher(value).find()
                || ACCESS_TOKEN_PATTERN.matcher(value).find()
                || EMAIL_PATTERN.matcher(value).find()
                || PHONE_PATTERN.matcher(value).find();
    }

    private static String normalizeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "agent-log.jsonl";
        }
        return originalFilename.trim();
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

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    record ValidatedLogFile(String fileName, long fileSize, int lineCount, String sanitizedContent) {
    }
}
