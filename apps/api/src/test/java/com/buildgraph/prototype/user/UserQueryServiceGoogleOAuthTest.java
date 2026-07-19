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

        assertThatThrownBy(() -> userQueryService.exchangeGoogleLogin("oauth-code", false, false, null, null, null, null))
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
        when(jdbcTemplate.queryForMap(
                anyString(),
                eq("new@example.com"),
                eq("New User"),
                eq("010-1234-5678"),
                eq("06236"),
                eq("서울시 강남구 테헤란로 1"),
                eq("101호"),
                eq(true)
        ))
                .thenReturn(userRowWithContact(1004L, "new@example.com", "New User", "USER"));

        Map<String, Object> response = userQueryService.exchangeGoogleLogin(
                "oauth-code",
                true,
                true,
                "01012345678",
                " 06236 ",
                "서울시   강남구   테헤란로 1",
                " 101호 "
        );
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
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("active_rank > ?"),
                eq(1004L),
                eq(3)
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
            if (sql.contains("WHERE p.provider = 'GOOGLE'")) {
                return List.of();
            }
            if ((sql.contains("WHERE email") || sql.contains("WHERE u.email")) && "admin@example.com".equals(argument)) {
                return List.of(userRow(1L, "admin@example.com", "BuildGraph Admin", "ADMIN"));
            }
            return List.of();
        });
        when(jdbcTemplate.queryForList(anyString(), eq(1L))).thenReturn(List.of(userRow(1L, "admin@example.com", "BuildGraph Admin", "ADMIN")));

        Map<String, Object> response = userQueryService.exchangeGoogleLogin("oauth-code", false, false, null, null, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) response.get("user");

        assertThat(user).containsEntry("email", "admin@example.com");
        assertThat(user).containsEntry("role", "ADMIN");
        verify(jdbcTemplate, never()).queryForMap(anyString(), any(), any(), any());
    }

    @Test
    void googleExistingUserMissingContactRequiresContactBeforeConsumingCode() {
        GoogleOAuthPendingLogin pending = pending("Demo User", "User@Example.com");
        when(googleOAuthRuntimeStore.getPendingLogin("oauth-code")).thenReturn(pending);
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object argument = invocation.getArgument(1);
            if (sql.contains("WHERE p.provider = 'GOOGLE'") && "google-sub-1".equals(argument)) {
                return List.of(userRow(1004L, "user@example.com", "Demo User", "USER"));
            }
            return List.of();
        });

        assertThatThrownBy(() -> userQueryService.exchangeGoogleLogin("oauth-code", false, false, null, null, null, null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("reason", "CONTACT_REQUIRED");
                    assertThat(exception.details()).containsEntry("email", "user@example.com");
                });

        verify(googleOAuthRuntimeStore, never()).consumePendingLogin(anyString());
    }

    @Test
    void googleExistingUserMissingContactCanCompleteContactAndLogin() {
        GoogleOAuthPendingLogin pending = pending("Demo User", "User@Example.com");
        when(googleOAuthRuntimeStore.getPendingLogin("oauth-code")).thenReturn(pending);
        when(googleOAuthRuntimeStore.consumePendingLogin("oauth-code")).thenReturn(pending);
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object argument = invocation.getArgument(1);
            if (sql.contains("WHERE p.provider = 'GOOGLE'") && "google-sub-1".equals(argument)) {
                return List.of(userRow(1004L, "user@example.com", "Demo User", "USER"));
            }
            return List.of();
        });
        when(jdbcTemplate.queryForList(anyString(), eq(1004L)))
                .thenReturn(List.of(userRow(1004L, "user@example.com", "Demo User", "USER")));

        Map<String, Object> response = userQueryService.exchangeGoogleLogin(
                "oauth-code",
                false,
                false,
                "01012345678",
                " 06236 ",
                "서울시   강남구   테헤란로 1",
                " 101호 "
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) response.get("user");

        assertThat(user).containsEntry("email", "user@example.com");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("UPDATE users"),
                eq("010-1234-5678"),
                eq("06236"),
                eq("서울시 강남구 테헤란로 1"),
                eq("101호"),
                eq(1004L)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO refresh_tokens"),
                eq(1004L),
                anyString(),
                any(Timestamp.class)
        );
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("active_rank > ?"),
                eq(1004L),
                eq(3)
        );
    }

    @Test
    void googleProfileVerificationRedirectReturnsProfileVerificationToken() {
        GoogleOAuthPendingLogin pending = new GoogleOAuthPendingLogin(
                "google-sub-1",
                "User@Example.com",
                "Demo User",
                true,
                "/my/profile?verified=google"
        );
        when(googleOAuthRuntimeStore.getPendingLogin("oauth-code")).thenReturn(pending);
        when(googleOAuthRuntimeStore.consumePendingLogin("oauth-code")).thenReturn(pending);
        when(googleOAuthRuntimeStore.createProfileVerificationToken(
                "00000000-0000-4000-8000-000000001004",
                "google-sub-1"
        )).thenReturn("profile-token");
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Object argument = invocation.getArgument(1);
            if (sql.contains("WHERE p.provider = 'GOOGLE'") && "google-sub-1".equals(argument)) {
                return List.of(userRowWithContact(1004L, "user@example.com", "Demo User", "USER"));
            }
            return List.of();
        });
        when(jdbcTemplate.queryForList(anyString(), eq(1004L)))
                .thenReturn(List.of(Map.of("provider", "GOOGLE")));

        Map<String, Object> response = userQueryService.exchangeGoogleLogin("oauth-code", false, false, null, null, null, null);

        assertThat(response).containsEntry("profileVerificationToken", "profile-token");
        verify(googleOAuthRuntimeStore).createProfileVerificationToken(
                "00000000-0000-4000-8000-000000001004",
                "google-sub-1"
        );
    }

    @Test
    void googleExpiredCodeFailsWithoutConsumingAgain() {
        when(googleOAuthRuntimeStore.getPendingLogin("expired-code")).thenReturn(null);

        assertThatThrownBy(() -> userQueryService.exchangeGoogleLogin("expired-code", true, false, null, null, null, null))
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

        assertThatThrownBy(() -> userQueryService.exchangeGoogleLogin("oauth-code", true, false, null, null, null, null))
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

    private Map<String, Object> userRowWithContact(Long internalId, String email, String name, String role) {
        return Map.of(
                "internal_id", internalId,
                "id", role.equals("ADMIN") ? "00000000-0000-4000-8000-000000000001" : "00000000-0000-4000-8000-000000001004",
                "email", email,
                "password_hash", "",
                "name", name,
                "role", role,
                "phone_number", "010-1234-5678",
                "postal_code", "06236",
                "address_line1", "서울시 강남구 테헤란로 1",
                "address_line2", "101호"
        );
    }
}
