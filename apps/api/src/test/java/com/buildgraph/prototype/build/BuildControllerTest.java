package com.buildgraph.prototype.build;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.common.MockData;
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

@WebMvcTest(BuildController.class)
class BuildControllerTest {
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
    private BuildQueryService buildQueryService;

    @MockitoBean
    private BuildChatService buildChatService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
    }

    @Test
    void buildChatReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(post("/api/ai/build-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "200만원 PC 추천"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(buildChatService);
    }

    @Test
    void buildChatReturnsDbRuleRecommendationsWithToolResults() throws Exception {
        when(buildChatService.chat(anyMap(), eq(USER))).thenReturn(MockData.map(
                "answerType", "BUDGET",
                "message", "200만원 예산 기준으로 실속형, 균형형, 성능형 3개 조합을 계산했습니다.",
                "warnings", List.of(),
                "builds", List.of(MockData.map(
                        "id", "ai-budget-2000000-balanced",
                        "tier", "balanced",
                        "label", "균형",
                        "title", "200만원 균형형",
                        "summary", "실제 DB 부품 기준 추천입니다.",
                        "totalPrice", 1980000,
                        "budgetWon", 2000000,
                        "budgetLabel", "200만원",
                        "tierLabel", "균형형",
                        "appliedPartCategories", List.of(),
                        "items", List.of(Map.of(
                                "partId", "00000000-0000-4000-8000-000000000101",
                                "category", "CPU",
                                "name", "Ryzen 7",
                                "manufacturer", "AMD",
                                "quantity", 1,
                                "price", 420000,
                                "note", "DB 현재가 기준"
                        )),
                        "toolResults", List.of(Map.of(
                                "tool", "price",
                                "status", "PASS",
                                "confidence", "HIGH",
                                "summary", "예산 안에 들어옵니다."
                        )),
                        "warnings", List.of(),
                        "confidence", "HIGH"
                )),
                "partRecommendation", null
        ));

        mockMvc.perform(post("/api/ai/build-chat")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "200만원 PC 추천"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerType").value("BUDGET"))
                .andExpect(jsonPath("$.builds[0].title").value("200만원 균형형"))
                .andExpect(jsonPath("$.builds[0].items[0].partId").value("00000000-0000-4000-8000-000000000101"))
                .andExpect(jsonPath("$.builds[0].toolResults[0].status").value("PASS"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(buildChatService).chat(anyMap(), eq(USER));
    }

    @Test
    void buildChatPassesCurrentBuildsForPartReplacement() throws Exception {
        when(buildChatService.chat(anyMap(), eq(USER))).thenReturn(Map.of(
                "answerType", "PART",
                "message", "GPU 추천 후보 3개를 반영했습니다.",
                "warnings", List.of(),
                "builds", List.of(),
                "partRecommendation", Map.of(
                        "category", "GPU",
                        "label", "GPU",
                        "intro", "DB 현재가 기준 후보입니다.",
                        "options", List.of()
                )
        ));

        mockMvc.perform(post("/api/ai/build-chat")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "GPU 추천해줘",
                                  "currentBuilds": [
                                    {
                                      "id": "ai-budget-2000000-balanced",
                                      "tier": "balanced",
                                      "items": [
                                        {
                                          "partId": "00000000-0000-4000-8000-000000000201",
                                          "category": "GPU",
                                          "quantity": 1,
                                          "price": 1
                                        }
                                      ]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerType").value("PART"))
                .andExpect(jsonPath("$.partRecommendation.category").value("GPU"));

        verify(buildChatService).chat(anyMap(), eq(USER));
    }
}
