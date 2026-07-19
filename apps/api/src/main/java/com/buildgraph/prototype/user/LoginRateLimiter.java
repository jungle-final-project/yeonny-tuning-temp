package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.ApiException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LoginRateLimiter {
    private static final String UNKNOWN_CLIENT = "unknown";
    private static final String RATE_LIMIT_MESSAGE = "Too many login attempts. Please try again later.";

    private final boolean enabled;
    private final int maxAttemptsPerIp;
    private final int maxFailuresPerEmail;
    private final int maxFailuresPerIpEmail;
    private final long retryAfterSeconds;
    private final Cache<String, AtomicInteger> ipAttemptCounters;
    private final Cache<String, AtomicInteger> emailFailureCounters;
    private final Cache<String, AtomicInteger> ipEmailFailureCounters;
    private final Cache<String, Boolean> ipBlocks;
    private final Cache<String, Boolean> emailBlocks;
    private final Cache<String, Boolean> ipEmailBlocks;

    @Autowired
    public LoginRateLimiter(
            @Value("${buildgraph.auth.login-rate-limit.enabled:true}") boolean enabled,
            @Value("${buildgraph.auth.login-rate-limit.ip-window-seconds:60}") long ipWindowSeconds,
            @Value("${buildgraph.auth.login-rate-limit.max-attempts-per-ip:120}") int maxAttemptsPerIp,
            @Value("${buildgraph.auth.login-rate-limit.failure-window-seconds:300}") long failureWindowSeconds,
            @Value("${buildgraph.auth.login-rate-limit.max-failures-per-email:5}") int maxFailuresPerEmail,
            @Value("${buildgraph.auth.login-rate-limit.max-failures-per-ip-email:5}") int maxFailuresPerIpEmail,
            @Value("${buildgraph.auth.login-rate-limit.block-seconds:60}") long blockSeconds
    ) {
        this(
                enabled,
                Duration.ofSeconds(ipWindowSeconds),
                maxAttemptsPerIp,
                Duration.ofSeconds(failureWindowSeconds),
                maxFailuresPerEmail,
                maxFailuresPerIpEmail,
                Duration.ofSeconds(blockSeconds)
        );
    }

    LoginRateLimiter(
            boolean enabled,
            Duration ipWindow,
            int maxAttemptsPerIp,
            Duration failureWindow,
            int maxFailuresPerEmail,
            int maxFailuresPerIpEmail,
            Duration blockDuration
    ) {
        this.enabled = enabled;
        this.maxAttemptsPerIp = maxAttemptsPerIp;
        this.maxFailuresPerEmail = maxFailuresPerEmail;
        this.maxFailuresPerIpEmail = maxFailuresPerIpEmail;
        this.retryAfterSeconds = Math.max(1L, blockDuration.toSeconds());
        this.ipAttemptCounters = expiringCounterCache(ipWindow);
        this.emailFailureCounters = expiringCounterCache(failureWindow);
        this.ipEmailFailureCounters = expiringCounterCache(failureWindow);
        this.ipBlocks = expiringBlockCache(blockDuration);
        this.emailBlocks = expiringBlockCache(blockDuration);
        this.ipEmailBlocks = expiringBlockCache(blockDuration);
    }

    public void checkAllowed(String email, HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        checkAllowed(email, clientIp(request));
    }

    public void recordFailure(String email, HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        recordFailure(email, clientIp(request));
    }

    public void recordSuccess(String email, HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        recordSuccess(email, clientIp(request));
    }

    void checkAllowed(String email, String clientIp) {
        if (!enabled) {
            return;
        }
        String ipKey = clientIpKey(clientIp);
        String emailKey = emailKey(email);
        String pairKey = pairKey(ipKey, emailKey);

        if (Boolean.TRUE.equals(ipBlocks.getIfPresent(ipKey))) {
            throw rateLimited("ip");
        }
        if (Boolean.TRUE.equals(emailBlocks.getIfPresent(emailKey))) {
            throw rateLimited("email");
        }
        if (Boolean.TRUE.equals(ipEmailBlocks.getIfPresent(pairKey))) {
            throw rateLimited("ip_email");
        }
        if (maxAttemptsPerIp > 0 && increment(ipAttemptCounters, ipKey) > maxAttemptsPerIp) {
            ipBlocks.put(ipKey, Boolean.TRUE);
            throw rateLimited("ip");
        }
    }

    void recordFailure(String email, String clientIp) {
        if (!enabled) {
            return;
        }
        String ipKey = clientIpKey(clientIp);
        String emailKey = emailKey(email);
        String pairKey = pairKey(ipKey, emailKey);
        if (maxFailuresPerEmail > 0 && increment(emailFailureCounters, emailKey) >= maxFailuresPerEmail) {
            emailBlocks.put(emailKey, Boolean.TRUE);
        }
        if (maxFailuresPerIpEmail > 0 && increment(ipEmailFailureCounters, pairKey) >= maxFailuresPerIpEmail) {
            ipEmailBlocks.put(pairKey, Boolean.TRUE);
        }
    }

    void recordSuccess(String email, String clientIp) {
        if (!enabled) {
            return;
        }
        String ipKey = clientIpKey(clientIp);
        String emailKey = emailKey(email);
        String pairKey = pairKey(ipKey, emailKey);
        emailFailureCounters.invalidate(emailKey);
        ipEmailFailureCounters.invalidate(pairKey);
        emailBlocks.invalidate(emailKey);
        ipEmailBlocks.invalidate(pairKey);
    }

    private static Cache<String, AtomicInteger> expiringCounterCache(Duration duration) {
        return Caffeine.newBuilder()
                .expireAfterWrite(safeDuration(duration))
                .maximumSize(50_000)
                .build();
    }

    private static Cache<String, Boolean> expiringBlockCache(Duration duration) {
        return Caffeine.newBuilder()
                .expireAfterWrite(safeDuration(duration))
                .maximumSize(50_000)
                .build();
    }

    private static Duration safeDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return duration;
    }

    private static int increment(Cache<String, AtomicInteger> cache, String key) {
        return cache.get(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private ApiException rateLimited(String scope) {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                RATE_LIMIT_MESSAGE,
                Map.of(
                        "scope", scope,
                        "retryAfterSeconds", retryAfterSeconds
                )
        );
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN_CLIENT;
        }
        String forwardedFor = firstHeaderValue(request.getHeader("X-Forwarded-For"));
        if (hasText(forwardedFor)) {
            return forwardedFor;
        }
        String realIp = request.getHeader("X-Real-IP");
        if (hasText(realIp)) {
            return realIp.trim();
        }
        String remoteAddr = request.getRemoteAddr();
        return hasText(remoteAddr) ? remoteAddr.trim() : UNKNOWN_CLIENT;
    }

    private static String firstHeaderValue(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.split(",", 2)[0].trim();
    }

    private static String clientIpKey(String clientIp) {
        return hasText(clientIp) ? clientIp.trim() : UNKNOWN_CLIENT;
    }

    private static String emailKey(String email) {
        return hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : "unknown-email";
    }

    private static String pairKey(String ipKey, String emailKey) {
        return ipKey + "|" + emailKey;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
