package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class UserQueryServiceLoginTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final PasswordService passwordService = new PasswordService();
    private final CurrentUserService currentUserService = org.mockito.Mockito.mock(CurrentUserService.class);
    private final GoogleOAuthRuntimeStore googleOAuthRuntimeStore = org.mockito.Mockito.mock(GoogleOAuthRuntimeStore.class);
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            "test-buildgraph-jwt-secret-change-me-2026",
            "buildgraph-api-test",
            Duration.ofMinutes(15),
            Clock.fixed(Instant.parse("2026-06-29T09:00:00Z"), ZoneOffset.UTC)
    );
    private final RefreshTokenService refreshTokenService = new RefreshTokenService(
            new SecureRandom(),
            Duration.ofDays(30),
            Clock.fixed(Instant.parse("2026-06-29T09:00:00Z"), ZoneOffset.UTC)
    );
    private final UserQueryService userQueryService = new UserQueryService(
            jdbcTemplate,
            passwordService,
            jwtTokenService,
            currentUserService,
            refreshTokenService,
            googleOAuthRuntimeStore
    );

    @Test
    void loginReturnsAuthResponseWhenPasswordMatches() throws Exception {
        when(jdbcTemplate.queryForList(anyString(), anyString()))
                .thenReturn(List.of(userRow(passwordService.hash("passw0rd!"))));

        Map<String, Object> response = userQueryService.login("user@example.com", "passw0rd!");

        String accessToken = (String) response.get("accessToken");
        SignedJWT jwt = SignedJWT.parse(accessToken);

        assertThat(accessToken).doesNotStartWith("demo-access-");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("00000000-0000-4000-8000-000000001004");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("user@example.com");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("USER");
        String refreshToken = (String) response.get("refreshToken");
        assertThat(refreshToken).isNotBlank();
        assertThat(refreshToken).doesNotStartWith("demo-refresh-");
        verify(jdbcTemplate).update(
                anyString(),
                eq(1004L),
                eq(refreshTokenService.hash(refreshToken)),
                any(Timestamp.class)
        );
        assertThat(response.get("user")).isInstanceOf(Map.class);
    }

    @Test
    void loginRejectsWrongPassword() {
        when(jdbcTemplate.queryForList(anyString(), anyString()))
                .thenReturn(List.of(userRow(passwordService.hash("passw0rd!"))));

        assertThatThrownBy(() -> userQueryService.login("user@example.com", "wrong-password"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
    }

    @Test
    void loginRejectsMissingUser() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> userQueryService.login("missing@example.com", "passw0rd!"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
    }

    private Map<String, Object> userRow(String passwordHash) {
        return Map.of(
                "internal_id", 1004L,
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "password_hash", passwordHash,
                "name", "Demo User",
                "role", "USER"
        );
    }
}
