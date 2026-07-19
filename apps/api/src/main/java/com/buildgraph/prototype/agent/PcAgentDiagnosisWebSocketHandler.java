package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenAuthenticationResult;
import com.buildgraph.prototype.config.security.AgentTokenAuthenticationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class PcAgentDiagnosisWebSocketHandler extends TextWebSocketHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final long AUTH_TIMEOUT_MS = 5_000L;

    private final AgentTokenAuthenticationService authenticationService;
    private final PcAgentDiagnosisSocketBroker broker;
    private final PcAgentDiagnosisPersistenceService persistenceService;

    public PcAgentDiagnosisWebSocketHandler(
            AgentTokenAuthenticationService authenticationService,
            PcAgentDiagnosisSocketBroker broker,
            PcAgentDiagnosisPersistenceService persistenceService
    ) {
        this.authenticationService = authenticationService;
        this.broker = broker;
        this.persistenceService = persistenceService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put("authenticated", false);
        CompletableFuture.delayedExecutor(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (session.isOpen() && !authenticated(session)) {
                sendErrorQuietly(session, "AUTH_FAILED", "Agent 인증 시간이 만료되었습니다.");
                closePolicyViolation(session);
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload;
        try {
            payload = OBJECT_MAPPER.readValue(message.getPayload(), MAP_TYPE);
        } catch (Exception error) {
            sendError(session, "INVALID_WS_PAYLOAD", "잘못된 WebSocket 메시지입니다.");
            if (!authenticated(session)) {
                closePolicyViolation(session);
            }
            return;
        }
        if (!authenticated(session)) {
            if ("AUTH".equals(payload.get("type"))) {
                authenticate(session, payload);
                return;
            }
            sendError(session, "AUTH_FAILED", "Agent 인증이 필요합니다.");
            closePolicyViolation(session);
            return;
        }
        if ("DIAGNOSIS_RESPONSE".equals(payload.get("type"))) {
            recordDiagnosisResponse(session, payload);
            return;
        }
        if ("DIAGNOSIS_STATUS".equals(payload.get("type"))) {
            recordDiagnosisStatus(session, payload);
            return;
        }
        if ("DIAGNOSIS_RESULT".equals(payload.get("type"))) {
            recordDiagnosisResult(session, payload);
            return;
        }
        sendError(session, "INVALID_WS_PAYLOAD", "지원하지 않는 WebSocket 메시지입니다.");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object principal = session.getAttributes().get("agentPrincipal");
        if (principal instanceof AgentPrincipal agentPrincipal) {
            broker.unregister(agentPrincipal.deviceId(), session);
        }
    }

    private void authenticate(WebSocketSession session, Map<String, Object> payload) throws IOException {
        String agentToken = text(payload.get("agentToken"));
        if (agentToken == null) {
            rejectAuthentication(session, "AUTH_FAILED", "Agent 토큰이 필요합니다.");
            return;
        }
        AgentTokenAuthenticationResult result = authenticationService.authenticate(agentToken);
        if (result.status() != AgentTokenAuthenticationResult.Status.AUTHENTICATED) {
            String code = result.status() == AgentTokenAuthenticationResult.Status.FORBIDDEN
                    ? "AGENT_FORBIDDEN"
                    : "AUTH_FAILED";
            rejectAuthentication(session, code, result.message());
            return;
        }
        AgentPrincipal principal = result.principal().orElseThrow();
        session.getAttributes().put("authenticated", true);
        session.getAttributes().put("agentPrincipal", principal);
        WebSocketSession outbound = broker.register(principal, session);
        session.getAttributes().put("outboundSession", outbound);
        send(outbound, "READY", Map.of(
                "deviceId", principal.deviceId(),
                "agentState", "IDLE"
        ));
    }

    private void recordDiagnosisResponse(WebSocketSession session, Map<String, Object> payload) throws IOException {
        AgentPrincipal principal = (AgentPrincipal) session.getAttributes().get("agentPrincipal");
        String diagnosisId = text(payload.get("diagnosisId"));
        String status = text(payload.get("status"));
        String message = text(payload.get("message"));
        if (diagnosisId == null || status == null
                || !broker.recordResponse(principal.deviceId(), diagnosisId, status, message)) {
            sendError(outbound(session), "INVALID_DIAGNOSIS_RESPONSE", "진단 응답이 현재 요청과 일치하지 않습니다.");
        }
    }

    private void recordDiagnosisStatus(WebSocketSession session, Map<String, Object> payload) throws IOException {
        AgentPrincipal principal = (AgentPrincipal) session.getAttributes().get("agentPrincipal");
        Map<String, Object> detail = objectMap(payload.get("detail"));
        String diagnosisId = text(detail.get("diagnosisId"));
        String eventId = text(detail.get("eventId"));
        String eventType = text(detail.get("eventType"));
        String sessionState = text(detail.get("sessionState"));
        String message = text(detail.get("message"));
        Number progressValue = detail.get("progress") instanceof Number number ? number : null;
        Map<String, Object> metadata = objectMap(detail.get("metadata"));
        int progress = progressValue == null ? -1 : progressValue.intValue();
        try {
            if (!persistenceService.storeStatus(principal, detail)) {
                sendError(outbound(session), "INVALID_DIAGNOSIS_STATUS", "진단 상태 이벤트가 요청과 일치하지 않습니다.");
                return;
            }
        } catch (RuntimeException error) {
            sendError(
                    outbound(session),
                    "DIAGNOSIS_PERSISTENCE_FAILED",
                    "진단 상태 저장에 실패했습니다. 동일 eventId로 다시 전송해 주세요.",
                    true
            );
            return;
        }
        if (!broker.recordStatus(
                principal.deviceId(),
                diagnosisId,
                eventId,
                eventType,
                sessionState,
                progress,
                message,
                metadata
        )) {
            sendError(outbound(session), "INVALID_DIAGNOSIS_STATUS", "진단 상태 이벤트 형식이 올바르지 않습니다.");
            return;
        }
        send(outbound(session), "DIAGNOSIS_STATUS_ACK", Map.of(
                "diagnosisId", diagnosisId,
                "eventId", eventId
        ));
    }

    private void recordDiagnosisResult(WebSocketSession session, Map<String, Object> payload) throws IOException {
        AgentPrincipal principal = (AgentPrincipal) session.getAttributes().get("agentPrincipal");
        Map<String, Object> detail = objectMap(payload.get("detail"));
        String diagnosisId = text(detail.get("diagnosisId"));
        String resultId = text(detail.get("resultId"));
        try {
            if (!persistenceService.storeResult(principal, detail)) {
                sendError(outbound(session), "INVALID_DIAGNOSIS_RESULT", "진단 결과가 요청과 일치하지 않습니다.");
                return;
            }
        } catch (RuntimeException error) {
            sendError(
                    outbound(session),
                    "DIAGNOSIS_PERSISTENCE_FAILED",
                    "진단 결과 저장에 실패했습니다. 동일 resultId로 다시 전송해 주세요.",
                    true
            );
            return;
        }
        if (!broker.recordResult(principal.deviceId(), detail)) {
            sendError(outbound(session), "INVALID_DIAGNOSIS_RESULT", "진단 결과 형식이 올바르지 않습니다.");
            return;
        }
        send(outbound(session), "DIAGNOSIS_RESULT_ACK", Map.of(
                "diagnosisId", diagnosisId,
                "resultId", resultId
        ));
    }

    private void rejectAuthentication(WebSocketSession session, String code, String message) throws IOException {
        sendError(session, code, message == null ? "Agent 인증에 실패했습니다." : message);
        closePolicyViolation(session);
    }

    private static boolean authenticated(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get("authenticated"));
    }

    private static WebSocketSession outbound(WebSocketSession session) {
        Object outbound = session.getAttributes().get("outboundSession");
        return outbound instanceof WebSocketSession socket ? socket : session;
    }

    private static void send(WebSocketSession session, String type, Map<String, Object> detail) throws IOException {
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(Map.of(
                "type", type,
                "detail", detail
        ))));
    }

    private static void sendError(WebSocketSession session, String code, String message) throws IOException {
        sendError(session, code, message, false);
    }

    private static void sendError(
            WebSocketSession session,
            String code,
            String message,
            boolean retryable
    ) throws IOException {
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(Map.of(
                "type", "ERROR",
                "code", code,
                "message", message,
                "retryable", retryable
        ))));
    }

    private static void sendErrorQuietly(WebSocketSession session, String code, String message) {
        try {
            sendError(session, code, message);
        } catch (IOException ignored) {
            // 인증 타임아웃 정리 중 전송 실패는 close로 마무리한다.
        }
    }

    private static void closePolicyViolation(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (IOException ignored) {
            // 연결 종료 중 실패는 컨테이너 정리 경로에 맡긴다.
        }
    }

    private static String text(Object value) {
        String result = value instanceof String text ? text.trim() : null;
        return result == null || result.isBlank() ? null : result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> result = new HashMap<>();
        values.forEach((key, item) -> {
            if (key instanceof String textKey) {
                result.put(textKey, item);
            }
        });
        return result;
    }
}
