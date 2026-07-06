package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

class SupportChatWebSocketTicketServiceTest {
    private static final String ROOM_ID = "00000000-0000-4000-8000-000000009001";
    private static final Instant NOW = Instant.parse("2026-07-06T10:00:00Z");
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );
    private static final CurrentUserService.CurrentUser ADMIN = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000000001",
            "admin@example.com",
            "BuildGraph Admin",
            "ADMIN",
            null
    );

    private final SupportChatService supportChatService = mock(SupportChatService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> operations = mock(ValueOperations.class);
    private final SupportChatWebSocketTicketService service = new SupportChatWebSocketTicketService(
            provider,
            supportChatService,
            Clock.fixed(NOW, ZoneOffset.UTC),
            60
    );

    @Test
    void userTicketIsIssuedWithTtlAfterAccessCheck() {
        redisAvailable();
        when(supportChatService.userCanAccess(ROOM_ID, USER)).thenReturn(true);

        Map<String, Object> response = service.issueUserTicket(ROOM_ID, USER);

        assertThat(response.get("ticket")).isInstanceOf(String.class);
        assertThat(response.get("expiresAt")).isEqualTo("2026-07-06T10:01:00Z");
        assertThat(response.get("expiresInSeconds")).isEqualTo(60L);
        verify(operations).set(
                eq("support-chat:ws-ticket:" + response.get("ticket")),
                anyString(),
                eq(Duration.ofSeconds(60))
        );
    }

    @Test
    void adminTicketIsIssuedWithModeAndRoomScope() {
        redisAvailable();
        when(supportChatService.adminCanAccess(ROOM_ID)).thenReturn(true);

        Map<String, Object> response = service.issueAdminTicket(ROOM_ID, ADMIN);

        assertThat(response.get("ticket")).isInstanceOf(String.class);
        verify(operations).set(
                eq("support-chat:ws-ticket:" + response.get("ticket")),
                org.mockito.ArgumentMatchers.contains("\"mode\":\"admin\""),
                eq(Duration.ofSeconds(60))
        );
        verify(operations).set(
                eq("support-chat:ws-ticket:" + response.get("ticket")),
                org.mockito.ArgumentMatchers.contains("\"sessionId\":\"" + ROOM_ID + "\""),
                eq(Duration.ofSeconds(60))
        );
    }

    @Test
    void inaccessibleRoomDoesNotIssueTicket() {
        redisAvailable();
        when(supportChatService.userCanAccess(ROOM_ID, USER)).thenReturn(false);

        assertThatThrownBy(() -> service.issueUserTicket(ROOM_ID, USER))
                .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void redisFailureDuringIssueReturnsUpstreamError() {
        redisAvailable();
        when(supportChatService.userCanAccess(ROOM_ID, USER)).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(operations).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> service.issueUserTicket(ROOM_ID, USER))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("UPSTREAM_ERROR");
                });
    }

    @Test
    void ticketIsConsumedOnce() {
        redisAvailable();
        String payload = """
                {
                  "mode": "user",
                  "sessionId": "%s",
                  "user": {
                    "internalId": 1004,
                    "id": "00000000-0000-4000-8000-000000001004",
                    "email": "user@example.com",
                    "name": "Demo User",
                    "role": "USER",
                    "createdAt": null
                  }
                }
                """.formatted(ROOM_ID);
        when(operations.getAndDelete("support-chat:ws-ticket:ticket-1")).thenReturn(payload).thenReturn(null);

        Optional<SupportChatWebSocketTicketService.AuthenticatedTicket> first = service.consume("ticket-1");
        Optional<SupportChatWebSocketTicketService.AuthenticatedTicket> second = service.consume("ticket-1");

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().mode()).isEqualTo("user");
        assertThat(first.orElseThrow().sessionId()).isEqualTo(ROOM_ID);
        assertThat(first.orElseThrow().user().internalId()).isEqualTo(1004L);
        assertThat(second).isEmpty();
    }

    private void redisAvailable() {
        when(provider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(operations);
    }
}
