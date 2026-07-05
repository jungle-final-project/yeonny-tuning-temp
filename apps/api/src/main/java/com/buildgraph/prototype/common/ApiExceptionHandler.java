package com.buildgraph.prototype.common;

import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final String DEFAULT_ERROR_MESSAGE = "요청을 처리할 수 없습니다.";
    private static final String VALIDATION_ERROR_MESSAGE = "요청 값이 올바르지 않습니다.";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiErrorResponse(exception.code(), exception.getMessage(), exception.details()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        HttpStatusCode statusCode = exception.getStatusCode();
        String code = codeFor(statusCode);
        String message = exception.getReason() == null ? DEFAULT_ERROR_MESSAGE : exception.getReason();
        return ResponseEntity.status(statusCode).body(new ApiErrorResponse(code, message));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiErrorResponse> handleValidationException(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("VALIDATION_ERROR", VALIDATION_ERROR_MESSAGE, detailsFor(exception)));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiErrorResponse> handleDataAccess(DataAccessException exception) {
        // 잘못된 public_id 등이 'WHERE ...::uuid' 캐스팅에서 실패하면 Postgres가 SQLState 22P02
        // (invalid_text_representation)를 던진다. 존재하지 않는 리소스 요청이므로 500이 아니라 404다.
        if ("22P02".equals(sqlStateOf(exception))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiErrorResponse("NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."));
        }
        // 그 밖의 DB 오류는 실제 서버 장애이므로 500을 유지한다.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("INTERNAL_ERROR", DEFAULT_ERROR_MESSAGE));
    }

    private static String sqlStateOf(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    @ExceptionHandler(NumberFormatException.class)
    ResponseEntity<ApiErrorResponse> handleNumberFormat(NumberFormatException exception) {
        // 사용자 입력을 파싱하는 여러 지점(예산/수량 문자열 등)에서 비숫자·범위 초과 값이 오면
        // NumberFormatException으로 500이 났다. 입력 오류이므로 400으로 돌린다.
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse("VALIDATION_ERROR", VALIDATION_ERROR_MESSAGE, Map.of("reason", "INVALID_NUMBER")));
    }

    private Map<String, Object> detailsFor(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
            return fieldErrorDetails(methodArgumentNotValidException.getBindingResult().getFieldErrors());
        }
        if (exception instanceof BindException bindException) {
            return fieldErrorDetails(bindException.getBindingResult().getFieldErrors());
        }
        if (exception instanceof ConstraintViolationException constraintViolationException) {
            List<Map<String, Object>> errors = constraintViolationException.getConstraintViolations()
                    .stream()
                    .map(violation -> Map.<String, Object>of(
                            "field", violation.getPropertyPath().toString(),
                            "message", violation.getMessage()
                    ))
                    .toList();
            return errors.isEmpty() ? Map.of("reason", "CONSTRAINT_VIOLATION") : Map.of("errors", errors);
        }
        if (exception instanceof MissingServletRequestParameterException missingParameterException) {
            return Map.of(
                    "parameter", missingParameterException.getParameterName(),
                    "parameterType", missingParameterException.getParameterType(),
                    "errors", List.of(Map.of(
                            "field", missingParameterException.getParameterName(),
                            "message", "required"
                    ))
            );
        }
        if (exception instanceof MethodArgumentTypeMismatchException typeMismatchException) {
            return Map.of(
                    "parameter", typeMismatchException.getName(),
                    "requiredType", typeMismatchException.getRequiredType() == null
                            ? "unknown"
                            : typeMismatchException.getRequiredType().getSimpleName(),
                    "errors", List.of(Map.of(
                            "field", typeMismatchException.getName(),
                            "message", "type mismatch"
                    ))
            );
        }
        if (exception instanceof HttpMessageNotReadableException) {
            return Map.of("reason", "MALFORMED_JSON");
        }
        return Map.of("reason", exception.getClass().getSimpleName());
    }

    private Map<String, Object> fieldErrorDetails(List<FieldError> fieldErrors) {
        List<Map<String, Object>> errors = fieldErrors.stream()
                .map(error -> Map.<String, Object>of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()
                ))
                .toList();
        return errors.isEmpty() ? Map.of("reason", "VALIDATION_ERROR") : Map.of("errors", errors);
    }

    private String codeFor(HttpStatusCode statusCode) {
        if (statusCode.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            return "VALIDATION_ERROR";
        }
        if (statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            return "UNAUTHORIZED";
        }
        if (statusCode.isSameCodeAs(HttpStatus.FORBIDDEN)) {
            return "FORBIDDEN";
        }
        if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return "NOT_FOUND";
        }
        if (statusCode.isSameCodeAs(HttpStatus.CONFLICT)) {
            return "CONFLICT_STATE";
        }
        if (statusCode.isSameCodeAs(HttpStatus.PRECONDITION_REQUIRED)) {
            return "PRECONDITION_REQUIRED";
        }
        if (statusCode.isSameCodeAs(HttpStatus.BAD_GATEWAY)) {
            return "UPSTREAM_ERROR";
        }
        return "INTERNAL_ERROR";
    }
}
