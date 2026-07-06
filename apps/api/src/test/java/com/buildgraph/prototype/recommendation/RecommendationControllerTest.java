package com.buildgraph.prototype.recommendation;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@WebMvcTest(RecommendationController.class)
class RecommendationControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private static final String ADMIN_TOKEN = "Bearer jwt-admin-token";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );
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
    private RecommendationLearningService recommendationLearningService;

    @MockitoBean
    private RecommendationTrainingService recommendationTrainingService;

    @MockitoBean
    private HomePartRecommendationService homePartRecommendationService;

    @MockitoBean
    private RecommendationDriftService recommendationDriftService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
        when(currentUserService.requireAdmin(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireAdmin(USER_TOKEN))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."));
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(ADMIN);
    }

    @Test
    void recommendationEventRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/recommendation-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"SAVE\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(recommendationLearningService);
    }

    @Test
    void recommendationEventStoresUserEvent() throws Exception {
        when(recommendationLearningService.recordEvent(anyMap(), org.mockito.ArgumentMatchers.eq(USER))).thenReturn(MockData.map(
                "id", "event-public-id",
                "eventType", "SAVE",
                "labelScore", 3.0,
                "sourceSurface", "BUILD_CHAT",
                "createdAt", "2026-07-03T10:00:00Z"
        ));

        mockMvc.perform(post("/api/recommendation-events")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"SAVE\",\"sourceSurface\":\"BUILD_CHAT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("SAVE"))
                .andExpect(jsonPath("$.labelScore").value(3.0));

        verify(recommendationLearningService).recordEvent(anyMap(), org.mockito.ArgumentMatchers.eq(USER));
    }

    @Test
    void homeRecommendedPartsRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/recommendations/home-parts?limit=4"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(homePartRecommendationService);
    }

    @Test
    void userCanListHomeRecommendedParts() throws Exception {
        when(homePartRecommendationService.homeParts(USER, 4)).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "recommendationId", "home-part-part-public-id",
                        "rankPosition", 0,
                        "scoreSource", "FALLBACK",
                        "part", Map.of(
                                "id", "part-public-id",
                                "category", "GPU",
                                "name", "RTX 5070",
                                "price", 850000
                        )
                )),
                "generatedAt", "2026-07-03T10:00:00Z",
                "fallbackUsed", true
        ));

        mockMvc.perform(get("/api/recommendations/home-parts?limit=4")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recommendationId").value("home-part-part-public-id"))
                .andExpect(jsonPath("$.items[0].part.category").value("GPU"))
                .andExpect(jsonPath("$.fallbackUsed").value(true));

        verify(homePartRecommendationService).homeParts(USER, 4);
    }

    @Test
    void asRecommendationFeedbackRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/recommendation-feedback/as-tickets/ticket-public-id")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"confirmed\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(recommendationLearningService);
    }

    @Test
    void adminCanCreateAsNegativeFeedback() throws Exception {
        when(recommendationLearningService.confirmAsNegativeFeedback("ticket-public-id", Map.of("reason", "confirmed"), ADMIN))
                .thenReturn(MockData.map(
                        "id", "event-public-id",
                        "eventType", "AS_CONFIRMED_NEGATIVE",
                        "labelScore", -2.0,
                        "sourceSurface", "ADMIN_AS_FEEDBACK",
                        "createdAt", "2026-07-03T10:00:00Z"
                ));

        mockMvc.perform(post("/api/admin/recommendation-feedback/as-tickets/ticket-public-id")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"confirmed\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("AS_CONFIRMED_NEGATIVE"))
                .andExpect(jsonPath("$.labelScore").value(-2.0));

        verify(recommendationLearningService).confirmAsNegativeFeedback("ticket-public-id", Map.of("reason", "confirmed"), ADMIN);
    }

    @Test
    void homePartFeedbackRequiresAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/recommendation-feedback/home-parts")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partId\":\"part-public-id\",\"label\":\"PROMOTE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminCanCreateHomePartFeedback() throws Exception {
        when(recommendationLearningService.recordHomePartAdminFeedback(Map.of("partId", "part-public-id", "label", "PROMOTE"), ADMIN))
                .thenReturn(MockData.map(
                        "id", "event-public-id",
                        "eventType", "ADMIN_PROMOTE",
                        "labelScore", 4.0,
                        "sourceSurface", "ADMIN_HOME_PART_FEEDBACK",
                        "category", "GPU",
                        "createdAt", "2026-07-03T10:00:00Z"
                ));

        mockMvc.perform(post("/api/admin/recommendation-feedback/home-parts")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partId\":\"part-public-id\",\"label\":\"PROMOTE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("ADMIN_PROMOTE"))
                .andExpect(jsonPath("$.labelScore").value(4.0));

        verify(recommendationLearningService).recordHomePartAdminFeedback(Map.of("partId", "part-public-id", "label", "PROMOTE"), ADMIN);
    }

    @Test
    void adminCanListRecommendationModels() throws Exception {
        when(recommendationLearningService.modelVersions()).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "id", "model-public-id",
                        "modelName", "xgboost-reranker",
                        "modelVersion", "xgb-20260703100000",
                        "status", "SHADOW"
                )),
                "page", 0,
                "size", 50,
                "total", 1
        ));

        mockMvc.perform(get("/api/admin/recommendation-models")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].modelVersion").value("xgb-20260703100000"));
    }

    @Test
    void adminCanReadRecommendationModelSummary() throws Exception {
        when(recommendationLearningService.modelSummary()).thenReturn(Map.of(
                "latestModel", Map.of(
                        "modelName", "xgboost-reranker",
                        "modelVersion", "xgb-20260703100000",
                        "status", "SHADOW"
                ),
                "homeParts", Map.of(
                        "windowDays", 7,
                        "impressions", 10,
                        "clicks", 2,
                        "ctr", 0.2,
                        "scoreSources", List.of(Map.of("scoreSource", "XGBOOST", "count", 10, "share", 1.0)),
                        "recentShadowScores", 40,
                        "recentCandidates", List.of(Map.of(
                                "partId", "part-public-id",
                                "category", "GPU",
                                "name", "RTX 5090",
                                "price", 4_000_000
                        ))
                ),
                "generatedAt", "2026-07-03T10:00:00Z"
        ));

        mockMvc.perform(get("/api/admin/recommendation-models/summary")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestModel.modelVersion").value("xgb-20260703100000"))
                .andExpect(jsonPath("$.homeParts.impressions").value(10))
                .andExpect(jsonPath("$.homeParts.scoreSources[0].scoreSource").value("XGBOOST"));
    }

    @Test
    void trainingOverviewRequiresAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/recommendation-training/overview")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(recommendationTrainingService);
    }

    @Test
    void adminCanReadTrainingOverview() throws Exception {
        when(recommendationTrainingService.overview()).thenReturn(MockData.map(
                "eligibleEvents", 12,
                "trainedDistinctEvents", 4,
                "untrainedEligibleEvents", 8,
                "excludedDatasetItems", 1
        ));

        mockMvc.perform(get("/api/admin/recommendation-training/overview")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleEvents").value(12))
                .andExpect(jsonPath("$.untrainedEligibleEvents").value(8));
    }

    @Test
    void adminCanCreateTrainingDataset() throws Exception {
        when(recommendationTrainingService.createDataset(Map.of("name", "dataset"), ADMIN)).thenReturn(MockData.map(
                "id", "dataset-public-id",
                "name", "dataset",
                "status", "DRAFT",
                "eligibleCount", 10,
                "includedCount", 10
        ));

        mockMvc.perform(post("/api/admin/recommendation-training-datasets")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"dataset\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("dataset-public-id"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(recommendationTrainingService).createDataset(Map.of("name", "dataset"), ADMIN);
    }

    @Test
    void adminCanCreateTrainingJob() throws Exception {
        when(recommendationTrainingService.createJob(Map.of("datasetId", "dataset-public-id"), ADMIN)).thenReturn(MockData.map(
                "id", "job-public-id",
                "datasetId", "dataset-public-id",
                "status", "QUEUED"
        ));

        mockMvc.perform(post("/api/admin/recommendation-training-jobs")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetId\":\"dataset-public-id\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }
}
