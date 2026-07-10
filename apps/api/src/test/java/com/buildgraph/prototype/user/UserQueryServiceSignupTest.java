package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class UserQueryServiceSignupTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final PasswordService passwordService = new PasswordService();
    private final CurrentUserService currentUserService = org.mockito.Mockito.mock(CurrentUserService.class);
    private final GoogleOAuthRuntimeStore googleOAuthRuntimeStore = org.mockito.Mockito.mock(GoogleOAuthRuntimeStore.class);
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            "test-buildgraph-jwt-secret-change-me-2026",
            "buildgraph-api-test",
            java.time.Duration.ofMinutes(15),
            java.time.Clock.systemUTC()
    );
    private final RefreshTokenService refreshTokenService = new RefreshTokenService(
            new java.security.SecureRandom(),
            java.time.Duration.ofDays(30),
            java.time.Clock.systemUTC()
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
    void signupStoresPasswordHash() {
        AtomicReference<String> storedPasswordHash = new AtomicReference<>();
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbcTemplate.queryForMap(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> {
                    storedPasswordHash.set(invocation.getArgument(2));
                    return userRow();
                });

        Map<String, Object> user = userQueryService.signup("홍길동", "user@example.com", "passw0rd!", true, false);

        assertThat(user).containsEntry("email", "user@example.com");
        assertThat(storedPasswordHash.get()).isNotEqualTo("passw0rd!");
        assertThat(passwordService.matches("passw0rd!", storedPasswordHash.get())).isTrue();
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(userRow()));

        assertThatThrownBy(() -> userQueryService.signup("홍길동", "user@example.com", "passw0rd!", true, false))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("DUPLICATE_RESOURCE");
                });
        verify(jdbcTemplate).queryForList(anyString(), anyString());
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void signupRequiresTermsAccepted() {
        assertThatThrownBy(() -> userQueryService.signup("홍길동", "user@example.com", "passw0rd!", false, false))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                });
        verifyNoInteractions(jdbcTemplate);
    }

    private Map<String, Object> userRow() {
        return Map.of(
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "홍길동",
                "role", "USER"
        );
    }
}
