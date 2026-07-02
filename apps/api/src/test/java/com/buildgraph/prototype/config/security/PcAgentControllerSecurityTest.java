package com.buildgraph.prototype.config.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.admin.AdminController;
import com.buildgraph.prototype.admin.AdminQueryService;
import com.buildgraph.prototype.agent.AgentQueryService;
import com.buildgraph.prototype.agent.PcAgentAsService;
import com.buildgraph.prototype.agent.PcAgentController;
import com.buildgraph.prototype.rag.RagEmbeddingService;
import com.buildgraph.prototype.rag.RagQueryService;
import com.buildgraph.prototype.ticket.TicketController;
import com.buildgraph.prototype.ticket.TicketQueryService;
import com.buildgraph.prototype.price.PriceQueryService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({PcAgentController.class, AdminController.class, TicketController.class})
@Import({AgentSecurityConfig.class, SecurityErrorResponseWriter.class})
class PcAgentControllerSecurityTest {
    private static final String AGENT_TOKEN = "raw-agent-token";
    private static final String IDEMPOTENCY_KEY = "demo-key-1";
    private static final String ADMIN_TOKEN = "Bearer jwt-admin-token";
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private static final CurrentUserService.CurrentUser ADMIN = new CurrentUserService.CurrentUser(
            2L,
            "00000000-0000-4000-8000-000000001002",
            "admin@example.com",
            "Admin User",
            "ADMIN",
            null
    );
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PcAgentAsService pcAgentAsService;

    @MockitoBean
    private AgentTokenAuthenticationService agentTokenAuthenticationService;

    @MockitoBean
    private AgentIdempotencyService agentIdempotencyService;

    @MockitoBean
    private TicketQueryService ticketQueryService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private AdminQueryService adminQueryService;

    @MockitoBean
    private AgentQueryService agentQueryService;

    @MockitoBean
    private RagQueryService ragQueryService;

    @MockitoBean
    private RagEmbeddingService ragEmbeddingService;

    @MockitoBean
    private PriceQueryService priceQueryService;

