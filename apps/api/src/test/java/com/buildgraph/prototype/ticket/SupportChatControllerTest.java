package com.buildgraph.prototype.ticket;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({SupportChatController.class, AdminSupportChatController.class})
class SupportChatControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private static final String ADMIN_TOKEN = "Bearer jwt-admin-token";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );
    private static final CurrentUserService.CurrentUser ADMIN = new CurrentUserService.CurrentUser(
            2L,
            "00000000-0000-4000-8000-000000000001",
            "admin@example.com",
            "BuildGraph Admin",
            "ADMIN",
            null
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupportChatService supportChatService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private SupportChatWebSocketHandler supportChatWebSocketHandler;

    @MockitoBean
    private SupportChatWebSocketTicketService supportChatWebSocketTicketService;

    @Test
    void currentChatWithoutTicketGuidesUserToSupportNew() throws Exception {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
        when(supportChatService.current(USER, null)).thenReturn(MockData.map(
                "contact", null,
                "messages", List.of(),
                "supportNewPath", "/support/new",
                "pollingIntervalMs", 5000
        ));

        mockMvc.perform(get("/api/support/chat-sessions/current")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact").doesNotExist())
                .andExpect(jsonPath("$.supportNewPath").value("/support/new"))
                .andExpect(jsonPath("$.pollingIntervalMs").value(5000));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(supportChatService).current(USER, null);
    }

    @Test
    void userCanPostMessageToOwnTicketChatSession() throws Exception {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
        when(supportChatService.postUserMessage("chat-session-id", Map.of("content", "지금 상담 가능할까요?"), USER))
                .thenReturn(chatDetail("chat-session-id", "지금 상담 가능할까요?"));

        mockMvc.perform(post("/api/support/chat-sessions/chat-session-id/messages")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "지금 상담 가능할까요?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact.id").value("chat-session-id"))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[0].content").value("지금 상담 가능할까요?"));

        verify(supportChatService).postUserMessage("chat-session-id", Map.of("content", "지금 상담 가능할까요?"), USER);
        verify(supportChatWebSocketHandler).broadcastRoomUpdate("chat-session-id");
    }

    @Test
    void adminCanPostMessageToTicketChatSession() throws Exception {
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(ADMIN);
        when(supportChatService.postAdminMessage("chat-session-id", Map.of("content", "확인 후 답변드리겠습니다."), ADMIN))
                .thenReturn(chatDetail("chat-session-id", "확인 후 답변드리겠습니다."));

        mockMvc.perform(post("/api/admin/support/chat-sessions/chat-session-id/messages")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "확인 후 답변드리겠습니다."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact.id").value("chat-session-id"))
                .andExpect(jsonPath("$.messages[0].content").value("확인 후 답변드리겠습니다."));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(supportChatService).postAdminMessage("chat-session-id", Map.of("content", "확인 후 답변드리겠습니다."), ADMIN);
        verify(supportChatWebSocketHandler).broadcastRoomUpdate("chat-session-id");
    }

    @Test
    void adminCanLoadChatDetailWithoutMarkingUnread() throws Exception {
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(ADMIN);
        when(supportChatService.adminDetail("chat-session-id", ADMIN, false))
                .thenReturn(chatDetail("chat-session-id", "사용자 메시지"));

        mockMvc.perform(get("/api/admin/support/chat-sessions/chat-session-id")
                        .queryParam("markRead", "false")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contact.id").value("chat-session-id"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(supportChatService).adminDetail("chat-session-id", ADMIN, false);
    }

    @Test
    void userCanIssueSupportChatWebSocketTicket() throws Exception {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
        when(supportChatWebSocketTicketService.issueUserTicket("chat-session-id", USER)).thenReturn(Map.of(
                "ticket", "ws-ticket-user",
                "expiresAt", "2026-07-06T10:01:00Z",
                "expiresInSeconds", 60L
        ));

        mockMvc.perform(post("/api/support/chat-sessions/chat-session-id/ws-ticket")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").value("ws-ticket-user"))
                .andExpect(jsonPath("$.expiresAt").value("2026-07-06T10:01:00Z"))
                .andExpect(jsonPath("$.expiresInSeconds").value(60));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(supportChatWebSocketTicketService).issueUserTicket("chat-session-id", USER);
    }

    @Test
    void adminCanIssueSupportChatWebSocketTicket() throws Exception {
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(ADMIN);
        when(supportChatWebSocketTicketService.issueAdminTicket("chat-session-id", ADMIN)).thenReturn(Map.of(
                "ticket", "ws-ticket-admin",
                "expiresAt", "2026-07-06T10:01:00Z",
                "expiresInSeconds", 60L
        ));

        mockMvc.perform(post("/api/admin/support/chat-sessions/chat-session-id/ws-ticket")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").value("ws-ticket-admin"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(supportChatWebSocketTicketService).issueAdminTicket("chat-session-id", ADMIN);
    }

    private static Map<String, Object> chatDetail(String sessionId, String content) {
        return MockData.map(
                "contact", MockData.map(
                        "id", sessionId,
                        "asTicketId", "ticket-public-id",
                        "status", "ACTIVE",
                        "title", "AS 상담방",
                        "adminUnreadCount", 1,
                        "userUnreadCount", 0
                ),
                "messages", List.of(MockData.map(
                        "id", "message-public-id",
                        "role", "USER",
                        "content", content,
                        "createdAt", "2026-07-06T10:00:00Z"
                )),
                "pollingIntervalMs", 5000
        );
    }
}
