package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SupportChatWebSocketHandler extends TextWebSocketHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;
    private static final long AUTH_TIMEOUT_MS = 5_000L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SupportChatService supportChatService;
    private final SupportChatWebSocketTicketService ticketService;
    private final Map<String, Set<SessionRegistration>> sessionsByChatId = new ConcurrentHashMap<>();

    public SupportChatWebSocketHandler(
            SupportChatService supportChatService,
            SupportChatWebSocketTicketService ticketService
    ) {
        this.supportChatService = supportChatService;
        this.ticketService = ticketService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            Handshake handshake = handshake(session);
            session.getAttributes().put("chatSessionId", handshake.sessionId());
            session.getAttributes().put("mode", handshake.mode());
            session.getAttributes().put("authenticated", false);
            scheduleAuthTimeout(session);
        } catch (Exception error) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("invalid support chat socket"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload;
        try {
            payload = OBJECT_MAPPER.readValue(message.getPayload(), MAP_TYPE);
        } catch (Exception error) {
            sendError(session, "INVALID_WS_PAYLOAD", "잘못된 WebSocket 메시지입니다.", false);
            if (!authenticated(session)) {
                closePolicyViolation(session);
            }
            return;
        }

        Object type = payload.get("type");
        if (!authenticated(session)) {
            if ("AUTH".equals(type)) {
                authenticateSession(session, payload);
                return;
            }
            sendError(session, "WS_AUTH_REQUIRED", "WebSocket 인증이 필요합니다.", false);
            closePolicyViolation(session);
            return;
        }
        if ("MESSAGE".equals(type)) {
            sendError(session, "WS_MESSAGE_DISABLED", "메시지는 REST API로 전송해 주세요.", false);
            return;
        }
        sendError(session, "INVALID_WS_PAYLOAD", "지원하지 않는 WebSocket 메시지입니다.", false);
    }

    public void broadcastRoomUpdate(String chatSessionId) {
        Set<SessionRegistration> sessions = sessionsByChatId.getOrDefault(chatSessionId, Set.of());
        for (SessionRegistration registration : sessions) {
            WebSocketSession session = registration.session();
            try {
                if (!session.isOpen()) {
                    removeSession(chatSessionId, registration);
                    continue;
                }
                send(session, "CHAT_UPDATED", detailFor(session, chatSessionId));
            } catch (Exception error) {
                // 한 세션의 전송 실패가 REST 응답이나 다른 세션 push를 막으면 안 된다. 놓친 갱신은 fallback polling이 보완한다.
                closeQuietly(session);
                removeSession(chatSessionId, registration);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object chatSessionId = session.getAttributes().get("chatSessionId");
        if (chatSessionId == null) {
            return;
        }
        removeSession(chatSessionId.toString(), session);
    }

    private void removeSession(String chatSessionId, WebSocketSession session) {
        Set<SessionRegistration> sessions = sessionsByChatId.get(chatSessionId);
        if (sessions != null) {
            String sessionId = session.getId();
            sessions.removeIf(registration -> registration.session() == session
                    || Objects.equals(registration.originalSessionId(), sessionId)
                    || Objects.equals(registration.session().getId(), sessionId));
            if (sessions.isEmpty()) {
                sessionsByChatId.remove(chatSessionId);
            }
        }
    }

    private void removeSession(String chatSessionId, SessionRegistration registration) {
        Set<SessionRegistration> sessions = sessionsByChatId.get(chatSessionId);
        if (sessions != null) {
            sessions.remove(registration);
            if (sessions.isEmpty()) {
                sessionsByChatId.remove(chatSessionId);
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (Exception ignored) {
            // 세션 정리 중 close 실패는 다음 polling/reconnect 경로가 보완한다.
        }
    }

    private void closePolicyViolation(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (Exception ignored) {
            // 인증 실패 세션 정리 중 close 실패는 무시한다.
        }
    }

    private void scheduleAuthTimeout(WebSocketSession session) {
        CompletableFuture.delayedExecutor(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
            try {
                if (session.isOpen() && !authenticated(session)) {
                    sendError(session, "WS_AUTH_REQUIRED", "WebSocket 인증 시간이 만료되었습니다.", false);
                    closePolicyViolation(session);
                }
            } catch (Exception ignored) {
                closePolicyViolation(session);
            }
        });
    }

    private void authenticateSession(WebSocketSession session, Map<String, Object> payload) throws IOException {
        String ticket = first(payload.get("ticket") instanceof String value ? value : null);
        if (ticket == null) {
            rejectTicket(session);
            return;
        }
        String expectedMode = String.valueOf(session.getAttributes().get("mode"));
        String expectedSessionId = String.valueOf(session.getAttributes().get("chatSessionId"));
        Optional<SupportChatWebSocketTicketService.AuthenticatedTicket> authenticatedTicket = ticketService.consume(ticket);
        if (authenticatedTicket.isEmpty()) {
            rejectTicket(session);
            return;
        }
        SupportChatWebSocketTicketService.AuthenticatedTicket auth = authenticatedTicket.orElseThrow();
        if (!Objects.equals(auth.mode(), expectedMode) || !Objects.equals(auth.sessionId(), expectedSessionId)) {
            rejectTicket(session);
            return;
        }
        if (!canAccess(auth)) {
            rejectTicket(session);
            return;
        }
        session.getAttributes().put("user", auth.user());
        session.getAttributes().put("authenticated", true);
        WebSocketSession outboundSession = new ConcurrentWebSocketSessionDecorator(
                session,
                SEND_TIME_LIMIT_MS,
                SEND_BUFFER_SIZE_LIMIT_BYTES
        );
        sessionsByChatId.computeIfAbsent(auth.sessionId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(new SessionRegistration(session.getId(), outboundSession));
        try {
            send(outboundSession, "CHAT_UPDATED", detail(auth));
        } catch (Exception error) {
            closeQuietly(outboundSession);
            removeSession(auth.sessionId(), outboundSession);
        }
    }

    private void rejectTicket(WebSocketSession session) throws IOException {
        sendError(session, "INVALID_WS_TICKET", "WebSocket 인증 티켓이 유효하지 않습니다.", false);
        closePolicyViolation(session);
    }

    private Handshake handshake(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            throw new IllegalArgumentException("missing websocket uri");
        }
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        String mode = first(params.getFirst("mode"));
        String sessionId = first(params.getFirst("sessionId"));
        if (!"user".equals(mode) && !"admin".equals(mode)) {
            throw new IllegalArgumentException("invalid websocket mode");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("missing websocket session id");
        }
        return new Handshake(mode, sessionId);
    }

    private boolean authenticated(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get("authenticated"));
    }

    private boolean canAccess(SupportChatWebSocketTicketService.AuthenticatedTicket auth) {
        return "admin".equals(auth.mode())
                ? supportChatService.adminCanAccess(auth.sessionId())
                : supportChatService.userCanAccess(auth.sessionId(), auth.user());
    }

    private Map<String, Object> detail(SupportChatWebSocketTicketService.AuthenticatedTicket auth) {
        if ("admin".equals(auth.mode())) {
            return supportChatService.adminDetailSnapshot(auth.sessionId(), auth.user());
        }
        return supportChatService.detailSnapshot(auth.sessionId(), auth.user());
    }

    private Map<String, Object> detailFor(WebSocketSession session, String chatSessionId) {
        String mode = String.valueOf(session.getAttributes().get("mode"));
        Object user = session.getAttributes().get("user");
        if (!(user instanceof CurrentUserService.CurrentUser currentUser)) {
            throw new IllegalStateException("missing websocket user");
        }
        if ("admin".equals(mode)) {
            return supportChatService.adminDetailSnapshot(chatSessionId, currentUser);
        }
        return supportChatService.detailSnapshot(chatSessionId, currentUser);
    }

    private void send(WebSocketSession session, String type, Map<String, Object> detail) throws IOException {
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(Map.of(
                "type", type,
                "detail", detail
        ))));
    }

    private void sendError(WebSocketSession session, String code, String message, boolean retryable) throws IOException {
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(Map.of(
                "type", "ERROR",
                "code", code,
                "message", message,
                "retryable", retryable
        ))));
    }

    private static String first(String value) {
        String text = value == null ? null : value.trim();
        return text == null || text.isBlank() ? null : text;
    }

    private record Handshake(String mode, String sessionId) {
    }

    private record SessionRegistration(String originalSessionId, WebSocketSession session) {
    }
}
