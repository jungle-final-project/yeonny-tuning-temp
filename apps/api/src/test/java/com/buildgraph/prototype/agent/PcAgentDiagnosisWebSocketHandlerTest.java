package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenAuthenticationResult;
import com.buildgraph.prototype.config.security.AgentTokenAuthenticationService;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class PcAgentDiagnosisWebSocketHandlerTest {
    private final AgentTokenAuthenticationService authenticationService = mock(AgentTokenAuthenticationService.class);
    private final PcAgentDiagnosisSocketBroker broker = mock(PcAgentDiagnosisSocketBroker.class);
    private final PcAgentDiagnosisPersistenceService persistenceService = mock(PcAgentDiagnosisPersistenceService.class);
    private final PcAgentDiagnosisWebSocketHandler handler = new PcAgentDiagnosisWebSocketHandler(
            authenticationService, broker, persistenceService
    );

    @Test
    void invalidAgentTokenReturnsAuthFailureAndClosesConnection() throws Exception {
        WebSocketSession session = session();
        when(authenticationService.authenticate("bad-token")).thenReturn(AgentTokenAuthenticationResult.invalid());
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"AUTH","agentToken":"bad-token"}
                """));

        assertLastFrameContains(session, "\"code\":\"AUTH_FAILED\"");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void validAgentTokenRegistersDeviceAndReturnsReadyFrame() throws Exception {
        WebSocketSession session = session();
        AgentPrincipal principal = new AgentPrincipal(1L, "device-1", 7L, "ACTIVE");
        when(authenticationService.authenticate("agent-token"))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        when(broker.register(principal, session)).thenReturn(session);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"AUTH","agentToken":"agent-token"}
                """));

        assertThat(session.getAttributes()).containsEntry("authenticated", true);
        assertLastFrameContains(session, "\"type\":\"READY\"");
        assertLastFrameContains(session, "\"deviceId\":\"device-1\"");
    }

    @Test
    void authenticatedDiagnosisStatusIsRecordedAndAcknowledged() throws Exception {
        WebSocketSession session = session();
        AgentPrincipal principal = new AgentPrincipal(1L, "device-1", 7L, "ACTIVE");
        when(authenticationService.authenticate("agent-token"))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        when(broker.register(principal, session)).thenReturn(session);
        when(broker.recordStatus(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(true);
        when(persistenceService.storeStatus(any(), any())).thenReturn(true);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"AUTH","agentToken":"agent-token"}
                """));

        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type":"DIAGNOSIS_STATUS",
                  "detail":{
                    "diagnosisId":"00000000-0000-4000-8000-000000000321",
                    "eventId":"event-1",
                    "eventType":"PROGRESS_UPDATED",
                    "sessionState":"DIAGNOSING",
                    "progress":25,
                    "timestamp":"2026-07-13T01:00:00Z",
                    "message":"진단 진행률이 25%로 업데이트되었습니다.",
                    "metadata":{"progress":25}
                  }
                }
                """));

        assertLastFrameContains(session, "\"type\":\"DIAGNOSIS_STATUS_ACK\"");
        assertLastFrameContains(session, "\"eventId\":\"event-1\"");
    }

    @Test
    void authenticatedDiagnosisResultIsRecordedAndAcknowledged() throws Exception {
        WebSocketSession session = session();
        AgentPrincipal principal = new AgentPrincipal(1L, "device-1", 7L, "ACTIVE");
        when(authenticationService.authenticate("agent-token"))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        when(broker.register(principal, session)).thenReturn(session);
        when(broker.recordResult(any(), any())).thenReturn(true);
        when(persistenceService.storeResult(any(), any())).thenReturn(true);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"AUTH","agentToken":"agent-token"}
                """));

        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type":"DIAGNOSIS_RESULT",
                  "detail":{
                    "diagnosisId":"00000000-0000-4000-8000-000000000321",
                    "resultId":"result-1",
                    "severity":"CRITICAL",
                    "title":"그래픽 장치 이상",
                    "summary":"드라이버 구성을 확인하세요.",
                    "resolutionType":"PHYSICAL_INSPECTION",
                    "canAutoRecover":false,
                    "evaluatedAt":"2026-07-13T01:00:00Z",
                    "evidence":[],
                    "findings":[],
                    "recommendedActions":[],
                    "dataMode":"LIVE"
                  }
                }
                """));

        assertLastFrameContains(session, "\"type\":\"DIAGNOSIS_RESULT_ACK\"");
        assertLastFrameContains(session, "\"resultId\":\"result-1\"");
    }

    @Test
    void persistenceFailureNeverReturnsSuccessAck() throws Exception {
        WebSocketSession session = session();
        AgentPrincipal principal = new AgentPrincipal(1L, "device-1", 7L, "ACTIVE");
        when(authenticationService.authenticate("agent-token"))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        when(broker.register(principal, session)).thenReturn(session);
        when(persistenceService.storeStatus(any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"AUTH","agentToken":"agent-token"}
                """));

        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type":"DIAGNOSIS_STATUS",
                  "detail":{
                    "diagnosisId":"00000000-0000-4000-8000-000000000321",
                    "eventId":"event-1",
                    "eventType":"PROGRESS_UPDATED",
                    "sessionState":"DIAGNOSING",
                    "progress":25,
                    "timestamp":"2026-07-13T01:00:00Z"
                  }
                }
                """));

        assertLastFrameContains(session, "\"code\":\"DIAGNOSIS_PERSISTENCE_FAILED\"");
        assertLastFrameContains(session, "\"retryable\":true");
        verify(broker, org.mockito.Mockito.never())
                .recordStatus(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static void assertLastFrameContains(WebSocketSession session, String expected) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getPayload()).contains(expected);
    }
}
