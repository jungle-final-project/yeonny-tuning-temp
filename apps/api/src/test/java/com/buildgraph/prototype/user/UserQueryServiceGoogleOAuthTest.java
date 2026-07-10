package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
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

class UserQueryServiceGoogleOAuthTest {
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
            new java.security.SecureRandom(),
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
    void googleNewUserRequiresTermsBeforeCreatingUser() {
        GoogleOAuthPendingLogin pending = pending("New User", "New@Example.com");
        when(googleOAuthRuntimeStore.getPendingLogin("oauth-code")).thenReturn(pending);
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> userQueryService.exchangeGoogleLogin("oauth-code", false, false))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("reason", "TERMS_REQUIRED");
                    assertThat(exception.details()).containsEntry("email", "new@example.com");
                });

        verify(googleOAuthRuntimeStore, never()).consumePendingLogin(anyString());
        verify(jdbcTemplate, never()).queryForMap(anyString(), any(), any(), any());
    }

    @Test
    void googleNewUserCreatesUserRoleUserAfterTerms() {
        GoogleOAuthPendingLogin pending = pending("New User", "New@Example.com");
        when(googleOAuthRuntimeStore.getPendingLogin("oauth-code")).thenReturn(pending);
        when(googleOAuthRuntimeStore.consumePendingLogin("oauth-code")).thenReturn(pending);
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbcTemplate.queryForMap(anyString(), eq("new@example.com"), eq("New User"), eq(true)))
                .thenReturn(userRow(1004L, "new@example.com", "New User", "USER"));

        Map<String, Object> response = userQueryService.exchangeGoogleLogin("oauth-code", true, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) response.get("user");

        assertThat(user).containsEntry("email", "new@example.com");
        assertThat(user).containsEntry("role", "USER");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO user_auth_providers"),
                eq(1004L),
                eq("google-sub-1"),
                eq("new@example.com"),
                eq(true)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO refresh_tokens"),
                eq(1004L),
                anyString(),
                any(Timestamp.class)
        );
    }

    @Test
    void googleExistingAdminEmailKeepsAdminRole() {
        GoogleOAuthPendingLogin pending = pending("BuildGraph Admin", "Admin@Example.com");
        when(googleOAuthRuntimeStore.getPendingLogin("oauth-code")).thenReturn(pending);
        when(googleOAuthRuntimeStore.consumePendingLogin("oauth-code")).thenReturn(pending);
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object argument = invocation.getArgument(1);
            if (sql.contains("FROM user_auth_providers")) {
                return List.of();
            }
            if (sql.contains("WHERE email") && "admin@example.com".equals(argument)) {
                return List.of(userRow(1L, "admin@example.com", "BuildGraph Admin", "ADMIN"));
            }
            return List.of();
        });
        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(userRow(1L, "admin@example.com", "BuildGraph Admin", "ADMIN")));

        Map<String, Object> response = userQueryService.exchangeGoogleLogin("oauth-code", false, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) response.get("user");

        assertThat(user).containsEntry("email", "admin@example.com");
        assertThat(user).containsEntry("role", "ADMIN");
        verify(jdbcTemplate, never()).queryForMap(anyString(), any(), any(), any());
    }

    @Test
    void googleExpiredCodeFailsWithoutConsumingAgain() {
        when(googleOAuthRuntimeStore.getPendingLogin("expired-code")).thenReturn(null);

        assertThatThrownBy(() -> userQueryService.exchangeGoogleLogin("expired-code", true, false))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(googleOAuthRuntimeStore, never()).consumePendingLogin(anyString());
    }

    @Test
    void googleUnverifiedEmailFailsWithoutCreatingUser() {
        GoogleOAuthPendingLogin pending = new GoogleOAuthPendingLogin(
                "google-sub-1",
                "unverified@example.com",
                "Unverified User",
                false,
                "/"
        );
        when(googleOAuthRuntimeStore.getPendingLogin("oauth-code")).thenReturn(pending);

        assertThatThrownBy(() -> userQueryService.exchangeGoogleLogin("oauth-code", true, false))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(googleOAuthRuntimeStore, never()).consumePendingLogin(anyString());
        verify(jdbcTemplate, never()).queryForMap(anyString(), any(), any(), any());
    }

    private GoogleOAuthPendingLogin pending(String name, String email) {
        return new GoogleOAuthPendingLogin("google-sub-1", email, name, true, "/");
    }

    private Map<String, Object> userRow(Long internalId, String email, String name, String role) {
        return Map.of(
                "internal_id", internalId,
                "id", role.equals("ADMIN") ? "00000000-0000-4000-8000-000000000001" : "00000000-0000-4000-8000-000000001004",
                "email", email,
                "password_hash", "",
                "name", name,
                "role", role
        );
    }
}
