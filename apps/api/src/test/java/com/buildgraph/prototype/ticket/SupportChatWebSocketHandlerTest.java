package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class SupportChatWebSocketHandlerTest {
    private static final String ROOM_ID = "00000000-0000-4000-8000-000000009001";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    private final SupportChatService supportChatService = mock(SupportChatService.class);
    private final SupportChatWebSocketTicketService ticketService = mock(SupportChatWebSocketTicketService.class);
    private final SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler(
            supportChatService,
            ticketService
    );

    @Test
    void inboundMessageFrameIsRejectedWithoutPersistingMessageAfterAuth() throws Exception {
        WebSocketSession session = authenticatedSession("user");

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "MESSAGE",
                  "content": "지금 상담 가능할까요?"
                }
                """))).doesNotThrowAnyException();

        verify(supportChatService, never()).postUserMessage(any(), any(), any());
        verify(supportChatService, never()).postAdminMessage(any(), any(), any());
        assertSentFrame(session, "ERROR", "WS_MESSAGE_DISABLED");
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void malformedPayloadReturnsErrorFrameWithoutThrowingAfterAuth() throws Exception {
        WebSocketSession session = authenticatedSession("user");

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("{not-json")))
                .doesNotThrowAnyException();

        verify(supportChatService, never()).postUserMessage(any(), any(), any());
        verify(supportChatService, never()).postAdminMessage(any(), any(), any());
        assertSentFrame(session, "ERROR", "INVALID_WS_PAYLOAD");
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void unknownPayloadTypeReturnsErrorFrameWithoutThrowingAfterAuth() throws Exception {
        WebSocketSession session = authenticatedSession("admin");

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "PING"
                }
                """))).doesNotThrowAnyException();

        verify(supportChatService, never()).postUserMessage(any(), any(), any());
        verify(supportChatService, never()).postAdminMessage(any(), any(), any());
        assertSentFrame(session, "ERROR", "INVALID_WS_PAYLOAD");
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void missingPayloadTypeRequiresAuthAndClosesBeforeAuth() throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession("user");
        handler.afterConnectionEstablished(session);

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("""
                {
                  "content": "type 필드가 없습니다."
                }
                """))).doesNotThrowAnyException();

        verify(supportChatService, never()).postUserMessage(any(), any(), any());
        verify(supportChatService, never()).postAdminMessage(any(), any(), any());
        assertSentFrame(session, "ERROR", "WS_AUTH_REQUIRED");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void malformedPayloadClosesBeforeAuth() throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession("user");
        handler.afterConnectionEstablished(session);

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("{not-json")))
                .doesNotThrowAnyException();

        assertSentFrame(session, "ERROR", "INVALID_WS_PAYLOAD");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void authFrameRegistersSessionAndSendsInitialUnreadSafeSnapshot() throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession("user");
        when(ticketService.consume("ticket-1")).thenReturn(Optional.of(new SupportChatWebSocketTicketService.AuthenticatedTicket(
                "user",
                ROOM_ID,
                USER
        )));
        when(supportChatService.userCanAccess(ROOM_ID, USER)).thenReturn(true);
        when(supportChatService.detailSnapshot(ROOM_ID, USER)).thenReturn(MockData.map(
                "contact", MockData.map("id", ROOM_ID, "userUnreadCount", 2),
                "messages", List.of(),
                "pollingIntervalMs", 5000
        ));

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "AUTH",
                  "ticket": "ticket-1"
                }
                """));
        handler.broadcastRoomUpdate(ROOM_ID);

        verify(supportChatService, times(2)).detailSnapshot(ROOM_ID, USER);
        verify(supportChatService, never()).detail(ROOM_ID, USER);
        assertThat(session.getAttributes()).containsEntry("authenticated", true);
        assertSentFrame(session, "CHAT_UPDATED", null);
    }

    @Test
    void invalidTicketReturnsErrorAndCloses() throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession("user");
        when(ticketService.consume("expired-ticket")).thenReturn(Optional.empty());

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "AUTH",
                  "ticket": "expired-ticket"
                }
                """));

        assertSentFrame(session, "ERROR", "INVALID_WS_TICKET");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void mismatchedTicketReturnsErrorAndCloses() throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession("user");
        when(ticketService.consume("admin-ticket")).thenReturn(Optional.of(new SupportChatWebSocketTicketService.AuthenticatedTicket(
                "admin",
                ROOM_ID,
                USER
        )));

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "AUTH",
                  "ticket": "admin-ticket"
                }
                """));

        assertSentFrame(session, "ERROR", "INVALID_WS_TICKET");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void broadcastKeepsOtherSessionsWhenOneSessionSendFails() throws Exception {
        WebSocketSession failingSession = authenticatedConnectedSession("ticket-failing");
        WebSocketSession healthySession = authenticatedConnectedSession("ticket-healthy");
        when(supportChatService.detailSnapshot(ROOM_ID, USER)).thenReturn(MockData.map(
                "contact", MockData.map("id", ROOM_ID, "userUnreadCount", 2),
                "messages", List.of(),
                "pollingIntervalMs", 5000
        ));

        doThrow(new IOException("socket write failed")).when(failingSession).sendMessage(any(TextMessage.class));

        assertThatCode(() -> handler.broadcastRoomUpdate(ROOM_ID)).doesNotThrowAnyException();

        verify(failingSession).close();
        verify(healthySession, times(2)).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession authenticatedConnectedSession(String ticket) throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession("user");
        when(ticketService.consume(ticket)).thenReturn(Optional.of(new SupportChatWebSocketTicketService.AuthenticatedTicket(
                "user",
                ROOM_ID,
                USER
        )));
        when(supportChatService.userCanAccess(ROOM_ID, USER)).thenReturn(true);
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "AUTH",
                  "ticket": "%s"
                }
                """.formatted(ticket)));
        return session;
    }

    private static WebSocketSession authenticatedSession(String mode) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("chatSessionId", ROOM_ID);
        attributes.put("mode", mode);
        attributes.put("user", USER);
        attributes.put("authenticated", true);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    private static WebSocketSession connectedButUnauthenticatedSession(String mode) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(session.getId()).thenReturn("session-" + mode + "-" + System.nanoTime());
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/support-chat?mode=" + mode + "&sessionId=" + ROOM_ID));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static void assertSentFrame(WebSocketSession session, String type, String code) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        String payload = captor.getAllValues().get(captor.getAllValues().size() - 1).getPayload();
        assertThat(payload).contains("\"type\":\"" + type + "\"");
        if (code != null) {
            assertThat(payload).contains("\"code\":\"" + code + "\"");
        }
    }
}
