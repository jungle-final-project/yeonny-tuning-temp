package com.buildgraph.prototype.ticket;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TicketController.class)
class TicketControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
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
    private TicketQueryService ticketQueryService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    void userCanReadFinalAgentAsTicketStatus() throws Exception {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
        when(ticketQueryService.ticket("ticket-public-id", USER)).thenReturn(MockData.map(
                "id", "ticket-public-id",
                "status", "OPEN",
                "symptom", "GPU temperature spike",
                "logUploadId", "log-upload-public-id",
                "analysisStatus", "RULE_READY",
                "reviewStatus", "APPROVED",
                "supportDecision", "REMOTE_POSSIBLE",
                "riskLevel", "MEDIUM",
                "causeCandidates", List.of(MockData.map("label", "GPU temperature spike")),
                "upgradeCandidates", List.of(),
                "adminNote", "Remote support link sent."
        ));

        mockMvc.perform(get("/api/as-tickets/ticket-public-id")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ticket-public-id"))
                .andExpect(jsonPath("$.analysisStatus").value("RULE_READY"))
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.supportDecision").value("REMOTE_POSSIBLE"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(ticketQueryService).ticket("ticket-public-id", USER);
    }
}
