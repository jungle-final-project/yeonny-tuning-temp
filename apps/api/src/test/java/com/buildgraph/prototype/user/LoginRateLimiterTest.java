package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buildgraph.prototype.common.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class LoginRateLimiterTest {
    @Test
    void ipBurstLimitRejectsBeforePasswordVerification() {
        LoginRateLimiter limiter = newLimiter(2, 5, 5);

        limiter.checkAllowed("user@example.com", "203.0.113.10");
        limiter.checkAllowed("other@example.com", "203.0.113.10");

        assertThatThrownBy(() -> limiter.checkAllowed("third@example.com", "203.0.113.10"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.code()).isEqualTo("RATE_LIMITED");
                    assertThat(exception.details()).containsEntry("scope", "ip");
                });
    }

    @Test
    void emailFailureLimitRejectsNextAttemptBeforePasswordVerification() {
        LoginRateLimiter limiter = newLimiter(100, 2, 5);

        limiter.recordFailure("User@Example.com", "203.0.113.10");
        limiter.recordFailure("user@example.com", "203.0.113.11");

        assertThatThrownBy(() -> limiter.checkAllowed("USER@example.com", "203.0.113.12"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.details()).containsEntry("scope", "email")
                );
    }

    @Test
    void ipEmailFailureLimitRejectsOnlySamePair() {
        LoginRateLimiter limiter = newLimiter(100, 10, 2);

        limiter.recordFailure("user@example.com", "203.0.113.10");
        limiter.recordFailure("user@example.com", "203.0.113.10");

        assertThatThrownBy(() -> limiter.checkAllowed("user@example.com", "203.0.113.10"))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.details()).containsEntry("scope", "ip_email")
                );
        assertThatCode(() -> limiter.checkAllowed("user@example.com", "203.0.113.11"))
                .doesNotThrowAnyException();
    }

    @Test
    void successfulLoginClearsFailureCounters() {
        LoginRateLimiter limiter = newLimiter(100, 2, 2);

        limiter.recordFailure("user@example.com", "203.0.113.10");
        limiter.recordSuccess("user@example.com", "203.0.113.10");
        limiter.recordFailure("user@example.com", "203.0.113.10");

        assertThatCode(() -> limiter.checkAllowed("user@example.com", "203.0.113.10"))
                .doesNotThrowAnyException();
    }

    private static LoginRateLimiter newLimiter(
            int maxAttemptsPerIp,
            int maxFailuresPerEmail,
            int maxFailuresPerIpEmail
    ) {
        return new LoginRateLimiter(
                true,
                Duration.ofMinutes(1),
                maxAttemptsPerIp,
                Duration.ofMinutes(5),
                maxFailuresPerEmail,
                maxFailuresPerIpEmail,
                Duration.ofSeconds(60)
        );
    }
}
