package com.buildgraph.prototype.config.security;

import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentTokenAuthenticationService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "UPDATE_REQUIRED");

    private final JdbcTemplate jdbcTemplate;
    private final AgentTokenHasher tokenHasher;

    public AgentTokenAuthenticationService(JdbcTemplate jdbcTemplate, AgentTokenHasher tokenHasher) {
        this.jdbcTemplate = jdbcTemplate;
        this.tokenHasher = tokenHasher;
    }

    public AgentTokenAuthenticationResult authenticate(String rawToken) {
        String tokenHash = tokenHasher.sha256Hex(rawToken);
        return jdbcTemplate.queryForList("""
                        SELECT id AS device_internal_id,
                               public_id::text AS device_id,
                               user_id,
                               status
                        FROM agent_devices
                        WHERE agent_token_hash = ?
                        """, tokenHash)
                .stream()
                .findFirst()
                .map(this::toResult)
                .orElseGet(AgentTokenAuthenticationResult::invalid);
    }

    private AgentTokenAuthenticationResult toResult(Map<String, Object> row) {
        String status = string(row, "status");
        if (!ALLOWED_STATUSES.contains(status)) {
            return AgentTokenAuthenticationResult.forbidden("Agent device is not active.");
        }
        return AgentTokenAuthenticationResult.authenticated(new AgentPrincipal(
                longValue(row, "device_internal_id"),
                string(row, "device_id"),
                longValue(row, "user_id"),
                status
        ));
    }

    private static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }
}
