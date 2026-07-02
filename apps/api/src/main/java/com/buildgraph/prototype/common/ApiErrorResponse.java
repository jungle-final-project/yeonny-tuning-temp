package com.buildgraph.prototype.common;

import java.util.Map;

public record ApiErrorResponse(String code, String message, Map<String, Object> details) {
    public ApiErrorResponse(String code, String message) {
        this(code, message, null);
    }

    public ApiErrorResponse {
        details = details == null || details.isEmpty() ? null : Map.copyOf(details);
    }
}
