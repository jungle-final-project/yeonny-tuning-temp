package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

class UserQueryServiceRefreshTest {
    private static final Instant NOW = Instant.parse("2026-06-29T09:00:00Z");
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final PasswordService passwordService = new PasswordService();
    private final CurrentUserService currentUserService = org.mockito.Mockito.mock(CurrentUserService.class);
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            "test-buildgraph-jwt-secret-change-me-2026",
            "buildgraph-api-test",
            Duration.ofMinutes(15),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final RefreshTokenService refreshTokenService = new RefreshTokenService(
            new SecureRandom(),
            Duration.ofDays(30),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final UserQueryService userQueryService = new UserQueryService(
            jdbcTemplate,
            passwordService,
            jwtTokenService,
            currentUserService,
            refreshTokenService
    );

    @Test
    void refreshRotatesRefreshTokenAndReturnsNewTokens() throws Exception {
        String oldRefreshToken = "old-refresh-token";
        when(jdbcTemplate.queryForList(anyString(), eq(refreshTokenService.hash(oldRefreshToken))))
                .thenReturn(List.of(refreshTokenRow()));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L)))
                .thenReturn(List.of(userRow()));

        Map<String, Object> response = userQueryService.refresh(oldRefreshToken);

        String accessToken = (String) response.get("accessToken");
        String nextRefreshToken = (String) response.get("refreshToken");
        SignedJWT jwt = SignedJWT.parse(accessToken);

        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("00000000-0000-4000-8000-000000001004");
        assertThat(nextRefreshToken).isNotBlank();
        assertThat(nextRefreshToken).isNotEqualTo(oldRefreshToken);
        verify(jdbcTemplate).update(
                anyString(),
                eq(9001L)
        );
        verify(jdbcTemplate).update(
                anyString(),
                eq(1004L),
                eq(refreshTokenService.hash(nextRefreshToken)),
                any(Timestamp.class)
        );
    }

    @Test
    void refreshRejectsBlankToken() {
        assertThatThrownBy(() -> userQueryService.refresh(" "))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void refreshRejectsMissingTokenRow() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> userQueryService.refresh("missing-refresh-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
    }

    @Test
    void logoutRevokesRefreshToken() {
        String refreshToken = "logout-refresh-token";
        when(currentUserService.requireUser("Bearer jwt-access-token")).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq(refreshTokenService.hash(refreshToken))))
                .thenReturn(List.of(refreshTokenRow()));

        userQueryService.logout("Bearer jwt-access-token", refreshToken);

        verify(currentUserService).requireUser("Bearer jwt-access-token");
        verify(jdbcTemplate).update(
                anyString(),
                eq(9001L)
        );
    }

    @Test
    void logoutRequiresUserBeforeRevokingRefreshToken() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required."));

        assertThatThrownBy(() -> userQueryService.logout(null, "logout-refresh-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void logoutRejectsBlankRefreshToken() {
        when(currentUserService.requireUser("Bearer jwt-access-token")).thenReturn(currentUser());

        assertThatThrownBy(() -> userQueryService.logout("Bearer jwt-access-token", " "))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
        verifyNoInteractions(jdbcTemplate);
    }

    private CurrentUserService.CurrentUser currentUser() {
        return new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Demo User",
                "USER",
                NOW
        );
    }

    private Map<String, Object> refreshTokenRow() {
        return Map.of(
                "id", 9001L,
                "user_id", 1004L,
                "token_hash", "old-refresh-token-hash"
        );
    }

    private Map<String, Object> userRow() {
        return Map.of(
                "internal_id", 1004L,
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER"
        );
    }
}
