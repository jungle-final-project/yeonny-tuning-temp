package com.buildgraph.prototype.log;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgentLogController.class)
class AgentLogControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            20L,
            "user-public-id",
            "user@example.com",
            "User",
            "USER",
            null
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AgentLogQueryService agentLogQueryService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    void userCanPreviewAsRagRecommendationWithoutPersistingUpload() throws Exception {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
        when(agentLogQueryService.previewAsRag(any(), eq(30))).thenReturn(MockData.map(
                "recommendedService", "REMOTE_SUPPORT",
                "recommendedServiceLabel", "원격지원 신청",
                "supportDecision", "REMOTE_POSSIBLE",
                "recommendationMessage", "이 증상은 원격지원 신청 서비스를 받는 것이 좋습니다."
        ));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "agent-log.jsonl",
                "application/x-ndjson",
                "display driver reset\n".getBytes()
        );

        mockMvc.perform(multipart("/api/agent-logs/as-rag-preview")
                        .file(file)
                        .param("rangeMinutes", "30")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendedService").value("REMOTE_SUPPORT"))
                .andExpect(jsonPath("$.supportDecision").value("REMOTE_POSSIBLE"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(agentLogQueryService).previewAsRag(any(), eq(30));
    }
}
