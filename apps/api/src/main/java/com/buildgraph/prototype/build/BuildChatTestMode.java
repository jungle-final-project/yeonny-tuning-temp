package com.buildgraph.prototype.build;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class BuildChatTestMode {
    static final String VERIFIED_MOCK_MARKER = "_buildGraphVerifiedMockRequest";

    private final boolean enabled;
    private final byte[] configuredKey;

    public BuildChatTestMode(
            @Value("${ai.build-chat.test-mode-enabled:false}") boolean enabled,
            @Value("${ai.build-chat.test-key:}") String configuredKey
    ) {
        this.enabled = enabled;
        this.configuredKey = bytes(configuredKey);
    }

    public boolean requireMockRequest(String requestedMode, String suppliedKey) {
        if (requestedMode == null || requestedMode.isBlank()) {
            if (suppliedKey != null && !suppliedKey.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI test mode header is required.");
            }
            return false;
        }
        if (!"MOCK".equalsIgnoreCase(requestedMode.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported AI test mode.");
        }
        if (!enabled || configuredKey.length == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI Build Chat test mode is disabled.");
        }
        byte[] supplied = bytes(suppliedKey);
        if (supplied.length == 0 || !MessageDigest.isEqual(configuredKey, supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid AI Build Chat test key.");
        }
        return true;
    }

    static Map<String, Object> sanitizedRequest(Map<String, Object> request, boolean mockRequest) {
        Map<String, Object> sanitized = new LinkedHashMap<>(request == null ? Map.of() : request);
        sanitized.remove(VERIFIED_MOCK_MARKER);
        if (mockRequest) {
            sanitized.put(VERIFIED_MOCK_MARKER, true);
        }
        return sanitized;
    }

    static boolean isVerifiedMockRequest(Map<String, Object> request) {
        return request != null && Boolean.TRUE.equals(request.get(VERIFIED_MOCK_MARKER));
    }

    private static byte[] bytes(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        return value.trim().getBytes(StandardCharsets.UTF_8);
    }
}
