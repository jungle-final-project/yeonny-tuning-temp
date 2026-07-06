package com.buildgraph.prototype.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildGraphCorsPropertiesTest {
    @Test
    void defaultOriginsAreSharedByRestCorsAndWebSocket() {
        BuildGraphCorsProperties properties = new BuildGraphCorsProperties(null);

        assertThat(properties.allowedOrigins()).containsExactly(
                "http://localhost:5173",
                "http://127.0.0.1:5173",
                "http://localhost:5174",
                "http://127.0.0.1:5174"
        );
    }

    @Test
    void configuredOriginsAreTrimmedAndBlankEntriesIgnored() {
        BuildGraphCorsProperties properties = new BuildGraphCorsProperties(" https://app.example.com, ,https://admin.example.com ");

        assertThat(properties.allowedOrigins()).containsExactly(
                "https://app.example.com",
                "https://admin.example.com"
        );
    }
}
