package com.buildgraph.prototype.common;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BuildGraphCorsProperties {
    private static final String DEFAULT_ALLOWED_ORIGINS = """
            http://localhost:5173,
            http://127.0.0.1:5173,
            http://localhost:5174,
            http://127.0.0.1:5174
            """;

    private final String[] allowedOrigins;

    public BuildGraphCorsProperties(@Value("${buildgraph.cors.allowed-origins:}") String allowedOrigins) {
        String configured = allowedOrigins == null || allowedOrigins.isBlank()
                ? DEFAULT_ALLOWED_ORIGINS
                : allowedOrigins;
        this.allowedOrigins = parseOrigins(configured);
    }

    public String[] allowedOrigins() {
        return allowedOrigins.clone();
    }

    private static String[] parseOrigins(String origins) {
        return Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }
}
