package com.buildgraph.prototype.config.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

class AgentIdempotencyKeyExtractor {
    private static final String HEADER_NAME = "Idempotency-Key";
    private static final int MAX_LENGTH = 160;
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    AgentIdempotencyKey extract(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (header == null || header.isBlank()) {
            return AgentIdempotencyKey.missing();
        }
        String value = header.trim();
        if (value.length() > MAX_LENGTH || !KEY_PATTERN.matcher(value).matches()) {
            return AgentIdempotencyKey.invalid();
        }
        return AgentIdempotencyKey.valid(value);
    }

    record AgentIdempotencyKey(String value, Status status) {
        static AgentIdempotencyKey valid(String value) {
            return new AgentIdempotencyKey(value, Status.VALID);
        }

        static AgentIdempotencyKey missing() {
            return new AgentIdempotencyKey(null, Status.MISSING);
        }

        static AgentIdempotencyKey invalid() {
            return new AgentIdempotencyKey(null, Status.INVALID);
        }

        enum Status {
            VALID,
            MISSING,
            INVALID
        }
    }
}
