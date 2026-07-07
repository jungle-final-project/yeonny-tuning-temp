package com.buildgraph.prototype.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.support.AsLogRagAnalysisService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.mockito.ArgumentCaptor;

class AgentLogQueryServiceTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            "2026-06-30T00:00:00Z"
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AsLogRagAnalysisService asLogRagAnalysisService = mock(AsLogRagAnalysisService.class);
    private final AgentLogQueryService service = new AgentLogQueryService(jdbcTemplate, asLogRagAnalysisService);

    @Test
    void uploadStoresRowAfterJsonlValidation() {
        String content = """
                {"timestamp":"2026-07-02T10:00:00Z","cpu":30}
                {"timestamp":"2026-07-02T10:00:01Z","gpu":45}
                """;
        MockMultipartFile file = file("agent-log.jsonl", "application/x-ndjson", content);
        when(jdbcTemplate.queryForMap(
                anyString(),
                eq(USER.internalId()),
                eq(30),
                eq("agent-log.jsonl"),
                eq(file.getSize()),
                eq("agent-log.jsonl"),
                contains("2 lines")
        )).thenReturn(Map.of(
                "id", "log-public-id",
                "status", "UPLOADED",
                "file_name", "agent-log.jsonl",
                "file_size", file.getSize(),
                "range_minutes", 30,
                "summary", "Validated JSONL log upload (2 lines).",
                "created_at", "2026-07-02T10:00:00Z",
                "delete_after", "2026-08-01T10:00:00Z"
        ));

        Map<String, Object> result = service.upload(file, null, true, USER);

        assertThat(result).containsEntry("id", "log-public-id");
        assertThat(result).containsEntry("fileName", "agent-log.jsonl");
        assertThat(result).containsEntry("rangeMinutes", 30);
    }

    @Test
    void rejectsUnsupportedExtensionWithoutCreatingRow() {
        MockMultipartFile file = file("agent-log.log", "text/plain", "{}\n");

        assertThatThrownBy(() -> service.upload(file, 30, true, USER))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("reason", "INVALID_EXTENSION");
                });
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsUnsupportedMimeWithoutCreatingRow() {
        MockMultipartFile file = file("agent-log.jsonl", "image/png", "{}\n");

        assertThatThrownBy(() -> service.upload(file, 30, true, USER))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("reason", "INVALID_MIME");
                });
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsInvalidJsonlWithoutCreatingRow() {
        MockMultipartFile file = file("agent-log.ndjson", "application/x-ndjson", """
                {"timestamp":"2026-07-02T10:00:00Z"}
                not-json
                """);

        assertThatThrownBy(() -> service.upload(file, 30, true, USER))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("reason", "INVALID_JSONL");
                });
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsMoreThanTwentyThousandLinesWithoutCreatingRow() {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 20_001; index += 1) {
            builder.append("{\"timestamp\":\"2026-07-02T10:00:00Z\",\"line\":")
                    .append(index)
                    .append("}\n");
        }
        MockMultipartFile file = file("agent-log.jsonl", "application/x-ndjson", builder.toString());

        assertThatThrownBy(() -> service.upload(file, 30, true, USER))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("reason", "LINE_LIMIT_EXCEEDED");
                });
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void masksSensitiveValuesBeforeStoragePathProcessing() {
        String content = """
                {"message":"Authorization: Bearer access-secret-123 user@example.com 010-1234-5678"}
                {"message":"refreshToken=refresh-secret-456 accessToken=access-secret-789"}
                """;
        AgentLogQueryService.ValidatedLogFile result = AgentLogQueryService.validateLogFile(
                file("agent-log.jsonl", "application/x-ndjson", content)
        );

        assertThat(result.lineCount()).isEqualTo(2);
        assertThat(result.sanitizedContent())
                .doesNotContain("access-secret-123")
                .doesNotContain("refresh-secret-456")
                .doesNotContain("access-secret-789")
                .doesNotContain("user@example.com")
                .doesNotContain("010-1234-5678")
                .contains("[REDACTED_AUTHORIZATION]")
                .contains("[REDACTED_REFRESH_TOKEN]")
                .contains("[REDACTED_ACCESS_TOKEN]")
                .contains("[REDACTED_EMAIL]")
                .contains("[REDACTED_PHONE]");
    }

    @Test
    void previewValidatesAndMasksLogBeforeRagAnalysis() {
        MockMultipartFile file = file("agent-log.jsonl", "application/x-ndjson", """
                {"timestamp":"2026-07-02T10:00:00Z","message":"user@example.com display driver reset"}
                """);
        when(asLogRagAnalysisService.analyzeText(eq("agent-log.jsonl"), anyString(), eq(30)))
                .thenReturn(Map.of("recommendedService", "DIAGNOSIS_ONLY"));

        Map<String, Object> result = service.previewAsRag(file, null);

        ArgumentCaptor<String> logText = ArgumentCaptor.forClass(String.class);
        verify(asLogRagAnalysisService).analyzeText(eq("agent-log.jsonl"), logText.capture(), eq(30));
        assertThat(logText.getValue())
                .contains("[REDACTED_EMAIL]")
                .doesNotContain("user@example.com");
        assertThat(result).containsEntry("recommendedService", "DIAGNOSIS_ONLY");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void previewRejectsInvalidFileBeforeRagAnalysis() {
        MockMultipartFile file = file("agent-log.log", "text/plain", "{}\n");

        assertThatThrownBy(() -> service.previewAsRag(file, 30))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FILE_VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("reason", "INVALID_EXTENSION");
                });
        verifyNoInteractions(jdbcTemplate, asLogRagAnalysisService);
    }

    private static MockMultipartFile file(String name, String contentType, String content) {
        return new MockMultipartFile(
                "file",
                name,
                contentType,
                content.getBytes(StandardCharsets.UTF_8)
        );
    }
}
