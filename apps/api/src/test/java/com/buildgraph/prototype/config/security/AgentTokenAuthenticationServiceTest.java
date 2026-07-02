package com.buildgraph.prototype.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentTokenAuthenticationServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AgentTokenHasher tokenHasher = new AgentTokenHasher();
    private final AgentTokenAuthenticationService service = new AgentTokenAuthenticationService(jdbcTemplate, tokenHasher);

    @Test
    void authenticateHashesRawAgentTokenBeforeLookup() {
        String rawToken = "agent-token";
        String tokenHash = tokenHasher.sha256Hex(rawToken);
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq(tokenHash)))
                .thenReturn(List.of(row("ACTIVE")));

        AgentTokenAuthenticationResult result = service.authenticate(rawToken);

        assertThat(result.status()).isEqualTo(AgentTokenAuthenticationResult.Status.AUTHENTICATED);
        assertThat(result.principal()).isPresent();
        assertThat(result.principal().orElseThrow().deviceId()).isEqualTo("device-public-id");
    }

    @Test
    void authenticateRejectsUnknownTokenAsUnauthorized() {
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq(tokenHasher.sha256Hex("missing-token"))))
                .thenReturn(List.of());

        AgentTokenAuthenticationResult result = service.authenticate("missing-token");

        assertThat(result.status()).isEqualTo(AgentTokenAuthenticationResult.Status.INVALID);
    }

    @Test
    void authenticateAllowsUpdateRequiredDeviceToReachUpdatePolicyAndHeartbeat() {
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq(tokenHasher.sha256Hex("update-token"))))
                .thenReturn(List.of(row("UPDATE_REQUIRED")));

        AgentTokenAuthenticationResult result = service.authenticate("update-token");

        assertThat(result.status()).isEqualTo(AgentTokenAuthenticationResult.Status.AUTHENTICATED);
        assertThat(result.principal().map(AgentPrincipal::status)).hasValue("UPDATE_REQUIRED");
    }

    @Test
    void authenticateRejectsBlockedOrRevokedDeviceAsForbidden() {
        when(jdbcTemplate.queryForList(contains("FROM agent_devices"), eq(tokenHasher.sha256Hex("blocked-token"))))
                .thenReturn(List.of(row("BLOCKED")));

        AgentTokenAuthenticationResult result = service.authenticate("blocked-token");

        assertThat(result.status()).isEqualTo(AgentTokenAuthenticationResult.Status.FORBIDDEN);
    }

    private static Map<String, Object> row(String status) {
        return Map.of(
                "device_internal_id", 10L,
                "device_id", "device-public-id",
                "user_id", 20L,
                "status", status
        );
    }
}
