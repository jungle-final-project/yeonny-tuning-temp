package com.buildgraph.prototype.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        HttpStatusCode statusCode = exception.getStatusCode();
        String code = statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED)
                ? "UNAUTHORIZED"
                : statusCode.isSameCodeAs(HttpStatus.FORBIDDEN) ? "FORBIDDEN" : "API_ERROR";
        String message = exception.getReason() == null ? "요청을 처리할 수 없습니다." : exception.getReason();
        return ResponseEntity.status(statusCode).body(new ApiErrorResponse(code, message));
    }
}
