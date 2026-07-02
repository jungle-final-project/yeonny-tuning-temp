package com.buildgraph.prototype.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void apiExceptionPreservesDetails() {
        ResponseEntity<ApiErrorResponse> response = handler.handleApiException(new ApiException(
                HttpStatus.BAD_REQUEST,
                "FILE_VALIDATION_ERROR",
                "로그 파일 형식이 올바르지 않습니다.",
                Map.of("field", "file", "reason", "INVALID_EXTENSION")
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FILE_VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("로그 파일 형식이 올바르지 않습니다.");
        assertThat(response.getBody().details()).containsEntry("field", "file");
        assertThat(response.getBody().details()).containsEntry("reason", "INVALID_EXTENSION");
    }

    @Test
    void validationErrorIncludesRequestParameterDetails() {
        ResponseEntity<ApiErrorResponse> response = handler.handleValidationException(
                new MissingServletRequestParameterException("file", "MultipartFile")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().details()).containsEntry("parameter", "file");
        assertThat(response.getBody().details()).containsEntry("parameterType", "MultipartFile");
        assertThat(response.getBody().details()).containsEntry("errors", List.of(Map.of(
                "field", "file",
                "message", "required"
        )));
    }
}
