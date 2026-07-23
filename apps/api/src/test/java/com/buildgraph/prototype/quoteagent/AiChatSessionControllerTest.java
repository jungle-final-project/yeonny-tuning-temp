package com.buildgraph.prototype.quoteagent;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.aichat.chat.AiChatSessionController;
import com.buildgraph.prototype.aichat.query.AiChatSessionQuery;
import com.buildgraph.prototype.user.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(AiChatSessionController.class)
class AiChatSessionControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            7L,
            "00000000-0000-4000-8000-000000001007",
            "user@example.com",
            "사용자",
            "USER",
            null
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiChatSessionQuery aiChatSessionQuery;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
    }

    @Test
    void resetClearsCurrentUsersServerContext() throws Exception {
        mockMvc.perform(post("/api/ai/build-chat/session/reset")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESET"));

        verify(aiChatSessionQuery).resetContext(USER.internalId());
    }

    @Test
    void resetRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/ai/build-chat/session/reset"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(aiChatSessionQuery);
    }
}