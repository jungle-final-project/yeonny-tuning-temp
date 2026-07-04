package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.agent.AiChatActionType;
import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
import com.buildgraph.prototype.agent.PartReplacementRanker;
import com.buildgraph.prototype.part.PartAliasReviewService;
import com.buildgraph.prototype.part.ToolBuildPart;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.recommendation.CandidateReranker;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class BuildChatServiceTest {
    @Test
    void parsesBudgetWonFromCommonKoreanInputs() {
        assertThat(BuildChatService.parseBudgetWon("200만원 PC 추천")).isEqualTo(200 * 10_000);
        assertThat(BuildChatService.parseBudgetWon("3백만원 PC 추천")).isEqualTo(300 * 10_000);
        assertThat(BuildChatService.parseBudgetWon("300만원대로 맞춰줘")).isEqualTo(3_000_000);
        assertThat(BuildChatService.parseBudgetWon("2,000,000원 안에서")).isEqualTo(200 * 10_000);
        assertThat(BuildChatService.budgetIntent("800만원으로 최고급 PC 추천해줘").mode()).isEqualTo("TARGET");
        assertThat(BuildChatService.budgetIntent("300만원 이하 RTX 5090 PC").mode()).isEqualTo("MAX");
        assertThat(BuildChatService.budgetIntent("300만원 이상으로 게임용 PC 맞춰줘").mode()).isEqualTo("MIN");
    }

    @Test
    void detectsPartQuestionCategories() {
        assertThat(BuildChatService.detectPartCategory("GPU 추천해줘")).isEqualTo("GPU");
        assertThat(BuildChatService.detectPartCategory("CPU는 뭐가 좋아?")).isEqualTo("CPU");
        assertThat(BuildChatService.detectPartCategory("쿨러 추천")).isEqualTo("COOLER");
    }

    @Test
    void buildChatUsesLlmRequiredEngineAndKeepsLegacyBuildShape() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "price",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "저장된 현재가 기준 예산 안에 들어옵니다."
        )));

        Map<String, Object> response = service.chat(Map.of("message", "200만원 QHD 게임용 PC 추천해줘"));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("builds")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        Map<String, Object> build = builds.get(0);
        assertThat(build).containsEntry("title", "QHD 균형 추천 조합");
        assertThat(build).containsEntry("totalPrice", 1_900_000);
        assertThat(build.get("items")).asList().hasSize(3);
        assertThat(build.get("toolResults")).asList().hasSize(1);
        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void buildChatFiltersTargetBudgetBuildsOutsideAllowedBandWhenNoHardConstraint() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(overBudgetTargetResponse(false));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "price",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "Tool 자체는 통과했습니다."
        )));

        Map<String, Object> response = service.chat(Map.of("message", "800만원으로 최고급 PC 추천해줘"));

        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("warnings")).asList().contains("명시 예산 범위를 벗어난 추천 조합을 제외했습니다.");
    }

    @Test
    void buildChatUsesRawMessageBudgetWhenLlmParsedContextMissesBudget() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(overBudgetNoParsedBudgetResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "price",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "Tool 자체는 통과했습니다."
        )));

        Map<String, Object> response = service.chat(Map.of("message", "800만원짜리 컴퓨터 추천해줘"));

        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("warnings")).asList().contains("명시 예산 범위를 벗어난 추천 조합을 제외했습니다.");
    }

    @Test
    void buildChatAllowsHardConstraintOverBudgetAndAddsWarningCode() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(overBudgetTargetResponse(true));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "price",
                "status", "WARN",
                "confidence", "HIGH",
                "summary", "예산을 초과했습니다."
        )));

        Map<String, Object> response = service.chat(Map.of("message", "300만원 이하 RTX 5090 PC 추천해줘"));

        assertThat(response.get("builds")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds.get(0).get("warnings")).asList()
                .contains("HARD_CONSTRAINT_OVER_BUDGET", "명시한 부품 조건을 지키기 위해 예산을 초과했습니다.");
    }

    @Test
    void buildChatReturnsDraftRemoveActionWhenCurrentQuoteDraftIsProvided() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "GPU 빼줘",
                "currentQuoteDraft", draftWithItems(List.of(draftItem("part-gpu-1", "GPU", "RTX 5070", 1, Map.of())))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).containsEntry("type", "REMOVE_DRAFT_PART");
        assertThat(actions.get(0)).containsEntry("requiresConfirmation", false);
        assertThat(actions.get(0).get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("partId", "part-gpu-1")
                .containsEntry("category", "GPU");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatReturnsOpenRouteFastPathWithoutCallingLlmOrCache() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "GPU 보여줘"));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response).containsEntry("message", "GPU 부품 화면으로 이동했습니다.");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).singleElement()
                .satisfies(action -> {
                    assertThat(action)
                            .containsEntry("type", "OPEN_ROUTE")
                            .containsEntry("requiresConfirmation", false);
                    assertThat(action.get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsEntry("route", "/self-quote?category=GPU")
                            .containsEntry("source", "AI_BUILD_CHAT");
                });
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatPublishesEngineOpenRouteActionWhenLocalFastRouteDoesNotMatch() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(new AiChatEngineResponse(
                "저장한 조합 목록으로 이동하겠습니다.",
                AiChatIntent.ASK_FOLLOW_UP,
                List.of(new AiChatAction(
                        AiChatActionType.OPEN_ROUTE,
                        "내 견적함 열기",
                        Map.of("route", "/my/quotes", "source", "AI_CHAT_ENGINE_LLM")
                )),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                null
        ));

        Map<String, Object> response = service.chat(Map.of("message", "지난번 만든 조합 목록 열어줘"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).singleElement()
                .satisfies(action -> {
                    assertThat(action).containsEntry("type", "OPEN_ROUTE");
                    assertThat(action.get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsEntry("route", "/my/quotes")
                            .containsEntry("source", "AI_BUILD_CHAT");
                });
        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void buildChatDoesNotFastRouteCartMutationCommands() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        service.chat(Map.of("message", "추천 조합 장바구니에 넣어줘"));

        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void buildChatRecordsShadowScoresAfterGeneratingFreshAiResponse() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = BuildChatCacheService.disabled();
        CandidateReranker candidateReranker = mock(CandidateReranker.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService, candidateReranker);
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        Map<String, Object> response = service.chat(Map.of("message", "200만원 게임용 PC 추천"));

        assertThat(response).containsEntry("answerType", "BUDGET");
        verify(candidateReranker).recordShadowScores(anyMap(), anyMap(), eq(null), eq(null));
    }

    @Test
    void buildChatFastRoutesSingleProductDetailWithoutCallingLlmOrCache() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("GPU"),
                eq("5090"),
                eq("5090"),
                eq("5090")
        )).thenReturn(List.of(Map.of(
                "id", "00000000-0000-4000-8000-000000005090",
                "category", "GPU",
                "name", "ASUS ROG Astral GeForce RTX 5090 OC 32GB",
                "manufacturer", "ASUS"
        )));

        Map<String, Object> response = service.chat(Map.of("message", "ASUS Astral 5090 상세 보여줘"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).singleElement()
                .satisfies(action -> {
                    assertThat(action).containsEntry("type", "OPEN_ROUTE");
                    assertThat(action.get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsEntry("route", "/parts/00000000-0000-4000-8000-000000005090")
                            .containsEntry("source", "AI_BUILD_CHAT");
                });
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatFastRoutesExactKoreanProductDetailBeforePrefixMatch() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("CPU"),
                eq("9950X3D"),
                eq("9950X3D"),
                eq("9950X3D")
        )).thenReturn(List.of(
                Map.of(
                        "id", "a75d6544-2296-4c4c-a7cd-64596e66f6d7",
                        "category", "CPU",
                        "name", "AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩)",
                        "manufacturer", "AMD"
                ),
                Map.of(
                        "id", "4d3f5a5f-4580-4a1c-a514-be7b34ac97c9",
                        "category", "CPU",
                        "name", "AMD 라이젠9-6세대 9950X3D2 Dual Edition 그래니트 릿지 정품(멀티팩)",
                        "manufacturer", "AMD"
                )
        ));

        Map<String, Object> response = service.chat(Map.of("message", "AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩) 상세페이지로 이동해"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).singleElement()
                .satisfies(action -> assertThat(action.get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("route", "/parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7"));
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatFallsBackToCategoryForShortModelTokenEvenWhenSingleActivePartMatches() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("CPU"),
                eq("9950X3D"),
                eq("9950X3D"),
                eq("9950X3D")
        )).thenReturn(List.of(Map.of(
                "id", "a75d6544-2296-4c4c-a7cd-64596e66f6d7",
                "category", "CPU",
                "name", "AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩)",
                "manufacturer", "AMD"
        )));

        Map<String, Object> response = service.chat(Map.of("message", "9950X3D 상세페이지 보여줘"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).singleElement()
                .satisfies(action -> assertThat(action.get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("route", "/self-quote?category=CPU&q=9950X3D"));
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatFallsBackToCategoryForAmbiguousProductDetailRequests() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("GPU"),
                eq("5090"),
                eq("5090"),
                eq("5090")
        )).thenReturn(List.of(
                Map.of(
                        "id", "00000000-0000-4000-8000-000000005090",
                        "category", "GPU",
                        "name", "ASUS ROG Astral GeForce RTX 5090 OC 32GB",
                        "manufacturer", "ASUS"
                ),
                Map.of(
                        "id", "00000000-0000-4000-8000-000000005091",
                        "category", "GPU",
                        "name", "MSI GeForce RTX 5090 SUPRIM 32GB",
                        "manufacturer", "MSI"
                )
        ));

        Map<String, Object> response = service.chat(Map.of("message", "5090 보여줘"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).singleElement()
                .satisfies(action -> assertThat(action.get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("route", "/self-quote?category=GPU&q=5090"));
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatReturnsNoDraftPartCandidateFastPathWithoutRoutingOrCallingLlm() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(anyString(), eq("MOTHERBOARD"), eq("msi"), eq("msi"), eq(80))).thenReturn(List.of(
                partRow("board-msi-x870", "MOTHERBOARD", "MSI MAG X870E TOMAHAWK WIFI", 540_000,
                        Map.of("chipset", "X870E", "socket", "AM5", "toolReady", true), 92)
        ));

        Map<String, Object> response = service.chat(Map.of("message", "메인보드 MSI 걸로 맞춰줘"));

        assertThat(response).containsEntry("answerType", "PART");
        @SuppressWarnings("unchecked")
        Map<String, Object> partRecommendation = (Map<String, Object>) response.get("partRecommendation");
        assertThat(partRecommendation).containsEntry("category", "MOTHERBOARD");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).singleElement().satisfies(action -> {
            assertThat(action).containsEntry("type", "ADD_PART_TO_DRAFT");
            assertThat(action.get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("partId", "board-msi-x870")
                    .containsEntry("category", "MOTHERBOARD")
                    .containsEntry("intentConfidence", "MEDIUM")
                    .containsEntry("sideEffectRisk", "MEDIUM");
        });
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatPassesRequestedAiProfileToEngine() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST"))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        service.chat(Map.of("message", "5090 글카 들어간 PC 추천해줘"), "BUILD_CHAT_54_MINI_FAST");

        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST"));
    }

    @Test
    void buildChatReturnsDraftQuantityActionForRamCapacityRequest() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "RAM 64GB로 바꿔줘",
                "currentQuoteDraft", draftWithItems(List.of(draftItem("part-ram-1", "RAM", "DDR5 32GB Kit", 1, Map.of("capacityGb", 32))))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0)).containsEntry("type", "UPDATE_DRAFT_QUANTITY");
        assertThat(actions.get(0).get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("partId", "part-ram-1")
                .containsEntry("quantity", 2);
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatReturnsDraftReplacementFastPathWithoutCallingLlmOrCache() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartReplacementRanker ranker = new PartReplacementRanker(mock(PartAliasReviewService.class));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService, ranker);
        when(jdbcTemplate.queryForList(anyString(), eq("GPU"), eq(200))).thenReturn(List.of(
                partRow("gpu-5070", "GPU", "RTX 5070", 900_000, Map.of("gpuClass", "RTX_5070", "vramGb", 12), 72),
                partRow("gpu-5080", "GPU", "RTX 5080", 1_700_000, Map.of("gpuClass", "RTX_5080", "vramGb", 16), 88),
                partRow("gpu-5090", "GPU", "RTX 5090", 3_000_000, Map.of("gpuClass", "RTX_5090", "vramGb", 32), 100)
        ));

        Map<String, Object> response = service.chat(Map.of(
                "message", "그래픽카드 더 좋은 걸로 바꿔줘",
                "currentQuoteDraft", draftWithItems(List.of(draftItem(
                        "gpu-current",
                        "GPU",
                        "RTX 5070 Ti",
                        1,
                        Map.of("gpuClass", "RTX_5070_TI", "vramGb", 16)
                )))
        ));

        assertThat(response).containsEntry("answerType", "PART");
        @SuppressWarnings("unchecked")
        Map<String, Object> partRecommendation = (Map<String, Object>) response.get("partRecommendation");
        assertThat(partRecommendation).containsEntry("category", "GPU");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) partRecommendation.get("options");
        assertThat(options)
                .extracting(option -> option.get("partId"))
                .containsExactly("gpu-5080", "gpu-5090");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        assertThat(actions).singleElement()
                .satisfies(action -> assertThat(action)
                        .containsEntry("type", "REPLACE_DRAFT_PART")
                        .containsEntry("requiresConfirmation", false));
        assertThat(actions.get(0).get("payload")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("partId", "gpu-5080")
                .containsEntry("category", "GPU");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatSimulatesGpuFrameImpactWithoutApplyingDraftAction() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts p")) {
                return List.of(partRow("gpu-5080", "GPU", "RTX 5080", 1_700_000, Map.of("gpuClass", "RTX_5080", "hardwareClass", "RTX_5080"), 88));
            }
            if (sql.contains("FROM game_fps_benchmarks")) {
                return List.of(Map.of(
                        "game_title", "PUBG",
                        "game_key", "pubg",
                        "resolution", "QHD",
                        "graphics_preset", "HIGH",
                        "avg_fps", 180,
                        "one_percent_low_fps", 130,
                        "source_name", "HowManyFPS",
                        "confidence", "MEDIUM",
                        "metadata", Map.of("gpuClass", "RTX_5080", "cpuClass", "RYZEN_9_9950X")
                ));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "size",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "장착 가능"
        )));

        Map<String, Object> response = service.chat(Map.of(
                "message", "지금 견적에서 그래픽카드를 5080으로 바꾸면 프레임이 어떻게되?",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cpu-current", "CPU", "Ryzen 9 9950X", 1, Map.of("cpuClass", "RYZEN_9_9950X", "hardwareClass", "RYZEN_9_9950X")),
                        draftItem("gpu-current", "GPU", "RTX 5070 Ti", 1, Map.of("gpuClass", "RTX_5070_TI", "hardwareClass", "RTX_5070_TI")),
                        draftItem("case-current", "CASE", "Airflow Case", 1, Map.of("maxGpuLengthMm", 380)),
                        draftItem("psu-current", "PSU", "1000W PSU", 1, Map.of("capacityW", 1000))
                ))
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("message").toString())
                .contains("RTX 5080")
                .contains("벤치마크")
                .doesNotContain("내부")
                .doesNotContain("normalized")
                .doesNotContain("DB");
        assertThat(response.get("simulation")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "PERFORMANCE_COMPARISON")
                .containsEntry("category", "GPU");
        @SuppressWarnings("unchecked")
        Map<String, Object> simulation = (Map<String, Object>) response.get("simulation");
        assertThat(simulation.get("currentPart")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "RTX 5070 Ti");
        assertThat(simulation.get("targetPart")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "RTX 5080");
        assertThat(simulation.get("fpsComparisons")).asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("gameTitle", "PUBG")
                .containsEntry("resolution", "QHD")
                .containsEntry("targetFps", 180.0);
        assertThat(response.get("actions")).asList().isEmpty();
        assertThat(response.get("partRecommendation")).isNull();
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatSimulatesNonGpuPartWithSpecComparisonCard() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts p")) {
                return List.of(partRow("ram-64", "RAM", "DDR5 64GB Kit", 240_000, Map.of(
                        "capacityGb", 64,
                        "moduleCount", 2,
                        "speedMhz", 6000,
                        "memoryType", "DDR5"
                ), 92));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "compatibility",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "호환 가능"
        )));

        Map<String, Object> response = service.chat(Map.of(
                "message", "RAM 64GB로 바꾸면 성능이 어떻게 돼?",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cpu-current", "CPU", "Ryzen 7 9700X", 1, Map.of("cpuClass", "RYZEN_7_9700X")),
                        draftItem("board-current", "MOTHERBOARD", "B850 Board", 1, Map.of("memoryType", "DDR5")),
                        draftItem("ram-current", "RAM", "DDR5 32GB Kit", 1, Map.of(
                                "capacityGb", 32,
                                "moduleCount", 2,
                                "speedMhz", 5600,
                                "memoryType", "DDR5"
                        ))
                ))
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("message").toString())
                .contains("RAM")
                .contains("주요 스펙")
                .doesNotContain("내부")
                .doesNotContain("normalized")
                .doesNotContain("DB");
        assertThat(response.get("simulation")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "PERFORMANCE_COMPARISON")
                .containsEntry("category", "RAM");
        @SuppressWarnings("unchecked")
        Map<String, Object> simulation = (Map<String, Object>) response.get("simulation");
        assertThat(simulation.get("fpsComparisons")).asList().isEmpty();
        assertThat(simulation.get("specComparisons")).asList()
                .anySatisfy(row -> assertThat(row)
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("label", "총 용량")
                        .containsEntry("currentValue", "32GB")
                        .containsEntry("targetValue", "64GB")
                        .containsEntry("deltaText", "+32GB"));
        assertThat(response.get("actions")).asList().isEmpty();
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatTreatsShortCpuWhatIfAsReadOnlySimulation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts p")) {
                return List.of(partRow("cpu-9700x", "CPU", "AMD Ryzen 7 9700X", 377_500, Map.of(
                        "cpuClass", "RYZEN_7_9700X",
                        "coreCount", 8,
                        "threadCount", 16,
                        "tdpW", 65
                ), 84));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "compatibility",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "호환 가능"
        )));

        Map<String, Object> response = service.chat(Map.of(
                "message", "지금 견적에서 cpu를 9700x 로바꾸면?",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cpu-current", "CPU", "Ryzen 9 9950X3D", 1, Map.of(
                                "cpuClass", "RYZEN_9_9950X3D",
                                "coreCount", 16,
                                "threadCount", 32,
                                "tdpW", 120
                        )),
                        draftItem("board-current", "MOTHERBOARD", "B850 Board", 1, Map.of("socket", "AM5"))
                ))
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("simulation")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "PERFORMANCE_COMPARISON")
                .containsEntry("category", "CPU");
        @SuppressWarnings("unchecked")
        Map<String, Object> simulation = (Map<String, Object>) response.get("simulation");
        assertThat(simulation.get("targetPart")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "AMD Ryzen 7 9700X");
        assertThat(simulation.get("specComparisons")).asList()
                .anySatisfy(row -> assertThat(row)
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("label", "코어")
                        .containsEntry("currentValue", "16개")
                        .containsEntry("targetValue", "8개"));
        assertThat(response.get("actions")).asList().isEmpty();
        assertThat(response.get("partRecommendation")).isNull();
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void partQuestionWithoutCurrentBuildsKeepsBuildsEmptyAndReturnsPartRecommendation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(partResponse());

        Map<String, Object> response = service.chat(Map.of("message", "GPU 추천해줘"));

        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("partRecommendation")).isNotNull();
    }

    @Test
    void partQuestionWithCurrentBuildsReturnsChangedBuildPreviewAndPartRecommendation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(partResponse());
        stubCurrentBuildParts(jdbcTemplate);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        Map<String, Object> response = service.chat(Map.of(
                "message", "그래픽카드 추천해줘",
                "currentBuilds", currentBuilds("gpu-current")
        ));

        assertThat(response).containsEntry("answerType", "PART");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).hasSize(1);
        assertThat(builds.get(0).get("appliedPartCategories")).asList().containsExactly("GPU");
        assertThat(response.get("partRecommendation")).isNotNull();
    }

    @Test
    void buildModifyWithCurrentBuildsReturnsChangedBuildPreview() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(modifyResponse());
        stubCurrentBuildParts(jdbcTemplate);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        Map<String, Object> response = service.chat(Map.of(
                "message", "방금 견적에서 그래픽카드 더 싼 걸로 바꿔줘",
                "currentBuilds", currentBuilds("gpu-current")
        ));

        assertThat(response).containsEntry("answerType", "PART");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).hasSize(1);
        assertThat(builds.get(0).get("appliedPartCategories")).asList().containsExactly("GPU");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) builds.get(0).get("items");
        assertThat(items.stream().map(item -> String.valueOf(item.get("partId"))).toList())
                .contains("gpu-1")
                .doesNotContain("gpu-current");
    }

    @Test
    void partQuestionUsesExplicitRamSingleQuantityInResponseOptions() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(singleRamResponse());

        Map<String, Object> response = service.chat(Map.of("message", "램 32기가 한 개 달린 거 추천해줘"));

        @SuppressWarnings("unchecked")
        Map<String, Object> partRecommendation = (Map<String, Object>) response.get("partRecommendation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) partRecommendation.get("options");
        assertThat(options).singleElement()
                .satisfies(option -> assertThat(option)
                        .containsEntry("partId", "ram-32-single")
                        .containsEntry("quantity", 1));
    }

    @Test
    void buildChatExcludesPartRecommendationAndDraftActionWhenToolReturnsBlockingFail() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(partResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ToolBuildPart> parts = (List<ToolBuildPart>) invocation.getArgument(0);
            boolean unsafeGpu = parts.stream().anyMatch(part -> "gpu-1".equals(part.publicId()));
            return List.of(Map.of(
                    "tool", "size",
                    "status", unsafeGpu ? "FAIL" : "PASS",
                    "confidence", "HIGH",
                    "summary", unsafeGpu
                            ? "케이스 장착 한계를 초과해 해당 조합은 장착할 수 없습니다."
                            : "GPU 길이와 쿨러 높이가 케이스 제약 안에 있습니다."
            ));
        });

        Map<String, Object> response = service.chat(Map.of(
                "message", "그래픽카드 더 좋은 걸로 추천해줘",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("part-gpu-current", "GPU", "RTX 5070", 1, Map.of()),
                        draftItem("part-case-current", "CASE", "Compact Case", 1, Map.of("maxGpuLengthMm", 330))
                ))
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> partRecommendation = (Map<String, Object>) response.get("partRecommendation");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) partRecommendation.get("options");
        assertThat(options)
                .extracting(option -> option.get("partId"))
                .containsExactly("gpu-2", "gpu-3");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) response.get("actions");
        List<String> actionPartIds = actions.stream()
                .map(action -> (Map<?, ?>) action.get("payload"))
                .map(payload -> String.valueOf(payload.get("partId")))
                .toList();
        assertThat(actionPartIds).containsExactly("gpu-2");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) response.get("warnings");
        assertThat(warnings).contains("Tool FAIL 후보 1개를 추천/적용 후보에서 제외했습니다.");
    }

    @Test
    void missingOpenAiKeyPropagatesPreconditionRequired() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다."));

        assertThatThrownBy(() -> service.chat(Map.of("message", "200만원 PC 추천")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    void buildChatReturnsCachedResponseWithoutCallingLlmAndScopesCacheByUser() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(42L, "user-id", "user@example.com", "사용자", "USER", null);
        Map<String, Object> cached = new LinkedHashMap<>();
        cached.put("answerType", "PART");
        cached.put("message", "캐시된 응답");
        cached.put("builds", List.of());
        cached.put("partRecommendation", Map.of());
        cached.put("actions", List.of());
        cached.put("warnings", List.of());
        cached.put("evidenceIds", List.of());
        cached.put("agentSessionId", null);
        when(cacheService.lookup(anyMap(), eq("BUILD_CHAT_54_MINI_FAST"), eq(42L))).thenReturn(Optional.of(cached));

        Map<String, Object> response = service.chat(Map.of("message", "그래픽카드 더 싼 걸로 추천해줘"), "BUILD_CHAT_54_MINI_FAST", user);

        assertThat(response).containsEntry("message", "캐시된 응답");
        assertThat(response).containsEntry("agentSessionId", null);
        assertThat(response.get("evidenceIds")).asList().isEmpty();
        verify(cacheService).lookup(anyMap(), eq("BUILD_CHAT_54_MINI_FAST"), eq(42L));
        verifyNoInteractions(aiChatEngine);
    }

    private static AiChatEngineResponse buildResponse() {
        return new AiChatEngineResponse(
                "LLM/RAG로 조건을 분석해 추천 조합을 만들었습니다.",
                AiChatIntent.FULL_BUILD_RECOMMEND,
                List.<AiChatAction>of(),
                List.of(new AiChatEngineResponse.BuildRecommendation(
                        "QHD 균형 추천 조합",
                        "QHD 게임",
                        "내부 자산 기준 균형 조합입니다.",
                        1_980_000,
                        "HIGH",
                        List.of(
                                part("CPU", "cpu-1", 420_000),
                                part("RAM", "ram-1", 140_000),
                                part("GPU", "gpu-1", 1_200_000)
                        )
                )),
                List.of(),
                Map.of(
                        "budget", 2_000_000,
                        "budgetPolicy", "USER_BUDGET",
                        "hardConstraintPolicy", "NONE"
                ),
                List.of("evidence-1"),
                List.of(),
                null
        );
    }

    private static AiChatEngineResponse overBudgetTargetResponse(boolean hardConstraint) {
        return new AiChatEngineResponse(
                "조건을 분석해 추천 조합을 만들었습니다.",
                AiChatIntent.FULL_BUILD_RECOMMEND,
                List.<AiChatAction>of(),
                List.of(new AiChatEngineResponse.BuildRecommendation(
                        "고가 추천 조합",
                        "예산 기준",
                        "과예산 조합입니다.",
                        12_000_000,
                        "MEDIUM",
                        List.of(
                                part("CPU", "cpu-expensive", 1_500_000),
                                part("RAM", "ram-expensive", 1_000_000),
                                part("GPU", "gpu-expensive", 8_500_000)
                        )
                )),
                List.of(),
                hardConstraint
                        ? Map.of(
                                "budget", 3_000_000,
                                "budgetMode", "MAX",
                                "budgetPolicy", "USER_BUDGET",
                                "hardConstraintPolicy", "MUST_INCLUDE",
                                "requiredGpuClasses", List.of("RTX_5090"),
                                "requiredPartKeywords", List.of("RTX 5090")
                        )
                        : Map.of(
                                "budget", 8_000_000,
                                "budgetMode", "TARGET",
                                "budgetPolicy", "USER_BUDGET",
                                "hardConstraintPolicy", "NONE"
                        ),
                List.of("evidence-1"),
                List.of(),
                null
        );
    }

    private static AiChatEngineResponse overBudgetNoParsedBudgetResponse() {
        return new AiChatEngineResponse(
                "조건을 분석해 추천 조합을 만들었습니다.",
                AiChatIntent.FULL_BUILD_RECOMMEND,
                List.<AiChatAction>of(),
                List.of(new AiChatEngineResponse.BuildRecommendation(
                        "고가 추천 조합",
                        "예산 누락",
                        "LLM이 예산을 누락한 과예산 조합입니다.",
                        12_000_000,
                        "MEDIUM",
                        List.of(
                                part("CPU", "cpu-expensive", 1_500_000),
                                part("RAM", "ram-expensive", 1_000_000),
                                part("GPU", "gpu-expensive", 8_500_000)
                        )
                )),
                List.of(),
                Map.of("hardConstraintPolicy", "NONE"),
                List.of("evidence-1"),
                List.of(),
                null
        );
    }

    private static AiChatEngineResponse partResponse() {
        return new AiChatEngineResponse(
                "GPU 후보를 정리했습니다.",
                AiChatIntent.PART_RECOMMEND,
                List.<AiChatAction>of(),
                List.of(),
                List.of(
                        part("GPU", "gpu-1", 1_200_000),
                        part("GPU", "gpu-2", 900_000),
                        part("GPU", "gpu-3", 700_000)
                ),
                Map.of("category", "GPU"),
                List.of("evidence-1"),
                List.of(),
                null
        );
    }

    private static AiChatEngineResponse modifyResponse() {
        return new AiChatEngineResponse(
                "현재 견적 기준으로 GPU 교체 후보를 정리했습니다.",
                AiChatIntent.BUILD_MODIFY,
                List.<AiChatAction>of(),
                List.of(),
                List.of(
                        part("GPU", "gpu-1", 900_000),
                        part("GPU", "gpu-2", 800_000),
                        part("GPU", "gpu-3", 700_000)
                ),
                Map.of("category", "GPU", "draftEdit", Map.of("category", "GPU", "operation", "REPLACE", "priceDirection", "CHEAPER")),
                List.of("evidence-1"),
                List.of(),
                null
        );
    }

    private static AiChatEngineResponse singleRamResponse() {
        return new AiChatEngineResponse(
                "램 32GB 1개 구성으로 추천해드릴게요.",
                AiChatIntent.PART_RECOMMEND,
                List.<AiChatAction>of(),
                List.of(),
                List.of(new AiChatEngineResponse.PartRecommendation(
                        "ram-32-single",
                        "RAM",
                        "Samsung DDR5 32GB UDIMM",
                        "Samsung",
                        117_4250,
                        Map.of("toolReady", true, "shortSpec", "32GB x1, DDR5-5600", "capacityGb", 32, "moduleCount", 1)
                )),
                Map.of("category", "RAM", "targetCapacityGb", 32, "targetModuleCount", 1, "targetQuantity", 1),
                List.of("evidence-1"),
                List.of(),
                null
        );
    }

    private static AiChatEngineResponse.PartRecommendation part(String category, String id, int price) {
        return new AiChatEngineResponse.PartRecommendation(
                id,
                category,
                category + " test part",
                "BuildGraph",
                price,
                Map.of("toolReady", true, "shortSpec", category + " spec")
        );
    }

    private static Map<String, Object> draftWithItems(List<Map<String, Object>> items) {
        return Map.of(
                "id", "draft-test",
                "status", "ACTIVE",
                "name", "셀프 견적",
                "items", items,
                "totalPrice", 0,
                "itemCount", items.size()
        );
    }

    private static Map<String, Object> draftItem(
            String partId,
            String category,
            String name,
            int quantity,
            Map<String, Object> attributes
    ) {
        return Map.of(
                "id", "draft-item-" + partId,
                "partId", partId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "quantity", quantity,
                "unitPriceAtAdd", 100_000,
                "currentPrice", 100_000,
                "lineTotal", 100_000 * quantity,
                "attributes", attributes
        );
    }

    private static List<Map<String, Object>> currentBuilds(String gpuPartId) {
        return List.of(Map.of(
                "id", "ai-engine-current",
                "tier", "balanced",
                "budgetWon", 2_000_000,
                "budgetLabel", "200만원",
                "items", List.of(
                        Map.of("partId", "cpu-current", "category", "CPU", "quantity", 1),
                        Map.of("partId", gpuPartId, "category", "GPU", "quantity", 1),
                        Map.of("partId", "ram-current", "category", "RAM", "quantity", 1)
                ),
                "appliedPartCategories", List.of()
        ));
    }

    private static void stubCurrentBuildParts(JdbcTemplate jdbcTemplate) {
        Map<String, Map<String, Object>> rows = Map.of(
                "cpu-current", partRow("cpu-current", "CPU", "CPU current part", 420_000, Map.of("toolReady", true, "shortSpec", "CPU spec"), 50),
                "gpu-current", partRow("gpu-current", "GPU", "GPU current part", 1_200_000, Map.of("toolReady", true, "shortSpec", "GPU spec"), 70),
                "ram-current", partRow("ram-current", "RAM", "RAM current part", 140_000, Map.of("toolReady", true, "shortSpec", "RAM spec"), 30)
        );
        when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenAnswer(invocation -> {
            Object rawArgument = invocation.getArgument(1);
            Object publicId = rawArgument instanceof Object[] arguments ? arguments[0] : rawArgument;
            Map<String, Object> row = rows.get(String.valueOf(publicId));
            return row == null ? List.of() : List.of(row);
        });
    }

    private static Map<String, Object> partRow(
            String partId,
            String category,
            String name,
            int price,
            Map<String, Object> attributes,
            int benchmarkScore
    ) {
        return Map.of(
                "id", partId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "attributes", attributes,
                "benchmark_score", benchmarkScore,
                "benchmark_summary", name + " benchmark"
        );
    }
}
