package com.buildgraph.prototype.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class BuildChatTestModeTest {

    @Test
    void acceptsMatchingMockHeadersWhenEnabled() {
        BuildChatTestMode mode = new BuildChatTestMode(true, "secret");

        assertTrue(mode.requireMockRequest("MOCK", "secret"));
    }

    @Test
    void rejectsInvalidTestKey() {
        BuildChatTestMode mode = new BuildChatTestMode(true, "secret");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> mode.requireMockRequest("MOCK", "wrong")
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void rejectsTestKeyWithoutModeHeader() {
        BuildChatTestMode mode = new BuildChatTestMode(true, "secret");

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> mode.requireMockRequest(null, "secret")
        );

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void stripsClientMarkerAndAddsItOnlyForVerifiedMockRequest() {
        Map<String, Object> spoofed = Map.of(
                "message", "hello",
                BuildChatTestMode.VERIFIED_MOCK_MARKER, true
        );

        Map<String, Object> normal = BuildChatTestMode.sanitizedRequest(spoofed, false);
        Map<String, Object> mock = BuildChatTestMode.sanitizedRequest(spoofed, true);

        assertFalse(BuildChatTestMode.isVerifiedMockRequest(normal));
        assertTrue(BuildChatTestMode.isVerifiedMockRequest(mock));
    }
}
