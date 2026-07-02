package com.buildgraph.prototype.config.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.user.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(AgentEndpointProbeController.class)
@Import({AgentSecurityConfig.class, SecurityErrorResponseWriter.class})
class AgentSecurityChainTest {
    private static final String VALID_AGENT_TOKEN = "agent-valid-token";
    private static final String SECOND_AGENT_TOKEN = "agent-valid-token-2";
    private static final String BAD_AGENT_TOKEN = "agent-bad-token";
    private static final String BLOCKED_AGENT_TOKEN = "agent-blocked-token";
    private static final String WEB_JWT_TOKEN = "jwt-user-token";
    private static final String IDEMPOTENCY_KEY = "agent-key-123";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentTokenAuthenticationService agentTokenAuthenticationService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private AgentIdempotencyService agentIdempotencyService;

    @Test
    void agentEndpointRejectsMissingBearerTokenWithUnauthorized() throws Exception {
        mockMvc.perform(post("/api/agent/consents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void agentEndpointRejectsMissingBearerTokenOnAnyAgentPathWithUnauthorized() throws Exception {
        mockMvc.perform(post("/api/agent/heartbeat"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void agentEndpointRejectsBadAgentTokenWithUnauthorized() throws Exception {
        when(agentTokenAuthenticationService.authenticate(BAD_AGENT_TOKEN))
                .thenReturn(AgentTokenAuthenticationResult.invalid());

        mockMvc.perform(post("/api/agent/heartbeat")
                        .header("Authorization", "Bearer " + BAD_AGENT_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void agentEndpointRejectsWebJwtTokenAsInvalidAgentToken() throws Exception {
        when(agentTokenAuthenticationService.authenticate(WEB_JWT_TOKEN))
                .thenReturn(AgentTokenAuthenticationResult.invalid());

        mockMvc.perform(post("/api/agent/heartbeat")
                        .header("Authorization", "Bearer " + WEB_JWT_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void agentEndpointAcceptsValidAgentBearerToken() throws Exception {
        AgentPrincipal principal = new AgentPrincipal(10L, "device-public-id", 20L, "ACTIVE");
        when(agentTokenAuthenticationService.authenticate(VALID_AGENT_TOKEN))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        when(agentIdempotencyService.reserve(
                eq(principal),
                eq("POST"),
                eq("/api/agent/heartbeat"),
                eq(IDEMPOTENCY_KEY),
                anyString()
        )).thenReturn(AgentIdempotencyDecision.proceed(10L));

        mockMvc.perform(post("/api/agent/heartbeat")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("device-public-id"))
                .andExpect(jsonPath("$.authenticationType").value("AgentAuthenticationToken"));
    }

    @Test
    void agentEndpointRejectsBlockedAgentTokenWithForbidden() throws Exception {
        when(agentTokenAuthenticationService.authenticate(BLOCKED_AGENT_TOKEN))
                .thenReturn(AgentTokenAuthenticationResult.forbidden("Agent device is not active."));

        mockMvc.perform(post("/api/agent/heartbeat")
                        .header("Authorization", "Bearer " + BLOCKED_AGENT_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void agentTokenDoesNotAuthenticateWebJwtProtectedEndpoint() throws Exception {
        when(currentUserService.requireUser("Bearer " + VALID_AGENT_TOKEN))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));

        mockMvc.perform(get("/api/web-jwt-protected")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void authenticatedAgentMutationRejectsMissingIdempotencyKeyWithBadRequest() throws Exception {
        AgentPrincipal principal = authenticateAgent(VALID_AGENT_TOKEN, 10L, "device-public-id");

        mockMvc.perform(post("/api/agent/mutations")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN)
                        .content("{\"value\":1}")
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(agentIdempotencyService, never()).reserve(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void authenticatedAgentMutationRejectsInvalidIdempotencyKeyWithBadRequest() throws Exception {
        authenticateAgent(VALID_AGENT_TOKEN, 10L, "device-public-id");

        mockMvc.perform(post("/api/agent/mutations")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN)
                        .header("Idempotency-Key", "bad key")
                        .content("{\"value\":1}")
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(agentIdempotencyService, never()).reserve(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void duplicateAgentMutationWithSameHashReplaysStoredResponse() throws Exception {
        AgentPrincipal principal = authenticateAgent(VALID_AGENT_TOKEN, 10L, "device-public-id");
        when(agentIdempotencyService.reserve(
                eq(principal),
                eq("POST"),
                eq("/api/agent/mutations"),
                eq(IDEMPOTENCY_KEY),
                anyString()
        )).thenReturn(
                AgentIdempotencyDecision.proceed(100L),
                AgentIdempotencyDecision.replay(201, "{\"mutationCount\":999}", "application/json")
        );

        mockMvc.perform(post("/api/agent/mutations")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .content("{\"value\":1}")
                        .contentType("application/json"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/agent/mutations")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .content("{\"value\":1}")
                        .contentType("application/json"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mutationCount").value(999));

        verify(agentIdempotencyService).complete(eq(100L), eq(201), anyString(), anyString());
    }

    @Test
    void sameAgentMutationWithSameKeyAndDifferentHashReturnsConflict() throws Exception {
        AgentPrincipal principal = authenticateAgent(VALID_AGENT_TOKEN, 10L, "device-public-id");
        when(agentIdempotencyService.reserve(
                eq(principal),
                eq("POST"),
                eq("/api/agent/mutations"),
                eq(IDEMPOTENCY_KEY),
                anyString()
        )).thenReturn(AgentIdempotencyDecision.conflict());

        mockMvc.perform(post("/api/agent/mutations")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .content("{\"value\":2}")
                        .contentType("application/json"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT_STATE"));
    }

    @Test
    void differentAgentsCanReuseSameIdempotencyKeyWithoutConflict() throws Exception {
        AgentPrincipal first = authenticateAgent(VALID_AGENT_TOKEN, 10L, "device-public-id");
        AgentPrincipal second = authenticateAgent(SECOND_AGENT_TOKEN, 11L, "device-public-id-2");
        when(agentIdempotencyService.reserve(
                eq(first),
                eq("POST"),
                eq("/api/agent/mutations"),
                eq(IDEMPOTENCY_KEY),
                anyString()
        )).thenReturn(AgentIdempotencyDecision.proceed(201L));
        when(agentIdempotencyService.reserve(
                eq(second),
                eq("POST"),
                eq("/api/agent/mutations"),
                eq(IDEMPOTENCY_KEY),
                anyString()
        )).thenReturn(AgentIdempotencyDecision.proceed(202L));

        mockMvc.perform(post("/api/agent/mutations")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .content("{\"value\":1}")
                        .contentType("application/json"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/agent/mutations")
                        .header("Authorization", "Bearer " + SECOND_AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .content("{\"value\":1}")
                        .contentType("application/json"))
                .andExpect(status().isCreated());

        verify(agentIdempotencyService).reserve(
                argThat(agent -> agent.deviceInternalId().equals(10L)),
                eq("POST"),
                eq("/api/agent/mutations"),
                eq(IDEMPOTENCY_KEY),
                anyString()
        );
        verify(agentIdempotencyService).reserve(
                argThat(agent -> agent.deviceInternalId().equals(11L)),
                eq("POST"),
                eq("/api/agent/mutations"),
                eq(IDEMPOTENCY_KEY),
                anyString()
        );
    }

    @Test
    void agentGetEndpointDoesNotRequireIdempotencyKey() throws Exception {
        authenticateAgent(VALID_AGENT_TOKEN, 10L, "device-public-id");

        mockMvc.perform(get("/api/agent/probe")
                        .header("Authorization", "Bearer " + VALID_AGENT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("device-public-id"));

        verify(agentIdempotencyService, never()).reserve(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void webJwtProtectedMutationDoesNotRequireIdempotencyKey() throws Exception {
        when(currentUserService.requireUser("Bearer " + WEB_JWT_TOKEN))
                .thenReturn(new CurrentUserService.CurrentUser(
                        1L,
                        "user-public-id",
                        "user@example.com",
                        "User",
                        "USER",
                        null
                ));

        mockMvc.perform(post("/api/web-jwt-protected")
                        .header("Authorization", "Bearer " + WEB_JWT_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-public-id"));

        verify(agentIdempotencyService, never()).reserve(any(), anyString(), anyString(), anyString(), anyString());
    }

    private AgentPrincipal authenticateAgent(String token, Long deviceInternalId, String deviceId) {
        AgentPrincipal principal = new AgentPrincipal(deviceInternalId, deviceId, 20L, "ACTIVE");
        when(agentTokenAuthenticationService.authenticate(token))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        return principal;
    }
}
