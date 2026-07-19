package com.buildgraph.prototype.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenMaintenanceService {
    private final JdbcTemplate jdbcTemplate;
    private final boolean cleanupEnabled;
    private final boolean cleanupOnStartup;
    private final int maxActivePerUser;

    public RefreshTokenMaintenanceService(
            JdbcTemplate jdbcTemplate,
            @Value("${buildgraph.auth.refresh-token-cleanup.enabled:true}") boolean cleanupEnabled,
            @Value("${buildgraph.auth.refresh-token-cleanup.run-on-startup:true}") boolean cleanupOnStartup,
            @Value("${buildgraph.auth.refresh-token-cleanup.max-active-per-user:3}") int maxActivePerUser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.cleanupEnabled = cleanupEnabled;
        this.cleanupOnStartup = cleanupOnStartup;
        this.maxActivePerUser = Math.max(1, maxActivePerUser);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupRefreshTokensOnStartup() {
        if (!cleanupOnStartup) {
            return;
        }
        cleanupRefreshTokens();
    }

    @Scheduled(fixedDelayString = "${buildgraph.auth.refresh-token-cleanup.fixed-delay-ms:3600000}")
    public void cleanupRefreshTokens() {
        if (!cleanupEnabled) {
            return;
        }
        revokeExpiredRefreshTokens();
        revokeExcessActiveRefreshTokens();
    }

    int revokeExpiredRefreshTokens() {
        return jdbcTemplate.update("""
                UPDATE refresh_tokens
                SET revoked_at = now()
                WHERE revoked_at IS NULL
                  AND expires_at <= now()
                """);
    }

    int revokeExcessActiveRefreshTokens() {
        return jdbcTemplate.update("""
                WITH ranked AS (
                  SELECT id,
                         row_number() OVER (
                           PARTITION BY user_id
                           ORDER BY created_at DESC, id DESC
                         ) AS active_rank
                  FROM refresh_tokens
                  WHERE revoked_at IS NULL
                    AND expires_at > now()
                )
                UPDATE refresh_tokens
                SET revoked_at = now()
                WHERE id IN (
                  SELECT id
                  FROM ranked
                  WHERE active_rank > ?
                )
                """, maxActivePerUser);
    }
}
