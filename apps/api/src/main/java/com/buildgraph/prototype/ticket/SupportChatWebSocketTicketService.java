package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SupportChatWebSocketTicketService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String KEY_PREFIX = "support-chat:ws-ticket:";

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final SupportChatService supportChatService;
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public SupportChatWebSocketTicketService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            SupportChatService supportChatService,
            @Value("${support-chat.ws-ticket.ttl-seconds:60}") long ttlSeconds
    ) {
        this(redisTemplateProvider, supportChatService, Clock.systemUTC(), ttlSeconds);
    }

    SupportChatWebSocketTicketService(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            SupportChatService supportChatService,
            Clock clock,
            long ttlSeconds
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.supportChatService = supportChatService;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    public Map<String, Object> issueUserTicket(String roomId, CurrentUserService.CurrentUser user) {
        if (!supportChatService.userCanAccess(roomId, user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상담방을 찾을 수 없습니다.");
        }
        return issue("user", roomId, user);
    }

    public Map<String, Object> issueAdminTicket(String roomId, CurrentUserService.CurrentUser admin) {
        if (!supportChatService.adminCanAccess(roomId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상담방을 찾을 수 없습니다.");
        }
        return issue("admin", roomId, admin);
    }

    public Optional<AuthenticatedTicket> consume(String ticket) {
        String token = stringOrNull(ticket);
        if (token == null) {
            return Optional.empty();
        }
        try {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                return Optional.empty();
            }
            String payload = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token);
            if (payload == null || payload.isBlank()) {
                return Optional.empty();
            }
            return parseTicket(payload);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Map<String, Object> issue(String mode, String roomId, CurrentUserService.CurrentUser user) {
        String ticket = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(ttl);
        Map<String, Object> payload = MockData.map(
                "mode", mode,
                "sessionId", roomId,
                "user", MockData.map(
                        "internalId", user.internalId(),
                        "id", user.id(),
                        "email", user.email(),
                        "name", user.name(),
                        "role", user.role(),
                        "createdAt", user.createdAt()
                )
        );
        try {
            redis().opsForValue().set(KEY_PREFIX + ticket, OBJECT_MAPPER.writeValueAsString(payload), ttl);
        } catch (Exception error) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "UPSTREAM_ERROR",
                    "실시간 상담 연결 티켓을 발급할 수 없습니다."
            );
        }
        return MockData.map(
                "ticket", ticket,
                "expiresAt", expiresAt.toString(),
                "expiresInSeconds", ttl.toSeconds()
        );
    }

    private StringRedisTemplate redis() {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "UPSTREAM_ERROR",
                    "실시간 상담 연결 티켓 저장소에 연결할 수 없습니다."
            );
        }
        return redisTemplate;
    }

    private static Optional<AuthenticatedTicket> parseTicket(String payload) throws Exception {
        Map<String, Object> data = OBJECT_MAPPER.readValue(payload, MAP_TYPE);
        Map<String, Object> userMap = objectMap(data.get("user"));
        String mode = stringOrNull(data.get("mode"));
        String sessionId = stringOrNull(data.get("sessionId"));
        String userId = stringOrNull(userMap.get("id"));
        String email = stringOrNull(userMap.get("email"));
        String name = stringOrNull(userMap.get("name"));
        String role = stringOrNull(userMap.get("role"));
        Long internalId = longOrNull(userMap.get("internalId"));
        if (mode == null || sessionId == null || userId == null || email == null || role == null || internalId == null) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedTicket(
                mode,
                sessionId,
                new CurrentUserService.CurrentUser(
                        internalId,
                        userId,
                        email,
                        name,
                        role,
                        userMap.get("createdAt")
                )
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Long longOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    public record AuthenticatedTicket(
            String mode,
            String sessionId,
            CurrentUserService.CurrentUser user
    ) {
    }
}
