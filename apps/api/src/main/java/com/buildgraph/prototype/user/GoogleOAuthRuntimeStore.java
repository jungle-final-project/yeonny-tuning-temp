package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GoogleOAuthRuntimeStore {
    private static final String STATE_PREFIX = "auth:google:state:";
    private static final String CODE_PREFIX = "auth:google:code:";
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GoogleOAuthProperties properties;
    private final SecureRandom secureRandom;

    @Autowired
    public GoogleOAuthRuntimeStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            GoogleOAuthProperties properties
    ) {
        this(redisTemplate, objectMapper, properties, new SecureRandom());
    }

    GoogleOAuthRuntimeStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            GoogleOAuthProperties properties,
            SecureRandom secureRandom
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.secureRandom = secureRandom;
    }

    public String createState(String redirectPath) {
        String state = randomToken();
        write(STATE_PREFIX + state, redirectPath, Duration.ofSeconds(properties.stateTtlSeconds()));
        return state;
    }

    public String consumeState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return readAndDelete(STATE_PREFIX + state);
    }

    public String createPendingLogin(GoogleOAuthPendingLogin pendingLogin) {
        String code = randomToken();
        try {
            write(CODE_PREFIX + code, objectMapper.writeValueAsString(pendingLogin), Duration.ofSeconds(properties.codeTtlSeconds()));
            return code;
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Google login session could not be saved.");
        }
    }

    public GoogleOAuthPendingLogin getPendingLogin(String code) {
        String payload = read(CODE_PREFIX + safeCode(code));
        return parsePendingLogin(payload);
    }

    public GoogleOAuthPendingLogin consumePendingLogin(String code) {
        String payload = readAndDelete(CODE_PREFIX + safeCode(code));
        return parsePendingLogin(payload);
    }

    private GoogleOAuthPendingLogin parsePendingLogin(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, GoogleOAuthPendingLogin.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google login session has expired.");
        }
    }

    private String safeCode(String code) {
        return code == null ? "" : code.trim();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String read(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (DataAccessException exception) {
            throw runtimeStoreUnavailable(exception);
        }
    }

    private String readAndDelete(String key) {
        try {
            return redisTemplate.opsForValue().getAndDelete(key);
        } catch (DataAccessException exception) {
            throw runtimeStoreUnavailable(exception);
        }
    }

    private void write(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (DataAccessException exception) {
            throw runtimeStoreUnavailable(exception);
        }
    }

    private ApiException runtimeStoreUnavailable(Exception exception) {
        return new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "PRECONDITION_REQUIRED",
                "Google OAuth runtime store is not available.",
                java.util.Map.of("reason", exception.getClass().getSimpleName())
        );
    }
}