    @Test
    void registerAllowsBootstrapWithoutAgentBearerToken() throws Exception {
        when(pcAgentAsService.register(anyMap())).thenReturn(Map.of(
                "deviceId", "device-public-id",
                "agentToken", AGENT_TOKEN,
                "status", "ACTIVE"
        ));

        mockMvc.perform(post("/api/agent/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "activationToken": "demo-agent-activation-token",
                                  "deviceFingerprintHash": "fingerprint-hash",
                                  "osVersion": "Windows 11",
                                  "agentVersion": "0.1.0"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("device-public-id"))
                .andExpect(jsonPath("$.agentToken").value(AGENT_TOKEN))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(pcAgentAsService).register(anyMap());
        verifyNoInteractions(agentTokenAuthenticationService);
    }

    @Test
    void authenticatedAgentConsentRequiresIdempotencyKey() throws Exception {
        authenticateAgent();

        mockMvc.perform(post("/api/agent/consents")
                        .header("Authorization", "Bearer " + AGENT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "consentType": "SERVER_UPLOAD",
                                  "accepted": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(pcAgentAsService);
    }

    @Test
    void agentHappyPathEndpointsUseAgentPrincipalAndIdempotencyKey() throws Exception {
        AgentPrincipal principal = authenticateAgent();
        when(agentIdempotencyService.reserve(eq(principal), anyString(), anyString(), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(
                        AgentIdempotencyDecision.proceed(101L),
                        AgentIdempotencyDecision.proceed(102L),
                        AgentIdempotencyDecision.proceed(103L)
                );
        when(pcAgentAsService.saveConsent(eq(principal), anyMap(), eq(IDEMPOTENCY_KEY))).thenReturn(Map.of(
                "id", "consent-public-id",
                "accepted", true
        ));
        when(pcAgentAsService.heartbeat(eq(principal), anyMap(), eq(IDEMPOTENCY_KEY))).thenReturn(Map.of(
                "deviceId", "device-public-id",
                "status", "ACTIVE"
        ));
        when(pcAgentAsService.uploadLogs(eq(principal), any(), any(), eq(IDEMPOTENCY_KEY))).thenReturn(Map.of(
                "logUploadId", "log-upload-id",
                "ticketId", "ticket-public-id",
                "analysisStatus", "RULE_READY"
        ));

        mockMvc.perform(post("/api/agent/consents")
                        .header("Authorization", "Bearer " + AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "consentType": "SERVER_UPLOAD",
                                  "accepted": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("consent-public-id"))
                .andExpect(jsonPath("$.accepted").value(true));

        mockMvc.perform(post("/api/agent/heartbeat")
                        .header("Authorization", "Bearer " + AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentVersion": "0.1.0",
                                  "serviceStatus": "RUNNING"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("device-public-id"));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "agent-log.jsonl.gz",
                "application/gzip",
                "demo".getBytes()
        );
        mockMvc.perform(multipart("/api/agent/log-uploads")
                        .file(file)
                        .header("Authorization", "Bearer " + AGENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .param("rangeMinutes", "30")
                        .param("symptom", "GPU temperature spike"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value("ticket-public-id"))
                .andExpect(jsonPath("$.analysisStatus").value("RULE_READY"));
    }

    @Test
    void minimalAgentAsHappyPathCanRunThroughHttpEndpoints() throws Exception {
        AgentPrincipal principal = authenticateAgent();
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(ADMIN);
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
        when(agentIdempotencyService.reserve(eq(principal), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(
                        AgentIdempotencyDecision.proceed(201L),
                        AgentIdempotencyDecision.proceed(202L),
                        AgentIdempotencyDecision.proceed(203L)
                );
        when(pcAgentAsService.register(anyMap())).thenReturn(Map.of(
                "deviceId", "device-public-id",
                "agentToken", AGENT_TOKEN,
                "status", "ACTIVE"
        ));
        when(pcAgentAsService.saveConsent(eq(principal), anyMap(), eq("consent-key"))).thenReturn(Map.of(
                "id", "consent-public-id",
                "accepted", true
        ));
        when(pcAgentAsService.heartbeat(eq(principal), anyMap(), eq("heartbeat-key"))).thenReturn(Map.of(
                "deviceId", "device-public-id",
                "status", "ACTIVE"
        ));
        when(pcAgentAsService.uploadLogs(eq(principal), any(), any(), eq("upload-key"))).thenReturn(Map.of(
                "uploadJobId", "upload-job-public-id",
                "logUploadId", "log-upload-public-id",
                "ticketId", "ticket-public-id",
                "analysisStatus", "RULE_READY",
                "reviewStatus", "REQUIRED",
                "supportDecision", "NEEDS_MORE_INFO"
        ));
        when(ticketQueryService.update("ticket-public-id", Map.of(
                "supportDecision", "REMOTE_POSSIBLE",
                "reviewStatus", "APPROVED",
                "adminNote", "Remote support link sent."
        ), ADMIN)).thenReturn(Map.of(
                "id", "ticket-public-id",
                "analysisStatus", "RULE_READY",
                "reviewStatus", "APPROVED",
                "supportDecision", "REMOTE_POSSIBLE"
        ));
        when(ticketQueryService.ticket("ticket-public-id", USER)).thenReturn(Map.of(
                "id", "ticket-public-id",
                "status", "OPEN",
                "analysisStatus", "RULE_READY",
                "reviewStatus", "APPROVED",
                "supportDecision", "REMOTE_POSSIBLE"
        ));

        mockMvc.perform(post("/api/agent/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "activationToken": "demo-agent-activation-token",
                                  "deviceFingerprintHash": "fingerprint-hash",
                                  "osVersion": "Windows 11",
                                  "agentVersion": "0.1.0"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentToken").value(AGENT_TOKEN));

        mockMvc.perform(post("/api/agent/consents")
                        .header("Authorization", "Bearer " + AGENT_TOKEN)
                        .header("Idempotency-Key", "consent-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "consentType": "SERVER_UPLOAD",
                                  "accepted": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        mockMvc.perform(post("/api/agent/heartbeat")
                        .header("Authorization", "Bearer " + AGENT_TOKEN)
                        .header("Idempotency-Key", "heartbeat-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "agentVersion": "0.1.0",
                                  "serviceStatus": "RUNNING"
                                }
                                """))
                .andExpect(status().isOk());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "agent-log.jsonl.gz",
                "application/gzip",
                "demo".getBytes()
        );
        mockMvc.perform(multipart("/api/agent/log-uploads")
                        .file(file)
                        .header("Authorization", "Bearer " + AGENT_TOKEN)
                        .header("Idempotency-Key", "upload-key")
                        .param("rangeMinutes", "30")
                        .param("symptom", "GPU temperature spike"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value("ticket-public-id"))
                .andExpect(jsonPath("$.analysisStatus").value("RULE_READY"));

        mockMvc.perform(patch("/api/admin/as-tickets/ticket-public-id")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "supportDecision": "REMOTE_POSSIBLE",
                                  "reviewStatus": "APPROVED",
                                  "adminNote": "Remote support link sent."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportDecision").value("REMOTE_POSSIBLE"));

        mockMvc.perform(get("/api/as-tickets/ticket-public-id")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisStatus").value("RULE_READY"))
                .andExpect(jsonPath("$.supportDecision").value("REMOTE_POSSIBLE"));
    }

    private AgentPrincipal authenticateAgent() {
        AgentPrincipal principal = new AgentPrincipal(10L, "device-public-id", 20L, "ACTIVE");
        when(agentTokenAuthenticationService.authenticate(AGENT_TOKEN))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        return principal;
    }
}
