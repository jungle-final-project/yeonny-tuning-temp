package com.buildgraph.prototype.build;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

// build-chat은 전용 풀에서 비동기(DeferredResult) 처리하므로, 실제 실행기 빈을 로드하고
// async가 시작된 뒤 asyncDispatch로 결과를 받는다.
@WebMvcTest(BuildController.class)
@Import(AiChatAsyncExecutor.class)
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
    void buildChatForwardsVerifiedMockHeadersToService() throws Exception {
        when(buildChatService.chat(
                anyMap(),
                eq("BUILD_CHAT_FAST"),
                eq(USER),
                eq("MOCK"),
                eq("secret")
        )).thenReturn(Map.of(
                "answerType", "GENERAL",
                "message", "mock response",
                "builds", List.of(),
                "warnings", List.of()
        ));

        MvcResult started = mockMvc.perform(post("/api/ai/build-chat")
                        .header("Authorization", USER_TOKEN)
                        .header("X-BuildGraph-AI-Profile", "BUILD_CHAT_FAST")
                        .header("X-BuildGraph-AI-Mode", "MOCK")
                        .header("X-BuildGraph-Test-Key", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "mock request"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("mock response"));

        verify(buildChatService).chat(
                anyMap(),
                eq("BUILD_CHAT_FAST"),
                eq(USER),
                eq("MOCK"),
                eq("secret")
        );
    }

    @Test
    void saveBuildFromChatReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(post("/api/builds/from-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceBuildId": "ai-budget-2000000-balanced"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(buildQueryService);
    }

    @Test
    void saveBuildFromChatPersistsTemporaryRecommendationForCurrentUser() throws Exception {
        when(buildQueryService.saveFromChat(anyMap(), eq(USER))).thenReturn(Map.of(
                "id", "00000000-0000-4000-8000-000000009001"
        ));

        mockMvc.perform(post("/api/builds/from-chat")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceBuildId": "ai-budget-2000000-balanced",
                                  "lastUserMessage": "200만원 PC 추천",
                                  "build": {
                                    "id": "ai-budget-2000000-balanced",
                                    "title": "200만원 균형형",
                                    "summary": "게임과 개발을 균형 있게 반영했습니다.",
                                    "totalPrice": 1980000,
                                    "confidence": "HIGH",
                                    "items": [
                                      {
                                        "partId": "00000000-0000-4000-8000-000000000101",
                                        "category": "CPU",
                                        "quantity": 1,
                                        "price": 420000
                                      }
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000000-0000-4000-8000-000000009001"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(buildQueryService).saveFromChat(anyMap(), eq(USER));
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

        MvcResult started = mockMvc.perform(post("/api/ai/build-chat")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "200만원 PC 추천"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
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

        MvcResult started = mockMvc.perform(post("/api/ai/build-chat")
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
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerType").value("PART"))
                .andExpect(jsonPath("$.partRecommendation.category").value("GPU"));

        verify(buildChatService).chat(anyMap(), eq(USER));
    }

    @Test
    void homeRecommendationsReturnServerValidatedBuilds() throws Exception {
        when(buildChatService.homeRecommendedBuilds()).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "id", "home-safe-build",
                        "totalPrice", 1_950_000,
                        "toolResults", List.of(Map.of("tool", "compatibility", "status", "PASS"))
                )),
                "generatedAt", "2026-07-14T00:00:00Z",
                "fallbackUsed", false
        ));

        mockMvc.perform(get("/api/recommendations/home-builds")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("home-safe-build"))
                .andExpect(jsonPath("$.items[0].toolResults[0].status").value("PASS"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(buildChatService).homeRecommendedBuilds();
    }

    @Test
    void homeRecommendationsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/recommendations/home-builds"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(buildChatService);
    }

    @Test
    void renameBuildDelegatesToService() throws Exception {
        when(buildQueryService.renameBuild(eq("build-1"), anyMap(), eq(USER)))
                .thenReturn(Map.of("id", "build-1", "name", "새 견적 이름"));

        mockMvc.perform(patch("/api/builds/build-1")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "새 견적 이름" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 견적 이름"));

        verify(buildQueryService).renameBuild(eq("build-1"), anyMap(), eq(USER));
    }

    @Test
    void duplicateBuildDelegatesToService() throws Exception {
        when(buildQueryService.duplicateBuild(eq("build-1"), eq(USER)))
                .thenReturn(Map.of("id", "build-2", "name", "원본 견적 (사본)"));

        mockMvc.perform(post("/api/builds/build-1/duplicate")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("build-2"))
                .andExpect(jsonPath("$.name").value("원본 견적 (사본)"));

        verify(buildQueryService).duplicateBuild(eq("build-1"), eq(USER));
    }

    @Test
    void deleteBuildDelegatesToServiceAndReturnsFlag() throws Exception {
        mockMvc.perform(delete("/api/builds/build-1")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("build-1"))
                .andExpect(jsonPath("$.deleted").value(true));

        verify(buildQueryService).deleteBuild(eq("build-1"), eq(USER));
    }

    @Test
    void deleteBuildReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(delete("/api/builds/build-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(buildQueryService);
    }
}
