package com.buildgraph.prototype.admin;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.agent.AgentQueryService;
import com.buildgraph.prototype.build.BuildGraphLayoutService;
import com.buildgraph.prototype.price.PriceQueryService;
import com.buildgraph.prototype.rag.RagEmbeddingService;
import com.buildgraph.prototype.rag.RagQueryService;
import com.buildgraph.prototype.ticket.AdminSupportChatQueueWebSocketHandler;
import com.buildgraph.prototype.ticket.SupportChatWebSocketHandler;
import com.buildgraph.prototype.ticket.TicketQueryService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(AdminController.class)
class AdminControllerTest {
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminQueryService adminQueryService;

    @MockitoBean
    private AgentQueryService agentQueryService;

    @MockitoBean
    private RagQueryService ragQueryService;

    @MockitoBean
    private RagEmbeddingService ragEmbeddingService;

    @MockitoBean
    private TicketQueryService ticketQueryService;

    @MockitoBean
    private SupportChatWebSocketHandler supportChatWebSocketHandler;

    @MockitoBean
    private AdminSupportChatQueueWebSocketHandler adminSupportChatQueueWebSocketHandler;

    @MockitoBean
    private PriceQueryService priceQueryService;

    @MockitoBean
    private BuildGraphLayoutService buildGraphLayoutService;

