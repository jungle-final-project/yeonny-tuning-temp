package com.buildgraph.prototype.common;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
                .body(new ApiErrorResponse(exception.code(), exception.getMessage()));
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
                .body(new ApiErrorResponse("VALIDATION_ERROR", VALIDATION_ERROR_MESSAGE));
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