    @MockitoBean
    private com.buildgraph.prototype.common.PipelineJobRunRecorder pipelineJobRunRecorder;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireAdmin(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireAdmin(USER_TOKEN))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."));
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(ADMIN);
    }

    @Test
    void dashboardReturnsUnauthorizedErrorResponseWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(adminQueryService);
    }

    @Test
    void dashboardReturnsForbiddenErrorResponseWhenTokenIsNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        verifyNoInteractions(adminQueryService);
    }

    @Test
    void dashboardReturnsAdminDashboardDtoForAdminToken() throws Exception {
        when(adminQueryService.dashboard()).thenReturn(Map.<String, Object>ofEntries(
                Map.entry("agentRunning", 1),
                Map.entry("openTickets", 3),
                Map.entry("priceJobsRunning", 0),
                Map.entry("todayRevenue", 27800L),
                Map.entry("weekRevenue", 230100L),
                Map.entry("previousWeekRevenue", 208000L),
                Map.entry("revenueTrend", List.of(
                        Map.of("date", "2026-07-14", "label", "07/14", "revenue", 230100L),
                        Map.of("date", "2026-07-15", "label", "07/15", "revenue", 0L),
                        Map.of("date", "2026-07-16", "label", "07/16", "revenue", 27800L)
                )),
                Map.entry("orderStatus", List.of(
                        Map.of("status", "PENDING", "label", "처리대기", "count", 1L),
                        Map.of("status", "IN_PROGRESS", "label", "진행중", "count", 2L),
                        Map.of("status", "COMPLETED", "label", "완료", "count", 8L),
                        Map.of("status", "CANCELLED", "label", "취소", "count", 0L)
                )),
                Map.entry("asStatus", List.of(
                        Map.of("status", "PENDING", "label", "접수 대기", "count", 1L),
                        Map.of("status", "IN_PROGRESS", "label", "처리 중", "count", 2L),
                        Map.of("status", "COMPLETED", "label", "해결 완료", "count", 5L),
                        Map.of("status", "CANCELLED", "label", "취소", "count", 0L)
                )),
                Map.entry("degraded", false),
                Map.entry("generatedAt", "2026-06-29T10:50:00Z")
        ));

        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRunning").value(1))
                .andExpect(jsonPath("$.openTickets").value(3))
                .andExpect(jsonPath("$.priceJobsRunning").value(0))
                .andExpect(jsonPath("$.todayRevenue").value(27800))
                .andExpect(jsonPath("$.weekRevenue").value(230100))
                .andExpect(jsonPath("$.previousWeekRevenue").value(208000))
                .andExpect(jsonPath("$.revenueTrend[0].label").value("07/14"))
                .andExpect(jsonPath("$.revenueTrend[0].revenue").value(230100))
                .andExpect(jsonPath("$.orderStatus[0].label").value("처리대기"))
                .andExpect(jsonPath("$.orderStatus[0].count").value(1))
                .andExpect(jsonPath("$.asStatus[0].label").value("접수 대기"))
                .andExpect(jsonPath("$.asStatus[0].count").value(1))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.generatedAt").value("2026-06-29T10:50:00Z"));

        verify(adminQueryService).dashboard();
    }

    @Test
    void buildGraphLayoutReturnsSavedDefaultLayoutForAdminToken() throws Exception {
        when(buildGraphLayoutService.getDefaultLayout()).thenReturn(Map.of(
                "layoutKey", "DEFAULT",
                "source", "SAVED",
                "positions", Map.of(
                        "CPU", Map.of("x", 120, "y", 180),
                        "GPU", Map.of("x", 460, "y", 320)
                ),
                "updatedAt", "2026-07-03T00:00:00Z"
        ));

        mockMvc.perform(get("/api/admin/build-graph-layouts/default")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layoutKey").value("DEFAULT"))
                .andExpect(jsonPath("$.source").value("SAVED"))
                .andExpect(jsonPath("$.positions.CPU.x").value(120))
                .andExpect(jsonPath("$.positions.GPU.y").value(320));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(buildGraphLayoutService).getDefaultLayout();
    }

    @Test
    void buildGraphLayoutSavesDefaultLayoutForAdminToken() throws Exception {
        when(buildGraphLayoutService.saveDefaultLayout(anyMap(), eq(ADMIN))).thenReturn(Map.of(
                "layoutKey", "DEFAULT",
                "source", "SAVED",
                "positions", Map.of(
                        "CPU", Map.of("x", 140, "y", 190),
                        "GPU", Map.of("x", 520, "y", 340)
                ),
                "anchors", Map.of(
                        "GPU", Map.of("card", Map.of("x", 24, "y", 84), "part", Map.of("x", 40, "y", 55))
                )
        ));

        mockMvc.perform(put("/api/admin/build-graph-layouts/default")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "positions": {
                                    "CPU": { "x": 140, "y": 190 },
                                    "GPU": { "x": 520, "y": 340 }
                                  },
                                  "anchors": {
                                    "GPU": {
                                      "card": { "x": 24, "y": 84 },
                                      "part": { "x": 40, "y": 55 }
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SAVED"))
                .andExpect(jsonPath("$.positions.CPU.x").value(140))
                .andExpect(jsonPath("$.positions.GPU.y").value(340))
                .andExpect(jsonPath("$.anchors.GPU.card.x").value(24))
                .andExpect(jsonPath("$.anchors.GPU.part.y").value(55));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(buildGraphLayoutService).saveDefaultLayout(anyMap(), eq(ADMIN));
    }

    @Test
    void buildGraphLayoutResetDeletesSavedLayoutForAdminToken() throws Exception {
        when(buildGraphLayoutService.resetDefaultLayout(ADMIN)).thenReturn(Map.of(
                "layoutKey", "DEFAULT",
                "source", "DEFAULT",
                "positions", Map.of("CPU", Map.of("x", 20, "y", 170))
        ));

        mockMvc.perform(delete("/api/admin/build-graph-layouts/default")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("DEFAULT"))
                .andExpect(jsonPath("$.positions.CPU.x").value(20));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(buildGraphLayoutService).resetDefaultLayout(ADMIN);
    }

    @Test
    void auditLogsReturnsUnauthorizedErrorResponseWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs/recent"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(adminQueryService);
    }

    @Test
    void auditLogsReturnsForbiddenErrorResponseWhenTokenIsNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs/recent")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        verifyNoInteractions(adminQueryService);
    }

    @Test
    void auditLogsReturnsRecentItemsForAdminToken() throws Exception {
        when(adminQueryService.auditLogs()).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "action", "AS_TICKET_UPDATED",
                        "targetType", "as_tickets",
                        "targetId", "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a",
                        "metadata", Map.of(
                                "beforeStatus", "OPEN",
                                "afterStatus", "IN_PROGRESS"
                        ),
                        "createdAt", "2026-06-29T10:45:00Z"
                ))
        ));

        mockMvc.perform(get("/api/admin/audit-logs/recent")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("AS_TICKET_UPDATED"))
                .andExpect(jsonPath("$.items[0].targetType").value("as_tickets"))
                .andExpect(jsonPath("$.items[0].targetId").value("4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a"))
                .andExpect(jsonPath("$.items[0].metadata.beforeStatus").value("OPEN"))
                .andExpect(jsonPath("$.items[0].metadata.afterStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-06-29T10:45:00Z"));

        verify(adminQueryService).auditLogs();
    }

    @Test
    void ragEvidenceListReturnsUnauthorizedErrorResponseWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/admin/rag-evidence"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(ragQueryService);
    }

    @Test
    void ragEvidenceListReturnsForbiddenErrorResponseWhenTokenIsNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/rag-evidence")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        verifyNoInteractions(ragQueryService);
    }

    @Test
    void ragEvidenceListReturnsItemsForAdminToken() throws Exception {
        when(ragQueryService.adminEvidenceList()).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "id", "rag-public-id",
                        "agentSessionId", "session-public-id",
                        "sourceId", "spec-rtx4070",
                        "summary", "RTX 4070 QHD 성능 근거",
                        "score", 0.92
                )),
                "page", 0,
                "size", 20,
                "total", 1
        ));

        mockMvc.perform(get("/api/admin/rag-evidence")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("rag-public-id"))
                .andExpect(jsonPath("$.items[0].agentSessionId").value("session-public-id"))
                .andExpect(jsonPath("$.items[0].sourceId").value("spec-rtx4070"))
                .andExpect(jsonPath("$.items[0].summary").value("RTX 4070 QHD 성능 근거"))
                .andExpect(jsonPath("$.items[0].score").value(0.92))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(1));

        verify(ragQueryService).adminEvidenceList();
    }

    @Test
    void updateAsTicketStoresSupportDecisionForAdminToken() throws Exception {
        when(ticketQueryService.update("ticket-public-id", Map.of(
                "supportDecision", "REMOTE_POSSIBLE",
                "reviewStatus", "APPROVED",
                "adminNote", "Remote support link sent."
        ), ADMIN)).thenReturn(Map.of(
                "id", "ticket-public-id",
                "status", "OPEN",
                "analysisStatus", "RULE_READY",
                "reviewStatus", "APPROVED",
                "supportDecision", "REMOTE_POSSIBLE",
                "adminNote", "Remote support link sent."
        ));

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
                .andExpect(jsonPath("$.id").value("ticket-public-id"))
                .andExpect(jsonPath("$.analysisStatus").value("RULE_READY"))
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.supportDecision").value("REMOTE_POSSIBLE"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(ticketQueryService).update("ticket-public-id", Map.of(
                "supportDecision", "REMOTE_POSSIBLE",
                "reviewStatus", "APPROVED",
                "adminNote", "Remote support link sent."
        ), ADMIN);
    }

    @Test
    void updateAsTicketBroadcastsSupportChatRoomWhenTicketStatusChanges() throws Exception {
        when(ticketQueryService.update("ticket-public-id", Map.of(
                "status", "CLOSED"
        ), ADMIN)).thenReturn(Map.of(
                "id", "ticket-public-id",
                "status", "CLOSED",
                "supportChatRoomId", "00000000-0000-4000-8000-000000009001",
                "causeCandidates", List.of(),
                "upgradeCandidates", List.of()
        ));

        mockMvc.perform(patch("/api/admin/as-tickets/ticket-public-id")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "CLOSED"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ticket-public-id"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.supportChatRoomId").value("00000000-0000-4000-8000-000000009001"));

        verify(ticketQueryService).update("ticket-public-id", Map.of("status", "CLOSED"), ADMIN);
        verify(supportChatWebSocketHandler).broadcastRoomUpdate("00000000-0000-4000-8000-000000009001");
        verify(adminSupportChatQueueWebSocketHandler).broadcastQueuePatch("00000000-0000-4000-8000-000000009001");
    }

    @Test
    void deleteAsTicketSoftDeletesAndBroadcastsSupportChatRemovalForAdminToken() throws Exception {
        when(ticketQueryService.delete("ticket-public-id", ADMIN)).thenReturn(Map.of(
                "id", "ticket-public-id",
                "deleted", true,
                "deletedAt", "2026-07-16T06:00:00Z",
                "supportChatRoomId", "00000000-0000-4000-8000-000000009001"
        ));

        mockMvc.perform(delete("/api/admin/as-tickets/ticket-public-id")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ticket-public-id"))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.deletedAt").value("2026-07-16T06:00:00Z"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(ticketQueryService).delete("ticket-public-id", ADMIN);
        verify(supportChatWebSocketHandler).broadcastRoomUpdate("00000000-0000-4000-8000-000000009001");
        verify(adminSupportChatQueueWebSocketHandler).broadcastQueuePatch("00000000-0000-4000-8000-000000009001");
    }
}
