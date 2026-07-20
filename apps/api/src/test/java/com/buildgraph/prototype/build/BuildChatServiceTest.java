package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.agent.AiChatActionType;
import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.SupportGuidanceDraft;
import com.buildgraph.prototype.agent.AiChatIntent;
import com.buildgraph.prototype.agent.PartReplacementRanker;
import com.buildgraph.prototype.agent.PartRouteResolver;
import com.buildgraph.prototype.part.catalog.PartCompatibleCandidateService;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import com.buildgraph.prototype.recommendation.CandidateReranker;
import com.buildgraph.prototype.recommendation.NoopCandidateReranker;
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
    void homeRecommendationRequiresCompleteBudgetSafeToolValidatedBuild() {
        List<Map<String, Object>> completeItems = List.of(
                Map.of("category", "CPU"),
                Map.of("category", "MOTHERBOARD"),
                Map.of("category", "RAM"),
                Map.of("category", "GPU"),
                Map.of("category", "STORAGE"),
                Map.of("category", "PSU"),
                Map.of("category", "CASE"),
                Map.of("category", "COOLER")
        );
        List<Map<String, Object>> completeToolResults = List.of(
                Map.of("tool", "compatibility", "status", "PASS"),
                Map.of("tool", "power", "status", "PASS"),
                Map.of("tool", "size", "status", "WARN"),
                Map.of("tool", "performance", "status", "PASS"),
                Map.of("tool", "price", "status", "PASS")
        );
        Map<String, Object> valid = Map.of(
                "totalPrice", 1_950_000,
                "items", completeItems,
                "toolResults", completeToolResults
        );
        Map<String, Object> blocking = Map.of(
                "totalPrice", 1_950_000,
                "items", completeItems,
                "toolResults", List.of(Map.of("tool", "size", "status", "FAIL"))
        );
        Map<String, Object> incomplete = Map.of(
                "totalPrice", 1_950_000,
                "items", completeItems.subList(0, 7),
                "toolResults", List.of()
        );
        Map<String, Object> outsideBudget = Map.of(
                "totalPrice", 3_000_000,
                "items", completeItems,
                "toolResults", completeToolResults
        );
        Map<String, Object> notFullyChecked = Map.of(
                "totalPrice", 1_950_000,
                "items", completeItems,
                "toolResults", List.of(Map.of("tool", "compatibility", "status", "PASS"))
        );

        assertThat(BuildChatService.isSafeCompleteHomeBuild(valid)).isTrue();
        assertThat(BuildChatService.isSafeCompleteHomeBuild(blocking)).isFalse();
        assertThat(BuildChatService.isSafeCompleteHomeBuild(incomplete)).isFalse();
        assertThat(BuildChatService.isSafeCompleteHomeBuild(outsideBudget)).isFalse();
        assertThat(BuildChatService.isSafeCompleteHomeBuild(notFullyChecked)).isFalse();
    }

    @Test
    void alignsRecommendationMessageWithActualBuildCardCount() {
        Map<String, Object> oneBuild = new LinkedHashMap<>();
        oneBuild.put("message", "내부 자산 기준으로 추천 조합 3개를 구성했습니다.");
        oneBuild.put("builds", List.of(Map.of("id", "build-1")));
        BuildChatService.alignBuildCountMessage(oneBuild);

        Map<String, Object> twoBuilds = new LinkedHashMap<>();
        twoBuilds.put("message", "3개의 추천 PC를 준비했습니다.");
        twoBuilds.put("builds", List.of(Map.of("id", "build-1"), Map.of("id", "build-2")));
        BuildChatService.alignBuildCountMessage(twoBuilds);

        assertThat(oneBuild.get("message")).isEqualTo("내부 자산 기준으로 추천 조합 1개를 구성했습니다.");
        assertThat(twoBuilds.get("message")).isEqualTo("2개의 추천 PC를 준비했습니다.");
    }

    @Test
    void shoppingSymptomGuidanceFallsBackToStaticProfileWhenLlmUnavailable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of("message", "게임하다 화면이 자꾸 멈춰"));

        assertThat(response).containsEntry("answerType", "GENERAL").containsEntry("simulation", null);
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response).doesNotContainKeys("actions", "causeCandidates", "riskLevel", "supportDecision");
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "게임하다 화면이 자꾸 멈춰");
        assertThat(response.get("supportGuidance"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "PC_AGENT_DIAGNOSTIC_ENTRY")
                .containsEntry("scope", "PRE_DIAGNOSIS")
                .containsEntry("symptomCategory", "DISPLAY_FREEZE")
                .satisfies(guidance -> {
                    assertThat(guidance).doesNotContainKeys("causeCandidates", "riskLevel", "supportDecision", "rawSamples");
                    assertThat(guidance.get("possibleCauses")).asList()
                            .contains("그래픽 드라이버 충돌", "GPU 온도 또는 부하 불안정")
                            .hasSizeBetween(1, 4);
                    assertThat(guidance.get("actions")).asList()
                            .anySatisfy(action -> assertThat(action)
                                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                                    .containsEntry("type", "DOWNLOAD_PC_AGENT"))
                            .anySatisfy(action -> assertThat(action)
                                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                                    .containsEntry("type", "OPEN_SUPPORT_NEW")
                                    .containsEntry("route", "/support/new"));
                });
        // 가이던스 문구용 LLM 초안 시도만 허용하고, 진단/DB 경로는 여전히 타지 않는다.
        org.mockito.Mockito.verify(aiChatEngine).draftSupportGuidance(any(), any(), any());
        org.mockito.Mockito.verifyNoMoreInteractions(aiChatEngine);
        verifyNoInteractions(jdbcTemplate, toolCheckService);
    }

    @Test
    void shoppingSymptomGuidanceUsesLlmDraftWhenAvailable() {
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        when(aiChatEngine.draftSupportGuidance(any(), any(), any())).thenReturn(Optional.of(new SupportGuidanceDraft(
                "화면이 검게 변했다가 돌아오는 증상으로 이해했습니다. PC Agent를 실행하면 증상 직후 로그로 가능성을 좁힐 수 있습니다.",
                "그래픽 드라이버 응답 중단 등이 원인 후보로 예상됩니다.",
                List.of("그래픽 드라이버 응답 중단", "모니터 신호 케이블 손실", "GPU 전원 공급 불안정")
        )));
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                aiChatEngine,
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of("message", "게임하다 화면이 자꾸 멈춰"));

        // 채팅 문구·요약·원인 후보는 LLM 초안을 쓰고, 구조 필드(제목 등)는 정적 프로필을 유지한다.
        assertThat(response.get("message")).asString().contains("화면이 검게 변했다가 돌아오는 증상");
        assertThat(response.get("supportGuidance"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("summary", "그래픽 드라이버 응답 중단 등이 원인 후보로 예상됩니다.")
                .containsEntry("title", "게임·화면 멈춤 증상")
                .satisfies(guidance -> assertThat(guidance.get("possibleCauses")).asList()
                        .containsExactly("그래픽 드라이버 응답 중단", "모니터 신호 케이블 손실", "GPU 전원 공급 불안정"));
    }

    @Test
    void shoppingSymptomGuidanceFallsBackToStaticProfileWhenLlmThrows() {
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        when(aiChatEngine.draftSupportGuidance(any(), any(), any())).thenThrow(new RuntimeException("llm down"));
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                aiChatEngine,
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of("message", "게임하다 화면이 자꾸 멈춰"));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("supportGuidance"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .satisfies(guidance -> assertThat(guidance.get("possibleCauses")).asList()
                        .contains("그래픽 드라이버 충돌"));
    }

    @Test
    void successfulClarificationFollowUpPreservesCombinedContextForTheNextTurn() {
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                mock(AiChatEngine.class),
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "그중 드라이버 가능성을 더 설명해줘",
                "clarificationContext", Map.of("originalMessage", "게임하다 화면이 자꾸 멈춰")
        ));

        assertThat(response.get("supportGuidance")).isNotNull();
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry(
                        "originalMessage",
                        "게임하다 화면이 자꾸 멈춰 그중 드라이버 가능성을 더 설명해줘"
                );
    }

    @Test
    void supportContextDoesNotCaptureAStandaloneBudgetRecommendation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.of(Map.of(
                "answerType", "BUDGET",
                "message", "300만원 예산 추천 조합입니다.",
                "builds", List.of(Map.of("id", "build-300")),
                "warnings", List.of()
        )));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                mock(AiChatEngine.class),
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "300만원 견적 추천해줘",
                "clarificationContext", Map.of("originalMessage", "게임하다 화면이 자꾸 멈춰")
        ));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("supportGuidance")).isNull();
        assertThat(response.get("builds")).asList().hasSize(1);
        verify(cacheService).lookup(argThat(body ->
                        "300만원 견적 추천해줘".equals(body.get("message"))
                                && !body.containsKey("clarificationContext")),
                any(),
                any());
    }

    @Test
    void caseRecommendationContextDoesNotCaptureANewStutterSymptom() {
        // 제보 재현: 케이스 개선 미리보기 직후 "게임이 자꾸 끊켜"(표기 변형) 같은 고장 증상을 치면
        // 원문("케이스 추천해줘")과 합성돼 케이스 교체안이 반복 제시되는 핑퐁이 생겼다.
        // 증상 호소는 새 AS 진단 대화로 시작해야 한다.
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                mock(AiChatEngine.class),
                BuildChatCacheService.disabled()
        );

        for (String symptom : List.of("게임이 자꾸 끊켜", "화면이 자꾸 끊킴")) {
            Map<String, Object> response = service.chat(Map.of(
                    "message", symptom,
                    "clarificationContext", Map.of("originalMessage", "현재 부품이 여유 있게 들어가는 케이스 추천해줘")
            ));

            assertThat(response.get("supportGuidance")).as(symptom).isNotNull();
            assertThat(response.get("builds")).as(symptom).asList().isEmpty();
            assertThat(String.valueOf(response.get("message"))).as(symptom).doesNotContain("케이스 변경안");
        }
    }

    @Test
    void simulationContextDoesNotCaptureANewSupportSymptom() {
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                mock(AiChatEngine.class),
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "게임하다 화면이 자꾸 멈춰",
                "clarificationContext", Map.of("originalMessage", "CPU를 9700X로 바꾸면 성능 어때?")
        ));

        assertThat(response.get("supportGuidance")).isNotNull();
        assertThat(response.get("simulation")).isNull();
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "게임하다 화면이 자꾸 멈춰");
    }

    @Test
    void recommendationContextDoesNotCaptureANewSupportSymptom() {
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                mock(AiChatEngine.class),
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "게임하다 화면이 자꾸 멈춰",
                "clarificationContext", Map.of("originalMessage", "300만원 견적 추천해줘")
        ));

        assertThat(response.get("supportGuidance")).isNotNull();
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "게임하다 화면이 자꾸 멈춰");
    }

    @Test
    void supportContextDoesNotCaptureAnExplicitReplacementSimulation() {
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                mock(AiChatEngine.class),
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "CPU를 9700X로 바꾸면 성능이 어떻게 돼?",
                "clarificationContext", Map.of("originalMessage", "게임하다 화면이 자꾸 멈춰")
        ));

        assertThat(response.get("supportGuidance")).isNull();
        assertThat(response.get("warnings")).asList().contains("SIMULATION_TARGET_NOT_FOUND");
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "CPU를 9700X로 바꾸면 성능이 어떻게 돼?");
    }

    @Test
    void shoppingSymptomGuidanceOffersBoundedPossibilitiesForEachSupportedCategory() {
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                mock(AiChatEngine.class),
                BuildChatCacheService.disabled()
        );
        Map<String, String> cases = Map.of(
                "게임하다 화면이 자꾸 멈춰", "DISPLAY_FREEZE",
                "컴퓨터가 갑자기 재부팅돼", "POWER_RESTART",
                "전원은 들어오는데 부팅이 안돼", "BOOT_FAILURE",
                "게임 프레임이 자꾸 끊겨", "PERFORMANCE_STUTTER",
                "팬 소리가 크고 너무 뜨거워", "THERMAL_NOISE",
                "SSD 디스크가 계속 100퍼센트야", "STORAGE",
                "인터넷이 자꾸 끊겨", "NETWORK",
                "오디오 소리가 안 나", "AUDIO"
        );

        cases.forEach((message, expectedCategory) -> {
            Map<String, Object> response = service.chat(Map.of("message", message));
            assertThat(response.get("supportGuidance"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("symptomCategory", expectedCategory)
                    .satisfies(guidance -> {
                        assertThat(guidance.get("possibleCauses")).asList().hasSizeBetween(1, 4);
                        assertThat(guidance).doesNotContainKeys("causeCandidates", "riskLevel", "supportDecision", "rawSamples");
                    });
            assertThat(response.get("message")).asString().contains("예상됩니다");
        });
    }

    @Test
    void llmCanRecoverAParaphrasedSymptomButStillReturnsOnlyShoppingGuidance() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "현재 PC 증상으로 이해했습니다.",
                        AiChatIntent.SUPPORT_GUIDANCE,
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of("supportIntent", Map.of(
                                "shouldGuide", true,
                                "symptomCategory", "DISPLAY_FREEZE",
                                "confidence", "HIGH"
                        )),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "쓰다 보면 디스플레이가 얼어붙는 느낌이야"));

        assertThat(response.get("supportGuidance"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("symptomCategory", "DISPLAY_FREEZE")
                .containsEntry("scope", "PRE_DIAGNOSIS")
                .satisfies(guidance -> assertThat(guidance.get("possibleCauses")).asList()
                        .contains("그래픽 드라이버 충돌"));
        assertThat(response).doesNotContainKeys("actions", "causeCandidates", "riskLevel", "supportDecision");
        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void resolvedNavigationRouteReachesTheClientInsteadOfOnlyBeingPromisedInText() {
        // 제보 재현: "9800X3D 상세페이지로 이동해줘"에 "이동할게요"라고 답만 하고 실제로는 이동하지 않았다.
        // 엔진이 상품을 특정해 만든 route가 응답 조립에서 통째로 빠져 프론트가 따라갈 것이 없었기 때문이다.
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "9800X3D 상세페이지로 이동할게요.",
                        AiChatIntent.ASK_FOLLOW_UP,
                        List.of(new AiChatAction(
                                AiChatActionType.OPEN_ROUTE,
                                "상품 상세 보기",
                                Map.of(
                                        "route", "/parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7",
                                        "reason", "LLM_ROUTE_INTENT"
                                )
                        )),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of("message", "9800X3D 상세페이지로 이동해줘"));

        assertThat(response.get("actions")).asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "OPEN_ROUTE")
                .containsEntry("label", "상품 상세 보기")
                .satisfies(action -> assertThat(action.get("payload"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("route", "/parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7"));
    }

    @Test
    void productDisambiguationChoicesReachTheClientAsQuickReplies() {
        // 후보가 두어 개뿐이면 이동 대신 채팅에서 고르게 한다 — 칩 문구가 그대로 다음 질문이 된다.
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "'9800X3D'에 해당하는 상품이 2개예요. 어느 쪽인지 골라 주세요.",
                        AiChatIntent.ASK_FOLLOW_UP,
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of("routeChoiceChips", List.of(
                                "AMD 라이젠7-6세대 9800X3D 그래니트 릿지 정품(멀티팩) 상세페이지로 이동해",
                                "AMD 라이젠7-6세대 9800X3D (그래니트 릿지) (멀티팩 정품) - 아이티 상세페이지로 이동해")),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of("message", "9800X3D 상세페이지로 이동해줘"));

        assertThat(response).doesNotContainKey("actions");
        assertThat(response.get("quickReplies")).asList().containsExactly(
                "AMD 라이젠7-6세대 9800X3D 그래니트 릿지 정품(멀티팩) 상세페이지로 이동해",
                "AMD 라이젠7-6세대 9800X3D (그래니트 릿지) (멀티팩 정품) - 아이티 상세페이지로 이동해");
    }

    @Test
    void routeChoiceChipIsTreatedAsAProductSelectionNotANewRequest() {
        // 칩 라벨은 DB 상품명 전문이라 "게임PC"·"포함" 같은 어휘가 섞여 있다.
        // 표식 없이 문장만 읽으면 견적 추천으로 새어, 상품을 고르라는 질문에 견적 카드가 나온다.
        String product = "게임PC 조립용 메인보드 B850M 팬 1개 포함";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(product), eq(product)))
                .thenReturn(List.of(Map.of(
                        "id", "11111111-1111-4111-8111-111111111111",
                        "category", "MOTHERBOARD",
                        "name", product,
                        "manufacturer", "TEST")));
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", product + " 상세페이지로 이동해",
                "quickReplySource", "ROUTE_CHOICE"
        ));

        assertThat(response.get("actions")).asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .satisfies(action -> assertThat(action.get("payload"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("route", "/parts/11111111-1111-4111-8111-111111111111"));
        assertThat(response.get("builds")).asList().isEmpty();
        verify(aiChatEngine, never()).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void exactProductNameNavigationResolvesWithoutCallingTheLlm() {
        // 정확한 풀 상품명 이동은 DB 한 번이면 끝난다 — LLM을 태우면 느리고, LLM이 흔들리면 이동이 죽는다.
        String query = "AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩)";
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq("CPU"), eq(query), eq(query)))
                .thenReturn(List.of(Map.of(
                        "id", "a75d6544-2296-4c4c-a7cd-64596e66f6d7",
                        "category", "CPU",
                        "name", query,
                        "manufacturer", "AMD")));
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of("message", query + " 상세페이지로 이동해"));

        assertThat(response.get("actions")).asList()
                .singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "OPEN_ROUTE")
                .satisfies(action -> assertThat(action.get("payload"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("route", "/parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7"));
        // 이동한 턴은 완결 응답이라 되묻기 에코를 남기지 않는다.
        assertThat(response.get("clarification")).isNull();
        verify(aiChatEngine, never()).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void navigationRequestAfterAClarificationIsNotMergedWithThePreviousMessage() {
        // 제보 재현: "게이밍 PC 추천해줘"로 되물은 직후 "9800X3D 상세페이지로 이동해"를 치면
        // 두 문장이 합성돼 엔진이 호출조차 되지 않고 이동이 통째로 삼켜졌다.
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        List<String> engineSaw = new java.util.ArrayList<>();
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenAnswer(invocation -> {
                    engineSaw.add(((AiChatEngineRequest) invocation.getArgument(0)).message());
                    return new AiChatEngineResponse(
                            "9800X3D 상세페이지로 이동할게요.",
                            AiChatIntent.ASK_FOLLOW_UP,
                            List.of(new AiChatAction(
                                    AiChatActionType.OPEN_ROUTE,
                                    "상품 상세 보기",
                                    Map.of("route", "/parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7")
                            )),
                            List.of(),
                            List.of(),
                            Map.of(),
                            List.of(),
                            List.of(),
                            null
                    );
                });
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "9800X3D 상세페이지로 이동해",
                "clarificationContext", Map.of("originalMessage", "게이밍 PC 추천해줘")
        ));

        // 이전 원문이 앞에 붙지 않은 그대로가 엔진에 가야 한다.
        assertThat(engineSaw).containsExactly("9800X3D 상세페이지로 이동해");
        assertThat(response.get("actions")).asList().isNotEmpty();
    }

    @Test
    void aTurnThatActuallyNavigatedDoesNotEchoItselfIntoTheNextQuestion() {
        // 이동한 턴은 완결 응답이다. 여기에 되묻기 에코가 붙으면 다음 이동 요청이 이 문장과 합성돼 깨진다.
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "해당 CPU 상세페이지로 이동할게요.",
                        AiChatIntent.ASK_FOLLOW_UP,
                        List.of(new AiChatAction(
                                AiChatActionType.OPEN_ROUTE,
                                "상품 상세 보기",
                                Map.of("route", "/parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7")
                        )),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of("message", "9950X3D 상세페이지로 이동해"));

        assertThat(response.get("actions")).asList().isNotEmpty();
        assertThat(response.get("clarification")).isNull();
    }

    @Test
    void responsesCarryingANavigationRouteAreNotStoredInTheSemanticCache() {
        // semantic 캐시는 '비슷한 질문'에 재생된다. 이 턴의 화면 이동이 거기 들어가면
        // 견적만 물은 다른 사용자를 엉뚱한 화면으로 끌고 간다.
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        BuildChatSemanticCacheService semanticCacheService = mock(BuildChatSemanticCacheService.class);
        when(semanticCacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "셀프 견적 화면으로 이동할게요.",
                        AiChatIntent.ASK_FOLLOW_UP,
                        List.of(new AiChatAction(
                                AiChatActionType.OPEN_ROUTE,
                                "셀프 견적 열기",
                                Map.of("route", "/self-quote")
                        )),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService,
                null,
                new NoopCandidateReranker(),
                new PartRouteResolver(mock(JdbcTemplate.class)),
                new BuildChatIntentRouter(),
                semanticCacheService
        );

        Map<String, Object> response = service.chat(Map.of("message", "끝판왕 PC 견적 화면 열어줘"));

        assertThat(response.get("actions")).asList().isNotEmpty();
        verify(semanticCacheService, never()).storeAsync(any(), any(), any(), any());
    }

    @Test
    void answersWithoutAResolvedRouteCarryNoNavigationField() {
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "어떤 용도로 쓰실 PC인가요?",
                        AiChatIntent.ASK_FOLLOW_UP,
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(
                mock(JdbcTemplate.class),
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of("message", "9800X3D 상세페이지로 이동해줘"));

        assertThat(response).doesNotContainKey("actions");
    }

    @Test
    void scoreExplanationUsesServerEvaluationAndReturnsReadOnlyAssessment() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildEvaluationService evaluationService = mock(BuildEvaluationService.class);
        Map<String, Object> assessment = scoreAssessment();
        when(evaluationService.evaluateCurrentDraft(eq(42L), nullable(Integer.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new BuildEvaluationService.BuildEvaluation(List.of(), 2_000_000, 2_000_000, List.of(), Map.of(), assessment));
        when(aiChatEngine.explainBuildAssessment(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST")))
                .thenReturn(new AiChatEngineResponse(
                        "현재 견적은 CPU 체급 대비 GPU가 낮아 GPU 상향을 먼저 검토하는 편이 좋습니다.",
                        AiChatIntent.EXPLAIN,
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        "session-score"
                ));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled(),
                null,
                null,
                null,
                new BuildChatIntentRouter(),
                BuildChatSemanticCacheService.disabled(),
                evaluationService
        );
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(42L, "user-id", "user@example.com", "사용자", "USER", null);

        Map<String, Object> response = service.chat(Map.of(
                "message", "왜 이 견적의 종합 점수가 낮아?",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "client-forged-part",
                        "category", "GPU",
                        "price", 1
                ))),
                "assessmentContext", Map.of("source", "QUOTE_DRAFT_CURRENT", "focusType", "SCORE")
        ), user);

        assertThat(response).containsEntry("answerType", "GENERAL").containsEntry("buildAssessment", assessment);
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("simulation")).isNull();
        assertThat(response).doesNotContainKey("actions");
        assertThat(response.get("quickReplies")).asList().contains("현재 견적에 맞는 상위 GPU 추천해줘");
        verify(aiChatEngine).explainBuildAssessment(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST"));
        verifyNoInteractions(jdbcTemplate, toolCheckService);
    }

    @Test
    void scoreExplanationFallsBackToDeterministicCardWhenLlmFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildEvaluationService evaluationService = mock(BuildEvaluationService.class);
        Map<String, Object> assessment = scoreAssessment();
        when(evaluationService.evaluateCurrentDraft(eq(42L), nullable(Integer.class), nullable(String.class), nullable(String.class)))
                .thenReturn(new BuildEvaluationService.BuildEvaluation(List.of(), 2_000_000, 2_000_000, List.of(), Map.of(), assessment));
        when(aiChatEngine.explainBuildAssessment(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST")))
                .thenThrow(new IllegalStateException("LLM unavailable"));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled(),
                null,
                null,
                null,
                new BuildChatIntentRouter(),
                BuildChatSemanticCacheService.disabled(),
                evaluationService
        );
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(42L, "user-id", "user@example.com", "사용자", "USER", null);

        Map<String, Object> response = service.chat(Map.of("message", "이 견적 병목 설명해줘"), user);

        assertThat(String.valueOf(response.get("message"))).contains("742/1000점");
        assertThat(response.get("warnings")).asList().contains("SCORE_EXPLANATION_LLM_FALLBACK");
        assertThat(response).containsEntry("buildAssessment", assessment);
    }

    @Test
    void boardLocationFastPathReturnsReadOnlyFocusWithoutCallingLlm() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "램 위치가 어디 있어?",
                "uiContext", Map.of(
                        "surface", "SELF_QUOTE",
                        "capabilities", List.of("BOARD_PART_FOCUS")
                )
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response).doesNotContainKey("simulation");
        assertThat(response.get("boardFocus")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "PART_LOCATION")
                .containsEntry("categories", List.of("RAM"));
        verifyNoInteractions(aiChatEngine, jdbcTemplate, toolCheckService);
    }

    private static Map<String, Object> scoreAssessment() {
        return Map.of(
                "type", "COMPOSITE_SCORE_EXPLANATION",
                "score", 742,
                "maxScore", 1000,
                "grade", "C",
                "label", "기본형",
                "summary", "CPU 체급에 비해 GPU 성능 균형이 낮습니다.",
                "strengths", List.of(Map.of(
                        "code", "HIGH_CPU_TIER",
                        "severity", "PASS",
                        "title", "CPU 성능 여유",
                        "description", "현재 CPU는 상위권 구성입니다.",
                        "relatedCategories", List.of("CPU")
                )),
                "cautions", List.of(Map.of(
                        "code", "CPU_GPU_IMBALANCE",
                        "severity", "WARN",
                        "title", "CPU와 GPU 성능 균형",
                        "description", "CPU 체급에 비해 GPU가 낮습니다.",
                        "relatedCategories", List.of("CPU", "GPU")
                )),
                "recommendations", List.of(Map.of(
                        "priority", 1,
                        "category", "GPU",
                        "title", "GPU 상향 우선",
                        "reason", "현재 구성에서 가장 큰 성능 제한 요소입니다.",
                        "prompt", "현재 견적에 맞는 상위 GPU 추천해줘"
                )),
                "evaluatedAt", "2026-07-13T00:00:00Z"
        );
    }

    @Test
    void explicitMultipleBoardLocationsUseFastPathWithoutCallingLlm() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled()
        );
        Map<String, Object> response = service.chat(Map.of(
                "message", "메인보드랑 램 위치가 어딜까",
                "uiContext", Map.of(
                        "surface", "SELF_QUOTE",
                        "capabilities", List.of("BOARD_PART_FOCUS")
                )
        ));

        assertThat(response.get("boardFocus")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("categories", List.of("MOTHERBOARD", "RAM"));
        assertThat(response.get("builds")).asList().isEmpty();
        verifyNoInteractions(aiChatEngine, jdbcTemplate, toolCheckService);
    }

    @Test
    void parsesBudgetWonFromCommonKoreanInputs() {
        assertThat(BuildChatService.parseBudgetWon("200만원 PC 추천")).isEqualTo(200 * 10_000);
        assertThat(BuildChatService.parseBudgetWon("3백만원 PC 추천")).isEqualTo(300 * 10_000);
        assertThat(BuildChatService.parseBudgetWon("300만원대로 맞춰줘")).isEqualTo(3_000_000);
        assertThat(BuildChatService.parseBudgetWon("2,000,000원 안에서")).isEqualTo(200 * 10_000);
        assertThat(BuildChatService.parseBudgetWon("삼백만원 정도로 디자인 작업용 컴")).isEqualTo(3_000_000);
        assertThat(BuildChatService.parseBudgetWon("일천삼백만원 예산으로")).isEqualTo(13_000_000);
        assertThat(BuildChatService.parseBudgetWon("천팔백만원 예산으로 방송용 컴퓨터")).isEqualTo(18_000_000);
        assertThat(BuildChatService.parseBudgetWon("2천만원 예산인데")).isEqualTo(20_000_000);
        assertThat(BuildChatService.parseBudgetWon("돈은 3천만원까지 괜찮으니")).isEqualTo(30_000_000);
        assertThat(BuildChatService.parseBudgetWon("천만에요 감사합니다")).isNull();
        // 십/백 합성 수사를 숫자로 합쳐 bare 만원(1만원) 폴백으로 새지 않게 한다
        assertThat(BuildChatService.parseBudgetWon("이백오십만원으로 게이밍 PC")).isEqualTo(2_500_000);
        assertThat(BuildChatService.parseBudgetWon("삼백오십만원 정도 편집용")).isEqualTo(3_500_000);
        assertThat(BuildChatService.parseBudgetWon("십만원짜리 램")).isEqualTo(100_000);
        assertThat(BuildChatService.parseBudgetWon("천이백만원 예산")).isEqualTo(12_000_000);
        assertThat(BuildChatService.parseBudgetWon("0.5억으로 최고급 워크스테이션")).isEqualTo(50_000_000);
        assertThat(BuildChatService.parseBudgetWon("1억원으로 제일 좋은 컴퓨터")).isEqualTo(100_000_000);
        assertThat(BuildChatService.parseBudgetWon("1억2천만원")).isEqualTo(120_000_000);
        assertThat(BuildChatService.parseBudgetWon("일억")).isEqualTo(100_000_000);
        assertThat(BuildChatService.parseBudgetWon("억 소리 나네")).isNull();
        assertThat(BuildChatService.budgetIntent("800만원으로 최고급 PC 추천해줘").mode()).isEqualTo("TARGET");
        assertThat(BuildChatService.budgetIntent("300만원 이하 RTX 5090 PC").mode()).isEqualTo("MAX");
        assertThat(BuildChatService.budgetIntent("300만원 안에서 QHD 게임 PC").mode()).isEqualTo("MAX");
        assertThat(BuildChatService.budgetIntent("300만원 이상으로 게임용 PC 맞춰줘").mode()).isEqualTo("MIN");
        assertThat(BuildChatService.budgetIntent("RTX 5090 말고 300만원 PC").negatedPartConstraint()).isTrue();
        assertThat(BuildChatService.budgetIntent("RTX 5090 없이 300만원 PC").negatedPartConstraint()).isTrue();
        assertThat(BuildChatService.budgetIntent("ASUS 보드와 AMD CPU로 400만원").explicitHardConstraint()).isTrue();
        assertThat(BuildChatService.budgetIntent("2TB SSD와 1000W 파워 포함 500만원").explicitHardConstraint()).isTrue();
        assertThat(BuildChatService.budgetIntent("DDR5 64GB 포함해서 300만원 개발용").explicitHardConstraint()).isTrue();
    }

    @Test
    void detectsPartQuestionCategories() {
        assertThat(BuildChatService.detectPartCategory("GPU 추천해줘")).isEqualTo("GPU");
        assertThat(BuildChatService.detectPartCategory("CPU는 뭐가 좋아?")).isEqualTo("CPU");
        assertThat(BuildChatService.detectPartCategory("RAM64GB 추천해줘")).isEqualTo("RAM");
        assertThat(BuildChatService.detectPartCategory("쿨러 추천")).isEqualTo("COOLER");
        assertThat(BuildChatService.detectPartCategory("360 수랭 장착 케이스")).isEqualTo("CASE");
        assertThat(BuildChatService.detectPartCategory("케이스에 맞는 수랭 쿨러 추천")).isEqualTo("COOLER");
        assertThat(BuildChatService.detectPartCategory("수랭 쿨러가 들어가는 케이스 추천")).isEqualTo("CASE");
        assertThat(BuildChatService.detectPartCategory("커세어 FRAME 4000D LCD 견적에 담아줘")).isNull();
    }

    @Test
    void simulationTargetTokenPrefersDestinationAndStripsParticles() {
        // "A에서 B로" 패턴은 도착지(B)를 우선하고, 조사 접미는 떼어낸다
        assertThat(BuildChatService.simulationModelToken("MOTHERBOARD", "메인보드를 B850에서 X870으로 바꾸면")).isEqualTo("X870");
        assertThat(BuildChatService.simulationModelToken("MOTHERBOARD", "메인보드를 X870으로 바꾸면 어때?")).isEqualTo("X870");
    }

    @Test
    void parsesTerabyteCapacityAsGigabytes() {
        assertThat(BuildChatService.parseCapacityGb("4TB SSD로 바꾸면")).isEqualTo(4000);
        assertThat(BuildChatService.parseCapacityGb("2테라 저장장치")).isEqualTo(2000);
        assertThat(BuildChatService.parseCapacityGb("512GB로 교체")).isEqualTo(512);
    }

    @Test
    void buildChatServesTierSnapshotWithoutEngineWhenBudgetIsNearTier() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        BuildChatTierSnapshotStore store = new BuildChatTierSnapshotStore();
        // 스냅샷 총액은 요청 예산(437만) 기준 target 밴드(±12.5%) 안이어야 즉시 서빙된다
        store.put(new BuildChatTierSnapshotStore.TierSnapshot(
                4_000_000,
                List.of(Map.of("id", "tier-build-400", "tier", "balanced", "totalPrice", 4_200_000)),
                List.of("미리 계산된 조합"),
                java.time.Instant.now()
        ));
        service.setTierSnapshotStore(store);

        Map<String, Object> response = service.chat(Map.of("message", "437만원 PC 추천해줘"));

        assertThat(response).containsEntry("answerType", "BUDGET");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).extracting(build -> build.get("id")).containsExactly("tier-build-400");
        assertThat(response.get("warnings")).asList().contains("미리 계산된 조합");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatSkipsTierSnapshotForExplicitPartConstraintOrDraftContext() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        BuildChatTierSnapshotStore store = new BuildChatTierSnapshotStore();
        store.put(new BuildChatTierSnapshotStore.TierSnapshot(
                4_000_000,
                List.of(Map.of("id", "tier-build-400", "tier", "balanced")),
                List.of(),
                java.time.Instant.now()
        ));
        service.setTierSnapshotStore(store);
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        // 명시적 부품 제약(5090)이 있으면 티어 즉시 응답을 쓰지 않는다
        Map<String, Object> constrained = service.chat(Map.of("message", "437만원 RTX 5090 넣어서 PC 추천해줘"));
        assertThat(constrained.get("builds")).asList()
                .noneMatch(build -> "tier-build-400".equals(((Map<?, ?>) build).get("id")));

        // 드래프트 문맥이 있으면 티어 즉시 응답을 쓰지 않는다
        Map<String, Object> withDraft = service.chat(Map.of(
                "message", "437만원 PC 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of("partId", "gpu-1", "category", "GPU", "quantity", 1)))
        ));
        assertThat(withDraft.get("builds")).asList()
                .noneMatch(build -> "tier-build-400".equals(((Map<?, ?>) build).get("id")));
    }

    @Test
    void buildChatSkipsTierSnapshotWhenSnapshotTotalIsBelowRequestedTargetBand() {
        // 스냅샷 티어(400만)는 허용 오차(15%) 안이지만, 총액(360만)이 요청 예산(460만)의
        // target 밴드 하한(402.5만)에 못 미치면 즉시 응답을 포기하고 일반 경로로 흘린다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        BuildChatTierSnapshotStore store = new BuildChatTierSnapshotStore();
        store.put(new BuildChatTierSnapshotStore.TierSnapshot(
                4_000_000,
                List.of(Map.of("id", "tier-build-400", "tier", "balanced", "totalPrice", 3_600_000)),
                List.of(),
                java.time.Instant.now()
        ));
        service.setTierSnapshotStore(store);
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        Map<String, Object> response = service.chat(Map.of("message", "460만원 PC 추천해줘"));

        assertThat(response.get("builds")).asList()
                .noneMatch(build -> "tier-build-400".equals(((Map<?, ?>) build).get("id")));
        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void buildChatSkipsTierSnapshotWhenSnapshotTotalExceedsMaxBudget() {
        // 스냅샷은 target 밴드로 프리계산되므로 티어보다 비싼 카드(440만)가 있을 수 있다.
        // "이하"(max 모드) 요청 예산(410만)을 넘으면 서빙하지 않고, 예산 이하(450만 요청)면 서빙한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        BuildChatTierSnapshotStore store = new BuildChatTierSnapshotStore();
        store.put(new BuildChatTierSnapshotStore.TierSnapshot(
                4_000_000,
                List.of(Map.of("id", "tier-build-400", "tier", "balanced", "totalPrice", 4_400_000)),
                List.of(),
                java.time.Instant.now()
        ));
        service.setTierSnapshotStore(store);
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        Map<String, Object> overMax = service.chat(Map.of("message", "410만원 이하로 PC 추천해줘"));
        assertThat(overMax.get("builds")).asList()
                .noneMatch(build -> "tier-build-400".equals(((Map<?, ?>) build).get("id")));
        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));

        Map<String, Object> underMax = service.chat(Map.of("message", "450만원 이하로 PC 추천해줘"));
        assertThat(underMax.get("builds")).asList()
                .anyMatch(build -> "tier-build-400".equals(((Map<?, ?>) build).get("id")));
        // 서빙된 두 번째 요청은 엔진을 다시 부르지 않는다(호출 횟수 1회 유지)
        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void targetBudgetLadderKeepsEveryCardInsideContractBand() {
        // 계약(docs/API_CONTRACT.md): 명시 예산 target 모드는 총액을 예산의 87.5%~112.5%에 맞춘다.
        // 과거 55/75/100% 가격 다양화 사다리는 800만 요청에 435만/572만 카드를 내 계약을 위반했다 —
        // 이제 다양성은 밴드 안 구성 차이로 만들고, 밴드 밖 카드는 제외한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of("message", "800만원으로 PC 추천해줘"));

        assertThat(response).containsEntry("answerType", "BUDGET");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).hasSizeGreaterThanOrEqualTo(2);
        assertThat(builds).allSatisfy(build ->
                assertThat((Integer) build.get("totalPrice")).isBetween(7_000_000, 9_000_000));
        // 카드에는 사용자 명시 예산과 target 모드가 그대로 표기된다
        assertThat(builds).allSatisfy(build -> {
            assertThat(build).containsEntry("budgetWon", 8_000_000);
            assertThat(build.get("badges")).asList().contains("TARGET");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) build.get("items");
            assertThat(items).anySatisfy(item -> {
                assertThat(item).containsEntry("category", "GPU");
            });
            assertThat(items).filteredOn(item -> "RAM".equals(item.get("category")))
                    .allSatisfy(item -> assertThat(item).containsEntry("quantity", 1));
        });
        assertThat(builds)
                .extracting(build -> build.get("tierLabel"))
                .as("동일한 평가 점수의 조합은 가격 순서만으로 서로 다른 성능 티어가 되면 안 된다")
                .containsOnly(builds.get(0).get("tierLabel"));
        assertThat(builds).allSatisfy(build ->
                assertThat(build.get("badges")).asList().contains(build.get("tierLabel")));
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void targetBudgetCardsKeepToolChecksAlignedWithEachGeneratedCard() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ToolBuildPart> parts = invocation.getArgument(0, List.class);
            int toolBudget = invocation.getArgument(1, Integer.class);
            int total = parts.stream()
                    .mapToInt(part -> (part.price() == null ? 0 : part.price()) * part.effectiveQuantity())
                    .sum();
            return List.of(Map.of(
                    "tool", "price",
                    "status", total > toolBudget ? "FAIL" : "PASS",
                    "summary", total > toolBudget ? "예산 초과" : "예산 통과"
            ));
        }).when(toolCheckService).checkBuild(anyList(), anyInt());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of("message", "800만원으로 PC 추천해줘"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).isNotEmpty().allSatisfy(build -> {
            assertThat((Integer) build.get("totalPrice")).isBetween(7_000_000, 9_000_000);
            assertThat((Integer) build.get("totalPrice")).isLessThanOrEqualTo(8_000_000);
            assertThat(build.get("toolResults")).asList().allSatisfy(result ->
                    assertThat(result).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                            .containsEntry("status", "PASS"));
        });
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void qhdTargetNearTheMinimumNeverReturnsAnOutsideBandCounterproposalCard() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of("message", "180만원으로 QHD 게임용 PC 추천해줘"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).allSatisfy(build ->
                assertThat((Integer) build.get("totalPrice")).isBetween(1_575_000, 2_025_000));
        assertThat(builds).allSatisfy(build ->
                assertThat(build.get("toolResults")).asList().noneSatisfy(result ->
                        assertThat(result).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                                .containsEntry("status", "FAIL")));
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void qhdGamingDemoPromptReturnsOnlyGpuBuildsAndOneTwoDimmRamKit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "민수는 200만 원 예산으로 QHD 게임용 PC를 구매하려고 합니다."));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(builds).allSatisfy(build -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) build.get("items");
            assertThat(items).anyMatch(item -> "GPU".equals(item.get("category")));
            assertThat(items).filteredOn(item -> "RAM".equals(item.get("category")))
                    .allSatisfy(item -> assertThat(item).containsEntry("quantity", 1));
        });
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void minBudgetLadderNeverReturnsCardsBelowRequestedFloor() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of("message", "400만원 이상으로 4K 게임 PC 추천해줘"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).isNotEmpty();
        assertThat(builds).allSatisfy(build ->
                assertThat((Integer) build.get("totalPrice")).isGreaterThanOrEqualTo(4_000_000));
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void minBudgetRequestDoesNotServeSnapshotBelowRequestedFloor() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        BuildChatTierSnapshotStore store = new BuildChatTierSnapshotStore();
        store.put(new BuildChatTierSnapshotStore.TierSnapshot(
                4_000_000,
                List.of(Map.of("id", "tier-below-min", "tier", "balanced", "totalPrice", 4_200_000)),
                List.of(),
                java.time.Instant.now()
        ));
        service.setTierSnapshotStore(store);
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        Map<String, Object> response = service.chat(Map.of("message", "450만원 이상으로 PC 추천해줘"));

        assertThat(response.get("builds")).asList()
                .noneMatch(build -> "tier-below-min".equals(((Map<?, ?>) build).get("id")));
        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void targetBudgetRequestDoesNotServeSnapshotContainingToolFail() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        BuildChatTierSnapshotStore store = new BuildChatTierSnapshotStore();
        store.put(new BuildChatTierSnapshotStore.TierSnapshot(
                4_000_000,
                List.of(Map.of(
                        "id", "tier-tool-fail",
                        "tier", "balanced",
                        "totalPrice", 4_000_000,
                        "toolResults", List.of(Map.of("tool", "compatibility", "status", "FAIL"))
                )),
                List.of(),
                java.time.Instant.now()
        ));
        service.setTierSnapshotStore(store);
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        Map<String, Object> response = service.chat(Map.of("message", "400만원으로 PC 추천해줘"));

        assertThat(response.get("builds")).asList()
                .noneMatch(build -> "tier-tool-fail".equals(((Map<?, ?>) build).get("id")));
    }

    @Test
    void maxBudgetLadderKeepsWideValueLadderForUnderBudgetRequests() {
        // "이하"(max 모드) 요청은 계약상 "예산 이하 우선"만 요구하므로
        // 가성비(55%)~예산 근접(100%) 가격 사다리를 그대로 유지한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of("message", "800만원 이하로 PC 추천해줘"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).hasSizeGreaterThanOrEqualTo(2);
        // target 밴드(87.5%) 아래 가성비 카드가 살아 있어야 한다 — max 모드는 밴드 필터 대상이 아니다
        assertThat(builds).anySatisfy(build ->
                assertThat((Integer) build.get("totalPrice")).isLessThan(7_000_000));
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void minimumFeasiblePhraseReturnsThreeVerifiedGamingBuildsWithoutAskingBudgetAgain() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "배그 같은 게임이 잘 돌아갔으면 좋겠어요. 최소 견적으로 맞춰줘."));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("builds")).asList().hasSize(3).allSatisfy(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("items")).asList().hasSize(8);
            assertThat(build.get("toolResults")).asList().noneSatisfy(result ->
                    assertThat(((Map<?, ?>) result).get("status")).isEqualTo("FAIL"));
        });
        assertThat(response.get("message").toString())
                .contains("게이밍")
                .contains("최소")
                .contains("3개");
        assertThat(response).doesNotContainKey("clarification");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void finalDemoClarificationKeepsPubgContextAndReturnsThreeMinimumBuilds() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> first = service.chat(Map.of(
                "message", "중학교 3학년 조카가 처음 PC를 맞추려고 합니다. 배틀그라운드가 잘 돌아가는 컴퓨터를 원합니다"));

        assertThat(first.get("builds")).asList().isEmpty();
        assertThat(first.get("message").toString())
                .contains("게이밍")
                .contains("예산")
                .doesNotContain("내부 자산");
        assertThat(first.get("clarification")).isInstanceOf(Map.class);
        String firstOriginalMessage = String.valueOf(
                ((Map<?, ?>) first.get("clarification")).get("originalMessage"));
        assertThat(firstOriginalMessage).contains("배틀그라운드");

        Map<String, Object> currentDraft = Map.of(
                "id", "demo-draft",
                "items", List.of(Map.of(
                        "partId", "existing-gpu",
                        "category", "GPU",
                        "name", "기존 고가 GPU",
                        "quantity", 1,
                        "price", 9_000_000
                )),
                "totalPrice", 9_000_000
        );
        List<Map<String, Object>> previousBuilds = List.of(Map.of(
                "id", "previous-build",
                "tier", "balanced",
                "totalPrice", 9_000_000,
                "items", List.of()
        ));
        Map<String, Object> second = service.chat(Map.of(
                "message", "배그 같은 게임이 잘 돌아갔으면 좋겠어요.",
                "currentBuilds", previousBuilds,
                "currentQuoteDraft", currentDraft,
                "clarificationContext", Map.of("originalMessage", firstOriginalMessage)));

        assertThat(second.get("builds")).asList().isEmpty();
        assertThat(second.get("message").toString()).contains("조카").contains("게이밍").contains("예산");
        String secondOriginalMessage = String.valueOf(
                ((Map<?, ?>) second.get("clarification")).get("originalMessage"));
        assertThat(secondOriginalMessage).contains("배틀그라운드").contains("배그");

        Map<String, Object> third = service.chat(Map.of(
                "message", "최소 견적으로 맞춰줘.",
                "currentBuilds", previousBuilds,
                "currentQuoteDraft", currentDraft,
                "clarificationContext", Map.of("originalMessage", secondOriginalMessage)));

        assertThat(third).containsEntry("answerType", "BUDGET");
        assertThat(third.get("builds")).asList().hasSize(3).allSatisfy(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("id")).isNotEqualTo("previous-build");
            assertThat(((Number) build.get("totalPrice")).intValue()).isLessThan(9_000_000);
            assertThat(build.get("items")).asList().hasSize(8);
            assertThat(build.get("toolResults")).asList().noneSatisfy(result ->
                    assertThat(((Map<?, ?>) result).get("status")).isEqualTo("FAIL"));
        });
        assertThat(third.get("message").toString()).contains("게이밍").contains("최소").contains("3개");
        assertThat(third).doesNotContainKey("clarification");
        verify(aiChatEngine, never()).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void minimumFeasibleFollowUpBypassesAStaleClarificationCacheEntry() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.of(Map.of(
                "answerType", "GENERAL",
                "message", "예산대를 알려주세요.",
                "builds", List.of(),
                "warnings", List.of(),
                "clarification", Map.of("originalMessage", "이전 대화")
        )));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "중학교 3학년 조카가 사용할 배틀그라운드 PC를 최소 견적으로 맞춰줘.",
                "currentBuilds", List.of(Map.of("id", "previous-build")),
                "currentQuoteDraft", Map.of(
                        "id", "demo-draft",
                        "items", List.of(Map.of("partId", "existing-gpu", "category", "GPU", "quantity", 1)),
                        "totalPrice", 9_000_000
                ),
                "clarificationContext", Map.of(
                        "originalMessage", "중학교 3학년 조카가 처음 PC를 맞추려고 합니다. 배틀그라운드가 잘 돌아가야 합니다"
                )
        ));

        assertThat(response.get("builds")).asList().hasSize(3);
        assertThat(response.get("message").toString()).contains("최소").doesNotContain("예산대를 알려주세요");
        verify(cacheService, never()).lookup(any(), any(), any());
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void minimumCostCompletionRequestKeepsDraftPartsAndFillsRemainingCategories() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "나머지는 최소 비용으로 채워줘",
                "currentQuoteDraft", Map.of(
                        "id", "demo-draft",
                        "items", List.of(
                                Map.of("partId", "existing-cpu", "category", "CPU",
                                        "name", "기존 CPU", "quantity", 1, "price", 300_000),
                                Map.of("partId", "existing-gpu", "category", "GPU",
                                        "name", "기존 GPU", "quantity", 1, "price", 700_000)),
                        "totalPrice", 1_000_000)));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("message").toString())
                .contains("담긴 부품은 유지")
                .doesNotContain("최소 완성가");
        assertThat(response.get("builds")).asList().isNotEmpty().allSatisfy(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("badges")).asList().contains("DRAFT_COMPLETION");
            assertThat(build.get("items")).asList().hasSize(8);
            assertThat(build.get("items")).asList().anySatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "existing-cpu"));
            assertThat(build.get("items")).asList().anySatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "existing-gpu"));
        });
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void minimumBudgetDraftCompletionRequestKeepsDraftInsteadOfRebuildingThreeMinimumBuilds() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "최소 예산으로 이 견적 완성해줘",
                "currentQuoteDraft", Map.of(
                        "id", "demo-draft",
                        "items", List.of(
                                Map.of("partId", "existing-cpu", "category", "CPU",
                                        "name", "기존 CPU", "quantity", 1, "price", 300_000),
                                Map.of("partId", "existing-gpu", "category", "GPU",
                                        "name", "기존 GPU", "quantity", 1, "price", 700_000)),
                        "totalPrice", 1_000_000)));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("message").toString())
                .contains("담긴 부품은 유지")
                .doesNotContain("최소 완성가");
        assertThat(response.get("builds")).asList().isNotEmpty().allSatisfy(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("badges")).asList().contains("DRAFT_COMPLETION");
            assertThat(build.get("items")).asList().anySatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "existing-gpu"));
        });
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void minimumRequestWithoutCompletionMarkerStillRebuildsThreeMinimumBuildsOverDraft() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "최소 견적으로 맞춰줘.",
                "currentQuoteDraft", Map.of(
                        "id", "demo-draft",
                        "items", List.of(Map.of("partId", "existing-gpu", "category", "GPU",
                                "name", "기존 고가 GPU", "quantity", 1, "price", 9_000_000)),
                        "totalPrice", 9_000_000)));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("builds")).asList().hasSize(3);
        assertThat(response.get("message").toString()).contains("최소").contains("3개");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void completionMarkerWithEmptyDraftStillReturnsMinimumBuilds() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "나머지는 최소 비용으로 채워줘",
                "currentQuoteDraft", Map.of("id", "empty-draft", "items", List.of(), "totalPrice", 0)));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("builds")).asList().hasSize(3);
        assertThat(response.get("message").toString()).contains("최소").contains("3개");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void targetStableFpsRequestReturnsOneToolVerifiedGpuPreview() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("category = ?")) {
                return List.of(partRow(
                        "gpu-target", "GPU", "RTX 5070 Demo", 800_000,
                        Map.of("gpuClass", "RTX_5070", "hardwareClass", "RTX_5070"), 80));
            }
            if (sql.contains("FROM game_fps_benchmarks")) {
                boolean targetGpu = "RTX_5070".equals(invocation.getArgument(2, String.class));
                double lowFps = targetGpu ? 153 : 105;
                return List.of(Map.of(
                        "game_title", "PlayerUnknown's Battlegrounds",
                        "game_key", "pubg",
                        "resolution", "QHD",
                        "graphics_preset", "MEDIUM",
                        "avg_fps", targetGpu ? 179 : 130,
                        "one_percent_low_fps", lowFps,
                        "source_name", "PC-Builds FPS calculator",
                        "confidence", "MEDIUM",
                        "metadata", Map.of("gpuClass", targetGpu ? "RTX_5070" : "RTX_5060")
                ));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "배그 QHD에서 120FPS를 안정적으로 노릴 GPU로 바꿔줘",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cpu-current", "CPU", "Ryzen 7", 1, Map.of("cpuClass", "RYZEN_7_9700X")),
                        draftItem("gpu-current", "GPU", "RTX 5060", 1, Map.of("gpuClass", "RTX_5060")),
                        draftItem("case-current", "CASE", "Demo Case", 1, Map.of("maxGpuLengthMm", 400)),
                        draftItem("psu-current", "PSU", "Demo PSU", 1, Map.of("capacityW", 1000))
                ))
        ));

        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("builds")).as("response: %s", response).asList().singleElement().satisfies(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("badges")).asList().contains("DRAFT_EDIT_PREVIEW");
            assertThat(build.get("items")).asList().anySatisfy(item -> {
                Map<String, Object> mapped = (Map<String, Object>) item;
                assertThat(mapped).containsEntry("partId", "gpu-target");
            });
        });
        assertThat(response.get("message").toString()).contains("1% low").contains("153FPS");
        assertThat(response.get("quickReplies")).asList().contains("이 변경안 적용해줘");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void finalDemoAverageFpsRequestReturnsOneToolVerifiedGpuPreview() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("category = ?")) {
                return List.of(partRow(
                        "gpu-5060-ti", "GPU", "RTX 5060 Ti Demo", 612_500,
                        Map.of("gpuClass", "RTX_5060_TI", "hardwareClass", "RTX_5060_TI"), 80));
            }
            if (sql.contains("FROM game_fps_benchmarks")) {
                boolean targetGpu = "RTX_5060_TI".equals(invocation.getArgument(2, String.class));
                return List.of(Map.of(
                        "game_title", "PlayerUnknown's Battlegrounds",
                        "game_key", "pubg",
                        "resolution", "QHD",
                        "graphics_preset", "MEDIUM",
                        "avg_fps", targetGpu ? 140 : 130,
                        "source_name", "PC-Builds FPS calculator",
                        "confidence", "MEDIUM",
                        "metadata", Map.of("gpuClass", targetGpu ? "RTX_5060_TI" : "RTX_5060")
                ));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "배그 QHD에서 평균 140FPS 이상 나오는 GPU로 바꿔줘",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cpu-current", "CPU", "Core Ultra 5", 1, Map.of("cpuClass", "INTEL_CORE_ULTRA_5_245K")),
                        draftItem("gpu-current", "GPU", "RTX 5060", 1, Map.of("gpuClass", "RTX_5060")),
                        draftItem("case-current", "CASE", "Demo Case", 1, Map.of("maxGpuLengthMm", 415)),
                        draftItem("psu-current", "PSU", "Demo PSU", 1, Map.of("capacityW", 600))
                ))
        ));

        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("builds")).as("response: %s", response).asList().singleElement().satisfies(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("badges")).asList().contains("DRAFT_EDIT_PREVIEW");
            assertThat(build.get("items")).asList().anySatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "gpu-5060-ti"));
            assertThat(build.get("toolResults")).asList().noneSatisfy(result ->
                    assertThat(((Map<?, ?>) result).get("status")).isEqualTo("FAIL"));
        });
        assertThat(response.get("message").toString()).contains("평균 약 140FPS");
        assertThat(response.get("quickReplies")).asList().contains("이 변경안 적용해줘");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void mixedTargetFpsRequestRespectsNamedGpuModelInsteadOfCheapestQualifier() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("category = ?")) {
                return List.of(
                        partRow("gpu-5070", "GPU", "RTX 5070 Demo", 900_000,
                                Map.of("gpuClass", "RTX_5070", "hardwareClass", "RTX_5070"), 80),
                        partRow("gpu-5090", "GPU", "RTX 5090 Demo", 3_500_000,
                                Map.of("gpuClass", "RTX_5090", "hardwareClass", "RTX_5090"), 99));
            }
            if (sql.contains("FROM game_fps_benchmarks")) {
                String gpuClass = invocation.getArgument(2, String.class);
                int avgFps = switch (gpuClass) {
                    case "RTX_5090" -> 200;
                    case "RTX_5070" -> 150;
                    default -> 110;
                };
                return List.of(Map.of(
                        "game_title", "PlayerUnknown's Battlegrounds",
                        "game_key", "pubg",
                        "resolution", "QHD",
                        "graphics_preset", "MEDIUM",
                        "avg_fps", avgFps,
                        "source_name", "PC-Builds FPS calculator",
                        "confidence", "MEDIUM",
                        "metadata", Map.of("gpuClass", gpuClass)
                ));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "RTX 5090으로 바꿔줘. 배그 QHD에서 평균 140FPS 이상은 나와야 해",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cpu-current", "CPU", "Ryzen 7", 1, Map.of("cpuClass", "RYZEN_7_9700X")),
                        draftItem("gpu-current", "GPU", "RTX 5060", 1, Map.of("gpuClass", "RTX_5060")),
                        draftItem("case-current", "CASE", "Demo Case", 1, Map.of("maxGpuLengthMm", 420)),
                        draftItem("psu-current", "PSU", "Demo PSU", 1, Map.of("capacityW", 1500))
                ))
        ));

        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("builds")).as("response: %s", response).asList().singleElement().satisfies(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("badges")).asList().contains("DRAFT_EDIT_PREVIEW");
            assertThat(build.get("items")).asList().anySatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "gpu-5090"));
            assertThat(build.get("items")).asList().noneSatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "gpu-5070"));
        });
        assertThat(response.get("message").toString()).contains("평균 약 200FPS");
        assertThat(response.get("quickReplies")).asList().contains("이 변경안 적용해줘");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void mixedTargetFpsRequestReportsNamedModelShortfallInsteadOfSilentSwap() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("category = ?")) {
                return List.of(
                        partRow("gpu-5070", "GPU", "RTX 5070 Demo", 900_000,
                                Map.of("gpuClass", "RTX_5070", "hardwareClass", "RTX_5070"), 80),
                        partRow("gpu-5090", "GPU", "RTX 5090 Demo", 3_500_000,
                                Map.of("gpuClass", "RTX_5090", "hardwareClass", "RTX_5090"), 99));
            }
            if (sql.contains("FROM game_fps_benchmarks")) {
                String gpuClass = invocation.getArgument(2, String.class);
                int avgFps = switch (gpuClass) {
                    case "RTX_5090" -> 120;
                    case "RTX_5070" -> 150;
                    default -> 100;
                };
                return List.of(Map.of(
                        "game_title", "PlayerUnknown's Battlegrounds",
                        "game_key", "pubg",
                        "resolution", "QHD",
                        "graphics_preset", "MEDIUM",
                        "avg_fps", avgFps,
                        "source_name", "PC-Builds FPS calculator",
                        "confidence", "MEDIUM",
                        "metadata", Map.of("gpuClass", gpuClass)
                ));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "RTX 5090으로 바꿔줘. 배그 QHD에서 평균 140FPS 이상은 나와야 해",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cpu-current", "CPU", "Ryzen 7", 1, Map.of("cpuClass", "RYZEN_7_9700X")),
                        draftItem("gpu-current", "GPU", "RTX 5060", 1, Map.of("gpuClass", "RTX_5060")),
                        draftItem("case-current", "CASE", "Demo Case", 1, Map.of("maxGpuLengthMm", 420)),
                        draftItem("psu-current", "PSU", "Demo PSU", 1, Map.of("capacityW", 1500))
                ))
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("builds")).as("response: %s", response).asList().isEmpty();
        assertThat(response.get("message").toString())
                .contains("RTX 5090")
                .contains("약 120FPS")
                .contains("140FPS에 못 미칩니다")
                .contains("바꾸지 않았습니다");
        assertThat(response.get("quickReplies")).asList()
                .contains("배틀그라운드 QHD에서 평균 140FPS 이상 나오는 GPU로 바꿔줘");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void spacedCyberpunkTargetFpsAddsVerifiedPsuWhenCurrentPowerBlocksGpu() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("category = ?")) {
                String category = invocation.getArgument(1, String.class);
                if ("GPU".equals(category)) {
                    return List.of(partRow(
                            "gpu-5070", "GPU", "RTX 5070 Demo", 930_000,
                            Map.of(
                                    "gpuClass", "RTX_5070",
                                    "hardwareClass", "RTX_5070",
                                    "requiredSystemPowerW", 650,
                                    "powerConnector", "12V-2x6",
                                    "lengthMm", 305
                            ),
                            82
                    ));
                }
                if ("PSU".equals(category)) {
                    return List.of(partRow(
                            "psu-650", "PSU", "650W ATX 3.1 Demo", 80_000,
                            Map.of(
                                    "capacityW", 650,
                                    "gpuConnector", "12V-2x6",
                                    "depthMm", 140
                            ),
                            70
                    ));
                }
            }
            if (sql.contains("FROM game_fps_benchmarks")) {
                boolean targetGpu = "RTX_5070".equals(invocation.getArgument(2, String.class));
                return List.of(Map.of(
                        "game_title", "Cyberpunk 2077",
                        "game_key", "cyberpunk-2077",
                        "resolution", "FHD",
                        "graphics_preset", "HIGH",
                        "avg_fps", targetGpu ? 138 : 112,
                        "source_name", "PC-Builds FPS calculator",
                        "confidence", "MEDIUM",
                        "metadata", Map.of("gpuClass", targetGpu ? "RTX_5070" : "RTX_5060")
                ));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(0);
            boolean targetGpu = parts.stream().anyMatch(part -> "gpu-5070".equals(part.publicId()));
            int psuCapacity = parts.stream()
                    .filter(part -> "PSU".equals(part.category()))
                    .map(part -> part.attributes().get("capacityW"))
                    .filter(Number.class::isInstance)
                    .map(Number.class::cast)
                    .mapToInt(Number::intValue)
                    .findFirst()
                    .orElse(0);
            if (targetGpu && psuCapacity < 650) {
                return List.of(
                        Map.of("tool", "compatibility", "status", "PASS", "summary", "호환성 통과"),
                        Map.of("tool", "power", "status", "FAIL", "summary", "현재 600W 파워가 GPU 권장 650W에 못 미칩니다"),
                        Map.of("tool", "size", "status", "PASS", "summary", "규격 통과"),
                        Map.of("tool", "performance", "status", "PASS", "summary", "성능 통과"),
                        Map.of("tool", "price", "status", "PASS", "summary", "가격 통과")
                );
            }
            return passingWholePlanToolResults();
        });
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "사이버 펑크 FHD에서 120FPS 이상 나오는 GPU로 변경해줘",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cpu-current", "CPU", "Core Ultra 5", 1,
                                Map.of("cpuClass", "INTEL_CORE_ULTRA_5_245K")),
                        draftItem("gpu-current", "GPU", "RTX 5060", 1,
                                Map.of("gpuClass", "RTX_5060", "requiredSystemPowerW", 550)),
                        draftItem("case-current", "CASE", "Demo Case", 1,
                                Map.of("maxGpuLengthMm", 415, "maxPsuLengthMm", 220)),
                        draftItem("psu-current", "PSU", "600W PSU", 1,
                                Map.of("capacityW", 600, "gpuConnector", "PCIe 6+2"))
                ))
        ));

        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("message").toString())
                .contains("사이버펑크 2077 FHD 평균 약 138FPS")
                .contains("파워까지 함께 바꾸는 안전한 변경안");
        assertThat(response.get("builds")).asList().singleElement().satisfies(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("appliedPartCategories")).asList().containsExactly("GPU", "PSU");
            assertThat(build.get("items")).asList()
                    .anySatisfy(item -> assertThat((Map<String, Object>) item).containsEntry("partId", "gpu-5070"))
                    .anySatisfy(item -> assertThat((Map<String, Object>) item).containsEntry("partId", "psu-650"));
            assertThat(build.get("toolResults")).asList().noneSatisfy(result ->
                    assertThat(((Map<?, ?>) result).get("status")).isEqualTo("FAIL"));
        });
        assertThat(response.get("quickReplies")).asList().contains("이 변경안 적용해줘");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void targetFpsClarificationKeepsAverageMetricWhenStabilityWasNotRequested() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "120FPS 이상 나오는 GPU로 바꿔줘"));

        assertThat(response.get("message").toString()).contains("게임을 알려주세요");
        assertThat(response.get("quickReplies")).asList()
                .contains("배그 QHD에서 평균 120FPS 이상 나오는 GPU로 바꿔줘")
                .allSatisfy(reply -> assertThat(reply.toString()).doesNotContain("안정적으로"));
        verifyNoInteractions(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
    }

    @Test
    void compatibleCaseChangeReturnsOneVerifiedReplacementPreview() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("category = ?")) {
                return List.of(partRow(
                        "case-compatible", "CASE", "호환 케이스", 120_000,
                        Map.of("maxGpuLengthMm", 420, "maxCpuCoolerHeightMm", 190), 70));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "호환되는 케이스로 바꿔줘",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("gpu-current", "GPU", "Long GPU", 1, Map.of("lengthMm", 350)),
                        draftItem("case-current", "CASE", "작은 케이스", 1, Map.of("maxGpuLengthMm", 300)),
                        draftItem("psu-current", "PSU", "Demo PSU", 1, Map.of("capacityW", 1000))
                ))
        ));

        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("builds")).as("response: %s", response).asList().singleElement().satisfies(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("items")).asList().anySatisfy(item -> {
                Map<String, Object> mapped = (Map<String, Object>) item;
                assertThat(mapped).containsEntry("partId", "case-compatible");
            });
            assertThat(build.get("toolResults")).asList().noneSatisfy(result ->
                    assertThat(((Map<?, ?>) result).get("status")).isEqualTo("FAIL"));
        });
        assertThat(response.get("quickReplies")).asList().contains("이 변경안 적용해줘");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void compatibleCaseChangeAutoAppliesOnlyWhenCurrentDraftHasCaseSizeFailure() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("category = ?")) {
                return List.of(partRow(
                        "case-compatible", "CASE", "호환 케이스", 120_000,
                        Map.of("maxGpuLengthMm", 420, "maxCpuCoolerHeightMm", 190), 70));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(0);
            boolean containsFailingCase = parts.stream()
                    .anyMatch(part -> "case-current".equals(part.publicId()));
            if (!containsFailingCase) {
                return passingWholePlanToolResults();
            }
            return List.of(
                    Map.of("tool", "compatibility", "status", "PASS", "summary", "호환성 통과"),
                    Map.of("tool", "power", "status", "PASS", "summary", "전력 통과"),
                    Map.of("tool", "size", "status", "FAIL", "summary", "GPU 길이가 케이스 허용 길이를 초과합니다."),
                    Map.of("tool", "performance", "status", "PASS", "summary", "성능 통과"),
                    Map.of("tool", "price", "status", "PASS", "summary", "가격 통과")
            );
        });
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "호환되는 케이스로 바꿔줘",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("gpu-current", "GPU", "Long GPU", 1, Map.of("lengthMm", 350)),
                        draftItem("case-current", "CASE", "작은 케이스", 1, Map.of("maxGpuLengthMm", 300)),
                        draftItem("psu-current", "PSU", "Demo PSU", 1, Map.of("capacityW", 1000))
                ))
        ));

        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("builds")).asList().singleElement().satisfies(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("badges")).asList()
                    .contains("DRAFT_EDIT_PREVIEW", "AUTO_APPLY_VERIFIED_REPAIR");
            assertThat(build.get("items")).asList().anySatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "case-compatible"));
        });
        assertThat(response.get("message").toString()).contains("자동 반영합니다");
        assertThat(response.get("quickReplies")).asList().isEmpty();
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void budgetTierSnapshotPrecomputeFollowsTargetBandRule() {
        // 티어 스냅샷 프리계산은 "N만원 PC"(target 의미) 요청에 서빙되므로 TARGET 밴드 규칙을 따른다
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        BuildChatService.TierBuilds tierBuilds = service.computeBudgetTierBuilds(8_000_000);

        assertThat(tierBuilds.builds()).isNotEmpty();
        assertThat(tierBuilds.builds()).allSatisfy(build ->
                assertThat((Integer) build.get("totalPrice")).isBetween(7_000_000, 9_000_000));
    }

    // 결정적 사다리 테스트용 촘촘한 부품 카탈로그: 카테고리별 8단계 가격(기본가 × 1~8)
    private static void stubDensePartCatalog(JdbcTemplate jdbcTemplate) {
        Map<String, Integer> basePrice = Map.of(
                "CPU", 300_000,
                "MOTHERBOARD", 150_000,
                "RAM", 100_000,
                "GPU", 500_000,
                "STORAGE", 100_000,
                "PSU", 100_000,
                "CASE", 100_000,
                "COOLER", 100_000);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (!sql.contains("FROM parts")) {
                return List.<Map<String, Object>>of();
            }
            Object[] arguments = invocation.getArguments();
            String category = arguments.length > 1 ? String.valueOf(arguments[1]) : null;
            Integer base = basePrice.get(category);
            if (base == null) {
                return List.<Map<String, Object>>of();
            }
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (int step = 1; step <= 8; step += 1) {
                int price = base * step;
                rows.add(new java.util.HashMap<String, Object>(Map.of(
                        "internal_id", (long) price,
                        "id", category.toLowerCase(java.util.Locale.ROOT) + "-" + price,
                        "category", category,
                        "name", category + " " + price,
                        "manufacturer", "테스트",
                        "price", price,
                        "attributes", "RAM".equals(category) ? "{\"moduleCount\":2,\"capacityGb\":32}" : "{}")));
            }
            return rows;
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
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
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatSemanticCacheService semanticCacheService = mock(BuildChatSemanticCacheService.class);
        CandidateReranker candidateReranker = mock(CandidateReranker.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                cacheService,
                null,
                candidateReranker,
                new PartRouteResolver(jdbcTemplate),
                new BuildChatIntentRouter(),
                semanticCacheService
        );
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
        verify(aiChatEngine).respondLlmRequired(argThat(request ->
                Boolean.TRUE.equals(request.context().get("_buildRecommendationParseOnly"))), nullable(String.class));
        verify(semanticCacheService, never()).lookup(any(), any(), any());
        verify(semanticCacheService, never()).storeAsync(any(), any(), any(), any());
    }

    @Test
    void complexHardConstraintFullBuildUsesCompactLlmSchemaWithoutHardcodedAnswers() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatSemanticCacheService semanticCacheService = mock(BuildChatSemanticCacheService.class);
        CandidateReranker candidateReranker = mock(CandidateReranker.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(overBudgetTargetResponse(true));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                cacheService,
                null,
                candidateReranker,
                new PartRouteResolver(jdbcTemplate),
                new BuildChatIntentRouter(),
                semanticCacheService
        );

        service.chat(Map.of(
                "message", "MSI 메인보드와 RTX 5070 Ti GPU를 반드시 같이 넣어 307만원 이하 게임 PC로 구성해줘",
                "currentQuoteDraft", Map.of("items", List.of())
        ));

        verify(aiChatEngine).respondLlmRequired(argThat(request ->
                Boolean.TRUE.equals(request.context().get("_buildRecommendationParseOnly"))), nullable(String.class));
        verify(semanticCacheService, never()).lookup(any(), any(), any());
        verify(semanticCacheService, never()).storeAsync(any(), any(), any(), any());
    }

    @Test
    void compactLlmSchemaGateExcludesDraftMutationsAndSinglePartRequests() {
        String fullBuild = "MSI 메인보드와 RTX 5070 Ti GPU를 반드시 같이 넣어 307만원 이하 게임 PC로 구성해줘";
        assertThat(BuildChatService.isExplicitHardFullBuildRecommendation(
                fullBuild,
                Map.of("currentQuoteDraft", Map.of("items", List.of())),
                BuildChatService.budgetIntent(fullBuild)
        )).isTrue();

        assertThat(BuildChatService.isExplicitHardFullBuildRecommendation(
                fullBuild,
                Map.of("currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "cpu-current",
                        "category", "CPU",
                        "quantity", 1
                )))),
                BuildChatService.budgetIntent(fullBuild)
        )).isFalse();

        String singlePart = "RAM 64GB를 20만원으로 맞춰줘";
        assertThat(BuildChatService.isExplicitHardFullBuildRecommendation(
                singlePart,
                Map.of("currentQuoteDraft", Map.of("items", List.of())),
                BuildChatService.budgetIntent(singlePart)
        )).isFalse();
    }

    @Test
    void buildChatDropsOverBudgetBuildWhenLlmMarksExplicitGpuSoft() {
        // LLM이 명시 5090을 소프트 선호(hardConstraintPolicy=NONE)로 판단하면, 원문 정규식이 5090을 잡아도
        // 예산 완화 차단을 풀어 과예산 조합을 제외한다(하드 강제 시 유지되는 것과 대비되는 새 계약).
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(overBudgetSoftExplicitGpuResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "price",
                "status", "WARN",
                "confidence", "HIGH",
                "summary", "예산을 초과했습니다."
        )));

        Map<String, Object> response = service.chat(Map.of("message", "300만원 이하 RTX 5090 PC 추천해줘"));

        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("warnings")).asList().contains("명시 예산 범위를 벗어난 추천 조합을 제외했습니다.");
    }

    @Test
    void buildChatRoutesFormerUnsupportedMessagesToLlmAndFallsBackToGracefulRefusalWithChips() {
        // 라우터가 UNSUPPORTED로 보던 문장("바꿔/빼줘/알림" 류)은 이제 dead-end가 아니라 LLM 제약 파서로
        // 흘러간다. LLM까지 불가한 환경에서는 기능 안내 + 바로 눌러볼 칩으로 우아하게 거절한다.
        for (String message : List.of(
                "GPU 빼줘",
                "GPU 보여줘",
                "추천 조합 장바구니에 넣어줘",
                "GPU 추천해줘",
                "5090 가격 떨어지면 알림 줘"
        )) {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            ToolCheckService toolCheckService = mock(ToolCheckService.class);
            AiChatEngine aiChatEngine = mock(AiChatEngine.class);
            BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
            when(cacheService.lookup(any(), any(), any())).thenReturn(java.util.Optional.empty());
            when(aiChatEngine.respondLlmRequired(any(), any()))
                    .thenThrow(new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다."));
            BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

            Map<String, Object> response = service.chat(Map.of("message", message));

            // LLM이 실제로 시도되었는지(강등 확인) — 예전에는 verifyNoInteractions로 즉답 거절을 고정했었다.
            verify(aiChatEngine).respondLlmRequired(any(), any());
            assertThat(response).containsEntry("answerType", "GENERAL");
            assertThat(response.get("message").toString()).contains("예산 견적 추천");
            assertThat(response.get("warnings")).asList().contains("UNSUPPORTED_INTENT");
            assertThat(response.get("builds")).asList().isEmpty();
            // dead-end 방지: 바로 눌러볼 수 있는 기능 칩이 함께 내려간다.
            assertThat(response.get("quickReplies")).asList()
                    .contains("200만원 게이밍 PC 추천해줘", "지금 견적 나머지 채워줘", "CPU를 9700X로 바꾸면?");
            if (message.contains("GPU")) {
                assertThat(response.get("message").toString()).contains("GPU");
            }
            assertThat(response).doesNotContainKeys("actions", "partRecommendation", "simulation");
        }
    }

    @Test
    void buildChatCounterProposesWhenPartConstraintExceedsBudgetUsingRealCatalogNumbers() {
        // "램 32기가 20만원" 류: LLM은 제약만 구조화하고, 부족액·최저가·예산 내 대안은 DB 실데이터로 역제안한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(java.util.Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (!sql.contains("FROM parts")) {
                return List.<Map<String, Object>>of();
            }
            if (sql.contains("ORDER BY price ASC")) {
                // cheapestMeeting: 32GB 최저가 234,000원
                return List.of(new java.util.HashMap<String, Object>(Map.of(
                        "id", "ram-32-cheapest", "name", "32GB 램 최저가", "price", 234_000,
                        "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0)));
            }
            // bestUnderBudget: 예산 내 최상 스펙 16GB 189,000원
            return List.of(new java.util.HashMap<String, Object>(Map.of(
                    "id", "ram-16-alt", "name", "16GB 램 대안", "price", 189_000,
                    "capacity_gb", 16, "vram_gb", 0, "wattage_w", 0)));
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "요청을 분석했습니다.",
                AiChatIntent.PART_RECOMMEND,
                List.<AiChatAction>of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of(
                        "category", "RAM",
                        "minCapacityGb", 32,
                        "quantity", 1,
                        "maxBudgetWon", 200_000
                )),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "램 32기가를 20만원으로 맞춰줘"));

        String message = response.get("message").toString();
        assertThat(message)
                .contains("20만원")
                .contains("32GB")
                .contains("32GB 램 최저가")
                .contains("234,000원")
                .contains("34,000원 더 필요합니다")
                .contains("16GB 램 대안");
        assertThat(response.get("warnings")).asList().contains("PART_BUDGET_SHORTFALL");
        assertThat(response.get("quickReplies")).asList()
                .contains("32GB RAM 최저가로 추천해줘", "20만원 이내 RAM 추천해줘");
    }

    @Test
    void buildChatFiltersStandalonePartRecommendationsByExplicitModelAndManufacturer() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        java.util.concurrent.atomic.AtomicBoolean sawGpuClassFilter = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean sawMsiFilter = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            List<Object> values = java.util.Arrays.asList(invocation.getArguments()).subList(1, invocation.getArguments().length);
            if (values.contains("RTX_5080")) {
                sawGpuClassFilter.set(true);
                return List.of(new java.util.HashMap<String, Object>(Map.of(
                        "id", "gpu-5080", "name", "MSI RTX 5080", "price", 1_700_000,
                        "capacity_gb", 0, "vram_gb", 16, "wattage_w", 0)));
            }
            if (values.contains("%MSI%")) {
                sawMsiFilter.set(true);
                return List.of(new java.util.HashMap<String, Object>(Map.of(
                        "id", "board-msi", "name", "MSI X870 메인보드", "price", 450_000,
                        "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0)));
            }
            return List.<Map<String, Object>>of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenAnswer(invocation -> {
            AiChatEngineRequest request = invocation.getArgument(0, AiChatEngineRequest.class);
            String category = request.message().contains("메인보드") ? "MOTHERBOARD" : "GPU";
            return new AiChatEngineResponse(
                    "요청을 분석했습니다.",
                    AiChatIntent.PART_RECOMMEND,
                    List.<AiChatAction>of(),
                    List.of(),
                    List.of(),
                    Map.of("partConstraint", Map.of("category", category)),
                    List.of(),
                    List.of(),
                    null
            );
        });
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> gpuResponse = service.chat(Map.of("message", "RTX 5080 추천"));
        Map<String, Object> boardResponse = service.chat(Map.of("message", "MSI 메인보드 추천"));

        assertThat(gpuResponse.get("message").toString()).contains("RTX 5080").doesNotContain("RTX 5090");
        assertThat(boardResponse.get("message").toString()).contains("MSI X870").doesNotContain("ASUS");
        assertThat(sawGpuClassFilter).isTrue();
        assertThat(sawMsiFilter).isTrue();
    }

    @Test
    void buildChatCounterProposesUsageMinimumWhenBudgetCannotCoverGpuRequiredUsage() {
        // "30만원 AI 학습용" 류: 용도상 GPU가 필수인데 예산이 실계산 최소 구성가 미만이면 근거 숫자로 역제안한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(java.util.Optional.empty());
        // 모든 카테고리 최저가 20만원 → 최소 구성가 = 7카테고리(GPU 포함) + RAM 2개 = 160만원
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (!sql.contains("FROM parts")) {
                return List.<Map<String, Object>>of();
            }
            return List.of(new java.util.HashMap<String, Object>(Map.of(
                    "id", "cheap-part", "name", "최저가 부품", "price", 200_000,
                    "capacity_gb", 16, "vram_gb", 8, "wattage_w", 600,
                    "category", "CPU", "manufacturer", "브랜드", "attributes", "{}")));
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "AI 학습용 구성을 검토했습니다.",
                AiChatIntent.FULL_BUILD_RECOMMEND,
                List.<AiChatAction>of(),
                List.of(),
                List.of(),
                Map.of("usageTags", List.of("AI_DEV"), "budget", 700_000),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        // 드래프트에 항목이 있으면 결정경로(단독 예산 추천)를 타지 않아 LLM 경로로 도달한다.
        Map<String, Object> response = service.chat(Map.of(
                "message", "30만원짜리 AI용 컴퓨터 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "draft-cpu", "category", "CPU", "name", "기존 CPU", "quantity", 1, "price", 200_000)
                ))
        ));

        String message = response.get("message").toString();
        assertThat(message)
                .contains("AI 학습용 PC는 그래픽카드가 필요해")
                .contains("160만원")
                .contains("30만원 예산으로는 어렵습니다");
        assertThat(response.get("warnings")).asList().contains("BUDGET_BELOW_USAGE_MINIMUM");
        assertThat(response.get("quickReplies")).asList().contains("160만원 AI 학습용 PC 추천해줘");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(message).doesNotContain("AI 학습용 구성을 검토했습니다.");
    }

    @Test
    void impossibleStandaloneBudgetUsesNaturalShortfallCopyWithoutAnInvalidBuildCard() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(new LinkedHashMap<String, Object>(Map.of(
                "id", "minimum-part",
                "category", "CPU",
                "name", "최저가 부품",
                "manufacturer", "BuildGraph",
                "price", 200_000,
                "attributes", "{}"
        )))).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "10만원으로 컴퓨터 추천해줘"));

        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("message").toString())
                .contains("가능한 최소 구성")
                .contains("부족합니다")
                .doesNotContain("을(를)");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatBuildsDraftEditPreviewCardInsteadOfMutatingTheDraft() {
        // "그래픽카드를 바꿔줘" 류: 즉시 반영하지 않고, 변경 반영 구성을 재검증한 미리보기 카드를 준다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(java.util.Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "더 저렴한 그래픽카드로 바꾸는 안을 찾았습니다.",
                AiChatIntent.BUILD_MODIFY,
                List.<AiChatAction>of(),
                List.of(),
                List.of(part("GPU", "gpu-cheaper", 800_000)),
                Map.of("draftEdit", Map.of("operation", "REPLACE", "category", "GPU")),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "그래픽카드를 더 싼 걸로 바꿔줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "draft-cpu", "category", "CPU", "name", "기존 CPU", "quantity", 1, "price", 500_000),
                        Map.of("partId", "draft-gpu", "category", "GPU", "name", "기존 GPU", "quantity", 1, "price", 1_200_000)
                ))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).hasSize(1);
        Map<String, Object> preview = builds.get(0);
        assertThat(preview.get("title")).isEqualTo("변경 적용 미리보기");
        assertThat(preview.get("totalPrice")).isEqualTo(1_300_000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) preview.get("items");
        assertThat(items).extracting(item -> item.get("partId")).containsExactlyInAnyOrder("draft-cpu", "gpu-cheaper");
        assertThat(response.get("message").toString())
                .contains("1,700,000원")
                .contains("1,300,000원")
                .contains("미리보기 카드에서 적용");
        assertThat(response).containsEntry("answerType", "PART");
    }

    @Test
    void underspecifiedPartAddReturnsChoicesInsteadOfArbitrarilyPreviewingFirstCandidate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (!sql.contains("FROM parts")) {
                return List.<Map<String, Object>>of();
            }
            return List.of(
                    Map.of("id", "ram-a", "name", "32GB RAM A", "price", 150_000,
                            "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0),
                    Map.of("id", "ram-b", "name", "32GB RAM B", "price", 170_000,
                            "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0),
                    Map.of("id", "ram-c", "name", "32GB RAM C", "price", 190_000,
                            "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0));
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "RAM 하나를 추가하겠습니다.",
                AiChatIntent.BUILD_MODIFY,
                List.of(),
                List.of(),
                List.of(part("RAM", "ram-arbitrary", 1_900_000)),
                Map.of(
                        "draftEdit", Map.of("operation", "ADD", "category", "RAM", "targetQuantity", 1),
                        "partConstraint", Map.of("category", "RAM", "quantity", 1)),
                List.of(),
                List.of(),
                null
        ));
        when(compatibilityService.compatibleCandidateSelection(
                eq(user), eq("RAM"), eq("ADD"), anyList(), anyInt()))
                .thenReturn(new PartCompatibleCandidateService.CompatibleCandidateSelection(
                        List.of("ram-a", "ram-b", "ram-c"), List.of(), List.of()));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "램 하나 넣어줘",
                "currentQuoteDraft", Map.of("items", List.of())), user);

        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("message")).asString()
                .contains("원하는 용량·가격대 또는 성능 방향")
                .doesNotContain("1,900,000원", "32GB RAM A");
        assertThat(response.get("quickReplies")).asList().containsExactly(
                "32GB RAM 추천해줘",
                "64GB RAM 추천해줘",
                "RAM 최저가로 추천해줘");
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) response.get("clarification");
        assertThat(clarification.get("missingSlots")).asList().containsExactly("partSelection");
        assertThat(clarification.get("originalMessage")).isEqualTo("RAM 추천해줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void clearDirectionalDraftEditUsesRankerAndToolCheckedPreviewWithoutLlm() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartReplacementRanker ranker = mock(PartReplacementRanker.class);
        AiChatEngineResponse.PartRecommendation sameTier = new AiChatEngineResponse.PartRecommendation(
                "gpu-same-tier", "GPU", "더 싼 RTX 5060 Ti", "테스트", 850_000,
                Map.of("gpuClass", "RTX_5060_TI"));
        AiChatEngineResponse.PartRecommendation cheaper = new AiChatEngineResponse.PartRecommendation(
                "gpu-cheaper", "GPU", "RTX 5060 하향 후보", "테스트", 600_000,
                Map.of("gpuClass", "RTX_5060"));
        when(ranker.select(eq("GPU"), anyMap(), eq("CHEAPER"), nullable(Integer.class), anyList(), eq(20)))
                .thenReturn(new PartReplacementRanker.SelectionResult(List.of(sameTier, cheaper), List.of()));
        doAnswer(invocation -> List.of(new java.util.HashMap<String, Object>(Map.of(
                "id", "gpu-cheaper",
                "category", "GPU",
                "name", "RTX 5060 가성비 GPU",
                "manufacturer", "테스트",
                "price", 600_000,
                "attributes", Map.of("gpuClass", "RTX_5060")
        )))).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService, ranker);

        Map<String, Object> response = service.chat(Map.of(
                "message", "GPU를 한 단계 낮은 더 저렴한 제품으로 바꿔줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "draft-cpu", "category", "CPU", "name", "기존 CPU", "quantity", 1, "price", 500_000),
                        Map.of("partId", "draft-gpu", "category", "GPU", "name", "기존 GPU", "quantity", 1,
                                "price", 900_000, "attributes", Map.of("gpuClass", "RTX_5060_TI"))
                ))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).hasSize(1);
        assertThat(builds.get(0)).containsEntry("tier", "draft-edit").containsEntry("totalPrice", 1_100_000);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) builds.get(0).get("items");
        assertThat(items).extracting(item -> item.get("partId"))
                .contains("gpu-cheaper")
                .doesNotContain("gpu-same-tier");
        assertThat(response.get("message").toString())
                .contains("가격이 낮고 성능 급락을 줄인")
                .contains("1,400,000원")
                .contains("1,100,000원");
        verify(ranker).select(eq("GPU"), anyMap(), eq("CHEAPER"), nullable(Integer.class), anyList(), eq(20));
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void exactSingletonPartAddChipBuildsReplacementPreviewWithoutCallingLlm() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        String targetName = "NZXT H9 Flow 2025  넉넉한 케이스";
        String userVisibleTargetName = "NZXT H9 Flow 2025 넉넉한 케이스";
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (!sql.contains("REGEXP_REPLACE(UPPER(name)")) {
                return List.<Map<String, Object>>of();
            }
            return List.of(new java.util.HashMap<String, Object>(Map.of(
                    "internal_id", 20L,
                    "id", "case-roomy",
                    "category", "CASE",
                    "name", targetName,
                    "manufacturer", "NZXT",
                    "price", 334_130,
                    "attributes", Map.of("maxGpuLengthMm", 459, "maxPsuLengthMm", 240)
            )));
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", userVisibleTargetName + " 견적에 담아줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "cpu-current", "category", "CPU", "name", "현재 CPU", "quantity", 1, "price", 500_000),
                        Map.of("partId", "case-current", "category", "CASE", "name", "현재 케이스", "quantity", 1, "price", 300_000)
                ))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        assertThat(builds).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) builds.get(0).get("items");
        assertThat(items).extracting(item -> item.get("partId"))
                .containsExactlyInAnyOrder("cpu-current", "case-roomy")
                .doesNotContain("case-current");
        assertThat(items).filteredOn(item -> "CASE".equals(item.get("category"))).hasSize(1);
        assertThat(response.get("message").toString()).contains("교체:").contains(targetName);

        Map<String, Object> samePartResponse = service.chat(Map.of(
                "message", userVisibleTargetName + " 견적에 담아줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "case-roomy", "category", "CASE", "name", targetName, "quantity", 1, "price", 334_130)
                ))
        ));
        assertThat(samePartResponse.get("builds")).asList().isEmpty();
        assertThat(samePartResponse.get("message").toString()).contains("이미 현재 견적에 선택되어 있습니다");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void exactPartChipAddsToEmptyDraftWithoutRepeatingRecommendation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(
                Map.of("tool", "compatibility", "status", "PASS", "summary", "기본 호환성을 통과했습니다."),
                Map.of("tool", "power", "status", "FAIL", "summary", "PSU가 아직 선택되지 않았습니다."),
                Map.of("tool", "price", "status", "PASS", "summary", "가격을 확인했습니다.")
        ));
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        String targetName = "조텍 GAMING 지포스 RTX 5090 SOLID OC D7 32GB";
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (!sql.contains("REGEXP_REPLACE(UPPER(name)")) {
                return List.<Map<String, Object>>of();
            }
            return List.of(new java.util.HashMap<String, Object>(Map.of(
                    "internal_id", 50L,
                    "id", "gpu-5090",
                    "category", "GPU",
                    "name", targetName,
                    "manufacturer", "ZOTAC",
                    "price", 3_300_000,
                    "attributes", Map.of("gpuClass", "RTX_5090")
            )));
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", targetName + " 견적에 담아줘",
                "currentQuoteDraft", Map.of("items", List.of())
        ));

        assertThat(response.get("builds")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = ((List<Map<String, Object>>) response.get("builds")).get(0);
        assertThat(preview.get("items")).asList().singleElement().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("partId", "gpu-5090")
                .containsEntry("category", "GPU");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> previewToolResults = (List<Map<String, Object>>) preview.get("toolResults");
        assertThat(previewToolResults.stream().map(result -> result.get("tool")).toList())
                .containsExactly("compatibility", "price")
                .doesNotContain("power");
        assertThat(response.get("message")).asString().contains("추가:", targetName);
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void exactPartChipUsesAuthenticatedActiveDraftWhenRequestOmitsDraft() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        String targetName = "커세어 FRAME 4000D LCD RS ARGB 화이트";
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("REGEXP_REPLACE(UPPER(name)")) {
                return List.of(new java.util.HashMap<String, Object>(Map.of(
                        "internal_id", 70L,
                        "id", "case-target",
                        "category", "CASE",
                        "name", targetName,
                        "manufacturer", "Corsair",
                        "price", 429_000,
                        "attributes", Map.of("maxGpuLengthMm", 430)
                )));
            }
            if (sql.contains("FROM quote_drafts qd")) {
                return List.of(
                        new java.util.HashMap<String, Object>(Map.of(
                                "part_id", "cpu-current",
                                "category", "CPU",
                                "name", "현재 CPU",
                                "manufacturer", "Intel",
                                "current_price", 500_000,
                                "quantity", 1,
                                "attributes", Map.of()
                        )),
                        new java.util.HashMap<String, Object>(Map.of(
                                "part_id", "case-current",
                                "category", "CASE",
                                "name", "현재 케이스",
                                "manufacturer", "기존",
                                "current_price", 300_000,
                                "quantity", 1,
                                "attributes", Map.of()
                        ))
                );
            }
            return List.<Map<String, Object>>of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of("message", targetName + " 견적에 담아줘"), user);

        assertThat(response.get("builds")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = ((List<Map<String, Object>>) response.get("builds")).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> previewItems = (List<Map<String, Object>>) preview.get("items");
        assertThat(previewItems.stream().map(item -> item.get("partId")).toList())
                .containsExactlyInAnyOrder("cpu-current", "case-target")
                .doesNotContain("case-current");
        assertThat(response.get("message")).asString().contains("교체:", targetName).doesNotContain("RAM 부품");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void exactRamChipIncrementsExistingPartQuantity() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        String targetName = "G.SKILL Trident Z5 Neo RGB DDR5-6000 CL30 32GB Kit";
        doAnswer(invocation -> List.of(new java.util.HashMap<String, Object>(Map.of(
                "internal_id", 80L,
                "id", "ram-target",
                "category", "RAM",
                "name", targetName,
                "manufacturer", "G.SKILL",
                "price", 245_500,
                "attributes", Map.of("capacityGb", 32, "moduleCount", 2)
        )))).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                BuildChatCacheService.disabled()
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", targetName + " 견적에 담아줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "ram-target",
                        "category", "RAM",
                        "name", targetName,
                        "manufacturer", "G.SKILL",
                        "quantity", 1,
                        "price", 245_500,
                        "attributes", Map.of("capacityGb", 32, "moduleCount", 2)
                )))
        ));

        assertThat(response.get("builds")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = ((List<Map<String, Object>>) response.get("builds")).get(0);
        assertThat(preview.get("items")).asList().singleElement().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("partId", "ram-target")
                .containsEntry("quantity", 2);
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void singletonAddFromLlmIsCoercedToReplacementPreview() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "선택한 케이스를 추가하겠습니다.",
                AiChatIntent.BUILD_MODIFY,
                List.of(),
                List.of(),
                List.of(part("CASE", "case-target", 350_000)),
                Map.of("draftEdit", Map.of("operation", "ADD", "category", "CASE")),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "선택한 케이스로 변경해줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "cpu-current", "category", "CPU", "name", "현재 CPU", "quantity", 1, "price", 500_000),
                        Map.of("partId", "case-current", "category", "CASE", "name", "현재 케이스", "quantity", 1, "price", 300_000)
                ))
        ));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> builds = (List<Map<String, Object>>) response.get("builds");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) builds.get(0).get("items");
        assertThat(items).extracting(item -> item.get("partId"))
                .containsExactlyInAnyOrder("cpu-current", "case-target")
                .doesNotContain("case-current");
        assertThat(items).filteredOn(item -> "CASE".equals(item.get("category"))).hasSize(1);
        assertThat(response.get("message").toString()).contains("교체:");
    }

    @Test
    void buildChatDoesNotOfferDraftEditPreviewWhenToolCheckFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "compatibility",
                "status", "FAIL",
                "confidence", "HIGH",
                "summary", "RAM 스틱 수가 메인보드 슬롯을 초과합니다."
        )));
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(java.util.Optional.empty());
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "램 수량을 두 개로 바꾸겠습니다.",
                AiChatIntent.BUILD_MODIFY,
                List.<AiChatAction>of(),
                List.of(),
                List.of(),
                Map.of("draftEdit", Map.of("operation", "UPDATE_QUANTITY", "category", "RAM", "targetQuantity", 2)),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "램 수량 두 개로 바꿔줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "draft-ram", "category", "RAM", "name", "32GB 2-DIMM kit", "quantity", 1, "price", 200_000)
                ))
        ));

        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("message").toString())
                .contains("적용 미리보기를 만들지 않았습니다")
                .contains("RAM 스틱 수가 메인보드 슬롯을 초과합니다");
        assertThat(response.get("quickReplies")).asList().isNotEmpty();
    }

    @Test
    void buildChatEchoesClarificationWhenModifyIntentEndsWithoutCardsOrChips() {
        // BUILD_MODIFY로 분류됐지만 카드도 칩도 못 만든 턴 — 다음 짧은 답이 맥락을 잃지 않게 원문을 에코한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(java.util.Optional.empty());
        doAnswer(invocation -> List.<Map<String, Object>>of())
                .when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "어떤 부품으로 바꿀지 조금 더 알려주세요.",
                AiChatIntent.BUILD_MODIFY,
                List.<AiChatAction>of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "그래픽카드를 더 싼 걸로 바꿔줘"));

        assertThat(response.get("builds")).asList().isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) response.get("clarification");
        assertThat(clarification).containsEntry("originalMessage", "그래픽카드를 더 싼 걸로 바꿔줘");
        // 되묻기 에코가 있으면 종단 칩 플로어는 개입하지 않는다.
        assertThat(response).doesNotContainKey("quickReplies");
    }

    @Test
    void buildChatKeepsTopicContextOnFollowUpTurnAndAddsFeatureChips() {
        // 후속 턴이 빈손이어도 종단 칩과 합성 원문을 함께 내려 다음 3~5턴에서 대상 부품을 잃지 않는다.
        // 완결된 새 명령은 isSelfContainedClarificationReply에서 이 문맥을 무시한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(java.util.Optional.empty());
        doAnswer(invocation -> List.<Map<String, Object>>of())
                .when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "어떤 부품으로 바꿀지 조금 더 알려주세요.",
                AiChatIntent.BUILD_MODIFY,
                List.<AiChatAction>of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "더 싼 걸로 바꿔줘",
                "clarificationContext", Map.of("originalMessage", "그래픽카드를")
        ));

        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "그래픽카드를 더 싼 걸로 바꿔줘");
        assertThat(response.get("quickReplies")).asList()
                .contains("200만원 게이밍 PC 추천해줘", "지금 견적 나머지 채워줘", "CPU를 9700X로 바꾸면?");
    }

    @Test
    void buildChatReturnsFixedClarificationForLowInformationMessagesWithoutLlmOrCache() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "아무거나 사줘"));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat((String) response.get("message")).contains("용도와 예산");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("warnings")).asList().contains("LOW_INFORMATION");
        // 되묻기에는 그대로 보낼 수 있는 완전한 프롬프트 칩과, 다음 턴에 에코할 원 요청이 담긴다.
        assertThat(response.get("quickReplies")).asList().contains("200만원 게이밍 PC 추천해줘");
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) response.get("clarification");
        assertThat(clarification).containsEntry("originalMessage", "아무거나 사줘");
        assertThat(response).doesNotContainKeys("actions", "partRecommendation", "simulation");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatAcknowledgesRecipientContextAndAddsServerMinimumBeforeAskingMissingInputs() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        stubMinimumPartPrices(jdbcTemplate, 200_000);
        when(aiChatEngine.acknowledgeLowInformationContext(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST")))
                .thenReturn(Optional.of("중학교 3학년 아드님이 사용할 PC를 찾고 계시는군요."));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(
                Map.of("message", "중3 아들 피시 맞출건데 추천해줘"),
                "BUILD_CHAT_54_MINI_FAST"
        );

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("message")).asString()
                .contains("중학교 3학년 아드님")
                .contains("최소 약 140만원")
                .contains("예산과 주로 사용할 용도")
                .doesNotContain("게임용으로 이해");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("warnings")).asList().contains("LOW_INFORMATION");
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("missingSlots", List.of("budget", "useCase"))
                .containsEntry("originalMessage", "중3 아들 피시 맞출건데 추천해줘");
        assertThat(response.get("quickReplies")).asList().containsExactly(
                "150만원 사무용 PC 추천해줘",
                "200만원 게이밍 PC 추천해줘",
                "300만원 게이밍 PC 추천해줘",
                "400만원 영상편집 PC 추천해줘"
        );
        verify(aiChatEngine).acknowledgeLowInformationContext(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST"));
        verifyNoInteractions(cacheService);
    }

    @Test
    void buildChatKeepsContextualClarificationAvailableWhenAcknowledgementLlmFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        stubMinimumPartPrices(jdbcTemplate, 100_000);
        when(aiChatEngine.acknowledgeLowInformationContext(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다."));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                aiChatEngine,
                mock(BuildChatCacheService.class)
        );

        Map<String, Object> response = service.chat(Map.of("message", "중3 아들 피시 맞출건데 추천해줘"));

        assertThat(response.get("message")).asString()
                .contains("중3 아들")
                .contains("최소 약 70만원")
                .contains("예산과 주로 사용할 용도");
        assertThat(response.get("warnings")).asList().contains("LOW_INFORMATION");
        assertThat(response.get("builds")).asList().isEmpty();
    }

    @Test
    void buildChatDoesNotAskForUseCaseAgainWhenRecipientAndUseCaseAreBothExplicit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        stubMinimumPartPrices(jdbcTemplate, 100_000);
        when(aiChatEngine.acknowledgeLowInformationContext(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST")))
                .thenReturn(Optional.of("중학교 3학년 아드님이 롤을 즐길 PC를 찾고 계시는군요."));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                aiChatEngine,
                mock(BuildChatCacheService.class)
        );

        Map<String, Object> response = service.chat(Map.of("message", "중3 아들이 롤 할 피시 추천해줘"));

        assertThat(response.get("message")).asString()
                .contains("롤을 즐길 PC")
                .contains("게이밍 PC 구성은 최소 약 80만원")
                .contains("예산대")
                .doesNotContain("주로 사용할 용도가 필요");
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("missingSlots", List.of("budget"));
        assertThat(response.get("quickReplies")).asList()
                .containsExactlyElementsOf(List.of(
                        "100만원 게이밍 PC 추천해줘",
                        "200만원 게이밍 PC 추천해줘",
                        "300만원 게이밍 PC 추천해줘",
                        "예산 무관 고성능 게이밍 PC 추천해줘"
                ));
    }

    @Test
    void buildChatCarriesRecipientConversationForwardWhenUseCaseIsProvidedFirst() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        stubMinimumPartPrices(jdbcTemplate, 100_000);
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "게임용으로 쓸듯",
                "clarificationContext", Map.of(
                        "originalMessage", "중3 아들 피시 맞춰줄건데 피시 추천해줘"
                )
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("message")).asString()
                .startsWith("좋아요. 중학교 3학년 아드님이 사용하실 게이밍 PC로 맞추면 되겠네요.")
                .contains("게이밍 PC")
                .contains("최소 약 80만원")
                .contains("예산대만")
                .doesNotContain("정확히 읽지 못했어요")
                .doesNotContain("용도를 알려");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("quickReplies")).asList().containsExactly(
                "100만원 게이밍 PC 추천해줘",
                "200만원 게이밍 PC 추천해줘",
                "300만원 게이밍 PC 추천해줘",
                "예산 무관 고성능 게이밍 PC 추천해줘"
        );
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("missingSlots", List.of("budget"))
                .containsEntry(
                        "originalMessage",
                        "중3 아들 피시 맞춰줄건데 피시 추천해줘 게임용으로 쓸듯"
                );
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatKeepsAnAlreadyKnownUseCaseWhenTheNextReplyAddsNoSlot() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        stubMinimumPartPrices(jdbcTemplate, 100_000);
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                mock(AiChatEngine.class),
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "아직 잘 모르겠어",
                "clarificationContext", Map.of("originalMessage", "게임용 PC 필요해")
        ));

        assertThat(response.get("message")).asString()
                .contains("게이밍 PC")
                .contains("예산대만")
                .doesNotContain("예산과 용도");
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("missingSlots", List.of("budget"));
        verifyNoInteractions(cacheService);
    }

    @Test
    void buildChatCarriesRecipientConversationForwardWhenBudgetIsProvidedFirst() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "200만원 정도",
                "clarificationContext", Map.of(
                        "originalMessage", "중3 아들 피시 맞춰줄건데 피시 추천해줘"
                )
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("message")).asString()
                .contains("예산은 200만원으로 잡을게요")
                .contains("중학교 3학년 아드님이 이 PC로 주로 무엇을 하실지")
                .doesNotContain("정확히 읽지 못했어요")
                .doesNotContain("예산을 알려");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("quickReplies")).asList()
                .contains("200만원 게이밍 PC 추천해줘");
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("missingSlots", List.of("useCase"))
                .containsEntry(
                        "originalMessage",
                        "중3 아들 피시 맞춰줄건데 피시 추천해줘 200만원 정도"
                );
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatContinuesToRecommendationAfterProgressiveSlotsAreComplete() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.of(Map.of(
                "answerType", "BUDGET",
                "message", "추천 조합을 준비했습니다.",
                "builds", List.of(Map.of("id", "build-1")),
                "warnings", List.of()
        )));
        BuildChatService service = new BuildChatService(
                jdbcTemplate,
                mock(ToolCheckService.class),
                aiChatEngine,
                cacheService
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "200만원 정도",
                "clarificationContext", Map.of(
                        "originalMessage", "중3 아들 피시 맞춰줄건데 피시 추천해줘 게임용으로 쓸듯"
                )
        ));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("builds")).asList().hasSize(1);
        assertThat(response.get("message")).asString()
                .startsWith("좋아요. 중학교 3학년 아드님이 사용하실 게이밍 PC를 200만원 예산으로 맞춰볼게요.")
                .contains("추천 조합을 준비했습니다.");
        verify(cacheService).lookup(argThat(body ->
                        "중3 아들 피시 맞춰줄건데 피시 추천해줘 게임용으로 쓸듯 200만원 정도"
                                .equals(body.get("message"))),
                any(),
                any());
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatPreservesNegatedPartPreferenceWhileAskingForMissingBuildBasis() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "RTX 5090 말고 가성비 GPU로 견적 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of())));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("message")).asString().contains("용도와 예산");
        assertThat(response.get("warnings")).asList().contains("LOW_INFORMATION");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "RTX 5090 말고 가성비 GPU로 견적 추천해줘");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatSpecializesClarificationForResolutionOnlyRequests() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, mock(ToolCheckService.class), mock(AiChatEngine.class), mock(BuildChatCacheService.class));

        Map<String, Object> response = service.chat(Map.of("message", "해상도 좋은 피시 맞춰줘"));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat((String) response.get("message")).contains("해상도");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("quickReplies")).asList()
                .contains("FHD 게이밍 150만원", "QHD 게이밍 250만원", "4K 게이밍 400만원");
    }

    @Test
    void buildChatMergesClarificationContextIntoFollowUpMessage() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        // 원 요청("해상도 좋은 피시 맞춰줘")+답변("QHD 게이밍 250만원")이 합성되어 견적 추천으로 라우팅돼야 한다.
        // 여기서는 캐시 조회까지 도달했는지(=명확화/미지원에서 멈추지 않았는지)로 라우팅을 검증한다.
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.of(Map.of(
                "answerType", "BUDGET", "message", "cached", "builds", List.of(), "warnings", List.of()
        )));

        Map<String, Object> response = service.chat(Map.of(
                "message", "QHD 게이밍 250만원",
                "clarificationContext", Map.of("originalMessage", "해상도 좋은 피시 맞춰줘")
        ));

        assertThat(response).containsEntry("answerType", "BUDGET");
        verify(cacheService).lookup(argThat(body -> "해상도 좋은 피시 맞춰줘 QHD 게이밍 250만원".equals(body.get("message"))), any(), any());
    }

    @Test
    void shortRamCapacityReplyIsNotMisreadAsAnExactProductName() {
        String merged = "RAM 추천해줘 32GB";

        assertThat(BuildChatService.simulationModelToken("RAM", merged)).isEqualTo("추천해줘 32GB");
        assertThat(BuildChatService.isGenericPartSpecToken(
                "RAM", BuildChatService.simulationModelToken("RAM", merged))).isTrue();
    }

    @Test
    void simpleValuePartRecommendationUsesDeterministicFastPath() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, mock(ToolCheckService.class), aiChatEngine, BuildChatCacheService.disabled());
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of(
                        "id", "gpu-value-a",
                        "name", "가성비 GPU A",
                        "price", 500_000,
                        "capacity_gb", 0,
                        "vram_gb", 8,
                        "wattage_w", 180),
                Map.of(
                        "id", "gpu-value-b",
                        "name", "가성비 GPU B",
                        "price", 550_000,
                        "capacity_gb", 0,
                        "vram_gb", 8,
                        "wattage_w", 190),
                Map.of(
                        "id", "gpu-value-c",
                        "name", "가성비 GPU C",
                        "price", 600_000,
                        "capacity_gb", 0,
                        "vram_gb", 12,
                        "wattage_w", 200)
        ));

        Map<String, Object> response = service.chat(Map.of(
                "message", "GPU 가성비로 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of())
        ));

        assertThat(response.get("message")).asString()
                .contains("가성비 요청", "가성비 GPU A", "가성비 GPU B", "가성비 GPU C");
        assertThat(response.get("quickReplies")).asList().hasSize(3);
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void completePartRecommendationReplyDoesNotDuplicateClarificationOriginal() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, mock(ToolCheckService.class), mock(AiChatEngine.class), cacheService);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.of(Map.of(
                "answerType", "PART", "message", "GPU 후보", "builds", List.of(), "warnings", List.of()
        )));

        Map<String, Object> response = service.chat(Map.of(
                "message", "고성능 GPU 추천해줘",
                "clarificationContext", Map.of("originalMessage", "GPU 추천해줘")
        ));

        assertThat(response).containsEntry("message", "GPU 후보");
        verify(cacheService).lookup(
                argThat(body -> "고성능 GPU 추천해줘".equals(body.get("message"))
                        && !body.containsKey("clarificationContext")),
                any(), any());
    }

    @Test
    void buildChatQuotesUserAndReAsksWhenClarificationFollowUpIsStillAmbiguous() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, mock(ToolCheckService.class), mock(AiChatEngine.class), mock(BuildChatCacheService.class));

        // 되묻기 후에도 예산·용도를 못 읽으면 임의 예산(300만) 3안을 가정하지 않는다 —
        // 사용자의 말을 인용해 무엇을 못 읽었는지 밝히고 칩과 함께 한 번 더 정확히 묻는다.
        Map<String, Object> response = service.chat(Map.of(
                "message", "알아서 해줘",
                "clarificationContext", Map.of("originalMessage", "컴퓨터 하나 맞춰줘")
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat((String) response.get("message"))
                .contains("\"알아서 해줘\"")
                .contains("정확히 읽지 못했어요");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("quickReplies")).asList().contains("200만원 게이밍 PC 추천해줘");
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) response.get("clarification");
        assertThat((String) clarification.get("originalMessage")).contains("컴퓨터 하나 맞춰줘");
    }

    @Test
    void buildChatReturnsSimulationClarificationWhenDraftIsEmptyWithoutFallingToLlm() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "그래픽카드를 5080으로 바꾸면 성능이 얼마나 좋아져?"));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response).containsEntry("message", "성능 비교는 현재 견적 기준으로 계산합니다. 먼저 셀프 견적 그래프에서 부품을 담거나 견적 추천을 받아주세요.");
        assertThat(response.get("warnings")).asList().contains("SIMULATION_TARGET_NOT_FOUND");
        assertThat(response).doesNotContainKeys("simulation", "actions", "partRecommendation");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatReturnsSimulationClarificationWhenTargetPartCannotBeResolved() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> response = service.chat(Map.of(
                "message", "그래픽카드를 5080으로 바꾸면 성능이 얼마나 좋아져?",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("gpu-current", "GPU", "RTX 5070 Ti", 1, Map.of("gpuClass", "RTX_5070_TI"))
                ))
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("warnings")).asList().contains("SIMULATION_TARGET_NOT_FOUND");
        assertThat(response).doesNotContainKeys("simulation", "actions", "partRecommendation");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatDoesNotPickArbitrarySimulationTargetWhenModelIsUnspecified() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        // 구체적 교체 대상(모델/용량/와트) 신호가 없는 시뮬레이션 요청
        Map<String, Object> response = service.chat(Map.of(
                "message", "그래픽카드 바꾸면 성능 어떻게 돼?",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("gpu-current", "GPU", "RTX 5060", 1, Map.of("gpuClass", "RTX_5060"))
                ))
        ));

        // 임의 후보(카탈로그 최상위 GPU)를 잡지 않고 되묻기로 유도해야 한다
        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("warnings")).asList().contains("SIMULATION_TARGET_NOT_FOUND");
        assertThat(response).doesNotContainKey("simulation");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatSimulatesTheTargetFromThePreviousDraftEditPreview() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        String targetId = "22222222-2222-4222-8222-222222222222";
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("WHERE public_id = ?::uuid")) {
                return List.of(partRow(targetId, "GPU", "RTX 5060 Ti preview target", 610_000,
                        Map.of("gpuClass", "RTX_5060_TI", "hardwareClass", "RTX_5060_TI"), 70));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "size", "status", "PASS", "confidence", "HIGH", "summary", "장착 가능")));

        Map<String, Object> response = service.chat(Map.of(
                "message", "바꾸면 QHD 게임 성능이 얼마나 달라져?",
                "clarificationContext", Map.of("originalMessage", "GPU를 더 저렴한 제품으로 바꿔줘"),
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("gpu-current", "GPU", "RTX 5070 Ti", 1,
                                Map.of("gpuClass", "RTX_5070_TI", "hardwareClass", "RTX_5070_TI")))),
                "currentBuilds", List.of(Map.of(
                        "badges", List.of("DRAFT_EDIT_PREVIEW"),
                        "items", List.of(Map.of(
                                "partId", targetId,
                                "category", "GPU",
                                "name", "RTX 5060 Ti preview target",
                                "quantity", 1,
                                "price", 610_000))
                ))
        ));

        assertThat(response.get("simulation"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("category", "GPU");
        @SuppressWarnings("unchecked")
        Map<String, Object> simulation = (Map<String, Object>) response.get("simulation");
        assertThat(simulation.get("targetPart"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("partId", targetId)
                .containsEntry("name", "RTX 5060 Ti preview target");
        assertThat(response).doesNotContainKeys("actions", "partRecommendation");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatEchoesOriginalMessageWhenSimulationTargetIsUnresolved() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        // 드래프트는 있지만 도착지 제품이 카탈로그에서 해상 안 되는 시뮬 dead-end
        Map<String, Object> response = service.chat(Map.of(
                "message", "그래픽카드를 5080으로 바꾸면 성능이 얼마나 좋아져?",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("gpu-current", "GPU", "RTX 5070 Ti", 1, Map.of("gpuClass", "RTX_5070_TI"))
                ))
        ));

        // 카드가 없으면 원문을 에코해 다음 짧은 답이 원 요청과 합성되게 한다
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) response.get("clarification");
        assertThat(clarification).containsEntry("originalMessage", "그래픽카드를 5080으로 바꾸면 성능이 얼마나 좋아져?");
        assertThat(response).doesNotContainKey("simulation");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatPreservesSimulationTopicWhenFollowUpStillNeedsATarget() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        // 후속 턴("MSI X870")에서도 target 해상에 실패하면 합성 문맥을 유지해 다음 설명/재추천이
        // 메인보드 주제를 잃지 않게 한다.
        Map<String, Object> response = service.chat(Map.of(
                "message", "MSI X870",
                "clarificationContext", Map.of("originalMessage", "메인보드를 바꾸면 성능 어때?"),
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("board-current", "MOTHERBOARD", "B850 Board", 1, Map.of("memoryType", "DDR5"))
                ))
        ));

        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "메인보드를 바꾸면 성능 어때? MSI X870");
        assertThat(response).doesNotContainKeys("simulation", "quickReplies");
        verifyNoInteractions(aiChatEngine, cacheService);
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
        assertThat(response).doesNotContainKeys("actions", "partRecommendation");
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
        assertThat(response).doesNotContainKeys("actions", "partRecommendation");
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
        assertThat(response).doesNotContainKeys("actions", "partRecommendation");
        verifyNoInteractions(aiChatEngine, cacheService);
    }

    @Test
    void buildChatFallsThroughToLlmForCoolingAttributeAndReturnsComparisonCard() {
        // "쿨러를 수랭으로 바꾸면" — 모델 미지정이라 시뮬 target이 안 잡히지만 dead-end 대신 LLM으로
        // 흘려보내 속성(coolingType=LIQUID)을 구조화하고, 드래프트의 현재 쿨러 vs 수랭 최적 후보를
        // 기존 1:1 스펙비교 카드로 제시한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "compatibility", "status", "PASS", "confidence", "HIGH", "summary", "호환 가능")));
        String targetPublicId = "11111111-1111-1111-1111-111111111111";
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("public_id = ?::uuid")) {
                // partByPublicId: 속성 충족 최적 후보(수랭 쿨러) 전체 행
                return List.of(Map.of(
                        "internal_id", 42L, "id", targetPublicId, "category", "COOLER",
                        "name", "쿨매 수랭 360", "manufacturer", "CoolerMaster", "price", 180_000,
                        "attributes", Map.of("coolerType", "LIQUID_AIO", "tdpW", 280, "radiatorLengthMm", 360)));
            }
            if (sql.contains("benchmark_summaries")) {
                return List.of();
            }
            if (sql.contains("FROM parts p")) {
                // meetingCheapestFirst: 속성 술어(coolerType) 통과 최저가 후보
                return List.of(Map.of(
                        "id", targetPublicId, "name", "쿨매 수랭 360", "price", 180_000,
                        "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "수랭 쿨러로 바꾸면 이렇게 달라집니다.",
                        AiChatIntent.BUILD_MODIFY,
                        List.<AiChatAction>of(),
                        List.of(),
                        List.of(),
                        Map.of("partConstraint", Map.of("category", "COOLER", "coolingType", "LIQUID")),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "쿨러를 수랭으로 바꾸면 어때?",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cooler-current", "COOLER", "공랭 쿨러 212", 1, Map.of("coolerType", "AIR", "tdpW", 150))
                ))
        ));

        // dead-end가 아니라 실데이터 1:1 카드로 답한다
        assertThat(response.get("message").toString()).doesNotContain("구체적으로");
        assertThat(response.get("simulation")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "PERFORMANCE_COMPARISON")
                .containsEntry("category", "COOLER");
        @SuppressWarnings("unchecked")
        Map<String, Object> simulation = (Map<String, Object>) response.get("simulation");
        assertThat(simulation.get("targetPart")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "쿨매 수랭 360");
        assertThat(simulation.get("specComparisons")).asList()
                .anySatisfy(row -> assertThat(row)
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                        .containsEntry("label", "냉각 방식")
                        .containsEntry("currentValue", "AIR")
                        .containsEntry("targetValue", "LIQUID_AIO"));
        // 속성 이해는 LLM이 했다 — 시뮬 dead-end로 끝나지 않고 LLM 경로로 흘러갔음을 확인
        verify(aiChatEngine).respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class));
    }

    @Test
    void buildChatKeepsCoolingRecommendationAsTopCandidatesEvenWithCurrentCooler() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts p")) {
                return List.of(
                        Map.of("id", "cooler-a", "name", "수랭 쿨러 A", "price", 180_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                        Map.of("id", "cooler-b", "name", "수랭 쿨러 B", "price", 220_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                        Map.of("id", "cooler-c", "name", "수랭 쿨러 C", "price", 260_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "수랭 쿨러 후보를 찾아봤어요.",
                        AiChatIntent.PART_RECOMMEND,
                        List.<AiChatAction>of(),
                        List.of(),
                        List.of(),
                        Map.of("partConstraint", Map.of("category", "COOLER", "coolingType", "LIQUID")),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "수랭 쿨러 추천해줘",
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cooler-current", "COOLER", "현재 공랭 쿨러", 1,
                                Map.of("coolerType", "AIR", "tdpW", 150))))));

        assertThat(response).doesNotContainKey("simulation");
        // 패널을 못 띄우는 클라이언트(capability 없음)에게는 종전 TOP 목록 문장이 그대로 나간다.
        assertThat(response.get("message")).asString().contains("수랭", "추천 TOP3");
        assertThat(response.get("quickReplies")).asList().containsExactly(
                "수랭 쿨러 A 견적에 담아줘",
                "수랭 쿨러 B 견적에 담아줘",
                "수랭 쿨러 C 견적에 담아줘");
        // 구조화된 추천 결과는 capability와 무관하게 항상 실어 보낸다 — 문장 안의 상품명은 파싱할 수 없다.
        assertThat(response.get("partRecommendation"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("category", "COOLER");
    }

    // 부품 목록 패널을 띄울 수 있는 클라이언트에게는 상품 나열을 패널에 넘기고 말풍선을 짧게 준다.
    // 같은 목록이 채팅과 패널에 두 번 나오면 어느 쪽을 봐야 할지 헷갈린다.
    @Test
    void buildChatShortensPartRecommendationMessageWhenClientCanOpenCandidatePanel() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts p")) {
                return List.of(
                        Map.of("id", "cooler-a", "name", "수랭 쿨러 A", "price", 180_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                        Map.of("id", "cooler-b", "name", "수랭 쿨러 B", "price", 220_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                        Map.of("id", "cooler-c", "name", "수랭 쿨러 C", "price", 260_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "수랭 쿨러 후보를 찾아봤어요.",
                        AiChatIntent.PART_RECOMMEND,
                        List.<AiChatAction>of(),
                        List.of(),
                        List.of(),
                        Map.of("partConstraint", Map.of("category", "COOLER", "coolingType", "LIQUID")),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "수랭 쿨러 추천해줘",
                "uiContext", Map.of("capabilities", List.of("PART_CANDIDATE_PANEL")),
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("cooler-current", "COOLER", "현재 공랭 쿨러", 1,
                                Map.of("coolerType", "AIR", "tdpW", 150))))));

        // 말풍선은 상품명·가격을 나열하지 않는다.
        assertThat(response.get("message")).asString()
                .doesNotContain("추천 TOP3")
                .doesNotContain("1)")
                .doesNotContain("수랭 쿨러 A");
        // 대신 무엇이 열렸는지 한 줄로 말한다.
        assertThat(response.get("message")).asString().contains("부품 목록");
        // 나열은 패널 몫 — 순서를 지킬 수 있게 partId가 함께 온다.
        assertThat(response.get("partRecommendation"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("category", "COOLER");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>)
                ((Map<String, Object>) response.get("partRecommendation")).get("options");
        assertThat(options).hasSize(3);
        assertThat(options).extracting(option -> option.get("partId"))
                .containsExactly("cooler-a", "cooler-b", "cooler-c");
        // 담기 칩은 그대로다 — 패널이 열려도 채팅에서 바로 담는 길을 막지 않는다.
        assertThat(response.get("quickReplies")).asList().containsExactly(
                "수랭 쿨러 A 견적에 담아줘",
                "수랭 쿨러 B 견적에 담아줘",
                "수랭 쿨러 C 견적에 담아줘");
    }

    @Test
    void barePartRecommendationRequestIsRecognizedOnlyWhenNoCriteriaRemain() {
        // 되묻기는 이 좁은 경우에만 — 카테고리와 추천 동사 말고는 아무 말도 없을 때.
        assertThat(BuildChatService.isBarePartRecommendationRequest("gpu 추천해줘")).isTrue();
        assertThat(BuildChatService.isBarePartRecommendationRequest("그래픽카드 추천")).isTrue();
        assertThat(BuildChatService.isBarePartRecommendationRequest("케이스 좀 추천해주세요")).isTrue();
        assertThat(BuildChatService.isBarePartRecommendationRequest("파워 후보 보여줘")).isTrue();

        // 사용자가 방향을 줬으면 그대로 나열한다.
        assertThat(BuildChatService.isBarePartRecommendationRequest("통풍 좋은 케이스 추천해줘")).isFalse();
        assertThat(BuildChatService.isBarePartRecommendationRequest("100만원대 gpu 추천해줘")).isFalse();
        assertThat(BuildChatService.isBarePartRecommendationRequest("가성비 gpu 추천해줘")).isFalse();
        assertThat(BuildChatService.isBarePartRecommendationRequest("조용한 쿨러 추천해줘")).isFalse();
        // 애초에 추천 요청이 아닌 문장은 건드리지 않는다(변경·수량 조작 경로 보호).
        assertThat(BuildChatService.isBarePartRecommendationRequest("램 수량 두 개로 바꿔줘")).isFalse();
        assertThat(BuildChatService.isBarePartRecommendationRequest("지금 견적 나머지 채워줘")).isFalse();
    }

    @Test
    void comparativeUpgradeReferenceTreatsModelNameAsAFloorNotAPick() {
        // 기준선 표현 — 모델명을 '고를 상품'으로 읽으면 안 된다.
        assertThat(BuildChatService.comparativeUpgradeReference("지금 5080보다 좋은 gpu 추천해줘")).isTrue();
        assertThat(BuildChatService.comparativeUpgradeReference("5080보다 나은 걸로")).isTrue();
        assertThat(BuildChatService.comparativeUpgradeReference("5080 이상으로 추천해줘")).isTrue();
        assertThat(BuildChatService.comparativeUpgradeReference("9800X3D보다 성능 높은 cpu")).isTrue();

        // 예산 하한('이상')은 비교 표현이 아니다 — 사이에 '만원'이 끼어 걸리지 않아야 한다.
        assertThat(BuildChatService.comparativeUpgradeReference("150만원 이상 gpu 추천해줘")).isFalse();
        assertThat(BuildChatService.comparativeUpgradeReference("100만원 이하 gpu 추천해줘")).isFalse();
        // 그냥 모델명 지정은 그대로 상품 지정이다.
        assertThat(BuildChatService.comparativeUpgradeReference("5080 추천해줘")).isFalse();
        assertThat(BuildChatService.comparativeUpgradeReference("rtx 5080 견적에 담아줘")).isFalse();
    }

    @Test
    void unsupportedRecommendationQualifierNamesOnlyWhatWeCannotHonor() {
        // 못 다루는 조건 — 이름을 붙여 되묻는다.
        assertThat(BuildChatService.unsupportedRecommendationQualifier("조용한 쿨러 추천해줘")).isEqualTo("소음");
        assertThat(BuildChatService.unsupportedRecommendationQualifier("발열 적은 gpu 추천해줘")).isEqualTo("발열");
        assertThat(BuildChatService.unsupportedRecommendationQualifier("롤 잘 돌아가는 gpu 추천해줘")).isEqualTo("게임 성능");
        assertThat(BuildChatService.unsupportedRecommendationQualifier("4k 게임용 gpu 추천해줘")).isEqualTo("게임 성능");
        assertThat(BuildChatService.unsupportedRecommendationQualifier("작은 케이스 추천해줘")).isEqualTo("크기");

        // 잘 되던 문장은 건드리지 않는다 — 오탐이 나면 되던 기능이 되묻기로 퇴화한다.
        assertThat(BuildChatService.unsupportedRecommendationQualifier("gpu 추천해줘")).isNull();
        assertThat(BuildChatService.unsupportedRecommendationQualifier("고성능 gpu 추천해줘")).isNull();
        assertThat(BuildChatService.unsupportedRecommendationQualifier("가성비 gpu 추천해줘")).isNull();
        assertThat(BuildChatService.unsupportedRecommendationQualifier("100만원 이하 gpu 추천해줘")).isNull();
        assertThat(BuildChatService.unsupportedRecommendationQualifier("8기가 gpu 추천해줘")).isNull();
        assertThat(BuildChatService.unsupportedRecommendationQualifier("램 수량 두 개로 바꿔줘")).isNull();
    }

    @Test
    void buildChatAsksForCriteriaWhenRecommendingAPartTheDraftAlreadyHas() {
        // "gpu 추천해줘" — 기준이 하나도 없는데 이미 RTX 5080이 담겨 있다. 후보 정렬은 호환·가격만
        // 보므로 그냥 나열하면 더 못한 5060 Ti가 "현재 견적과 호환되는 추천"으로 올라온다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts p")) {
                return List.of(
                        Map.of("id", "gpu-cheap", "name", "RTX 5060 Ti 16GB", "price", 660_000,
                                "capacity_gb", 0, "vram_gb", 16, "wattage_w", 0));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "GPU 후보를 찾아봤어요.",
                        AiChatIntent.PART_RECOMMEND,
                        List.<AiChatAction>of(),
                        List.of(),
                        List.of(),
                        Map.of("partConstraint", Map.of("category", "GPU")),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "gpu 추천해줘",
                "uiContext", Map.of("capabilities", List.of("PART_CANDIDATE_PANEL")),
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("gpu-current", "GPU", "MSI 지포스 RTX 5080", 1, Map.of("vramGb", 16))))));

        // 나열하지 않는다 — 패널도 열지 않는다.
        assertThat(response.get("partRecommendation")).isNull();
        assertThat(response.get("message")).asString()
                .contains("RTX 5080")
                .contains("어떤 기준");
        // 칩은 실제로 다른 결과를 내는 경로만 준다(성능순·가성비순·최저가순).
        assertThat(response.get("quickReplies")).asList()
                .containsExactly("고성능 GPU 추천해줘", "가성비 GPU 추천해줘", "제일 저렴한 GPU 추천해줘");
        // 다음 짧은 답이 원 요청과 합쳐지도록 원문을 에코한다.
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "gpu 추천해줘");
    }

    @Test
    void buildChatKeepsBubbleListingWhenUserAsksForTwoCategoriesAtOnce() {
        // "케이스랑 파워 추천해줘" — 패널은 한 번에 한 카테고리만 연다. 한쪽만 띄워 놓고
        // "띄웠어요"라고 답하면 나머지 한쪽은 물어본 적 없는 것처럼 사라진다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts p")) {
                return List.of(
                        Map.of("id", "psu-a", "name", "파워 A", "price", 120_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 850),
                        Map.of("id", "psu-b", "name", "파워 B", "price", 150_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 1000));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "케이스와 파워 후보를 찾아봤어요.",
                        AiChatIntent.PART_RECOMMEND,
                        List.<AiChatAction>of(),
                        List.of(),
                        List.of(),
                        Map.of("partConstraint", Map.of("category", "PSU")),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "케이스랑 파워 추천해줘",
                "uiContext", Map.of("capabilities", List.of("PART_CANDIDATE_PANEL"))));

        // 패널로 넘기지 않는다 — 넘기면 케이스를 물어본 적 없는 것처럼 된다.
        assertThat(response.get("partRecommendation")).isNull();
        // 말풍선도 줄이지 않는다 — 나열이 유일한 답이므로 그대로 있어야 한다.
        assertThat(response.get("message")).asString().doesNotContain("부품 목록에 띄웠어요");
    }

    @Test
    void buildChatListsAttributeCandidatesWhenDraftHasNoMatchingCategory() {
        // "통풍 좋은 케이스 추천해줘" — 비교 대상(드래프트 케이스) 없음 → 속성 충족 후보 TOP 나열 + 담기 칩.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts p")) {
                // meetingCheapestFirst: 통풍(airflowFocus=true) 충족 케이스 후보
                return List.of(
                        Map.of("id", "case-1", "name", "메쉬 통풍 케이스 A", "price", 89_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                        Map.of("id", "case-2", "name", "메쉬 통풍 케이스 B", "price", 112_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                        Map.of("id", "case-3", "name", "메쉬 통풍 케이스 C", "price", 148_000,
                                "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0));
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "통풍 좋은 케이스 후보를 찾아봤어요.",
                        AiChatIntent.BUILD_MODIFY,
                        List.<AiChatAction>of(),
                        List.of(),
                        List.of(),
                        Map.of(
                                "partConstraint", Map.of("category", "CASE", "airflowFocused", true),
                                "draftEdit", Map.of("operation", "REPLACE", "category", "CASE")),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "통풍 좋은 케이스 추천해줘"));

        assertThat(response.get("message").toString())
                .doesNotContain("구체적으로")
                .contains("통풍 강조")
                .contains("케이스 추천 TOP");
        assertThat(response.get("quickReplies")).asList()
                .contains("메쉬 통풍 케이스 A 견적에 담아줘");
        assertThat(response).doesNotContainKey("simulation");
    }

    @Test
    void caseScoreImprovementExcludesCurrentAndEqualFitCasesAndReturnsOnlyVerifiedScoreGains() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("ORDER BY price ASC")) {
                return List.of(
                        partRow("case-current", "CASE", "현재 H9 Flow", 320_000,
                                Map.of("maxGpuLengthMm", 459, "maxCpuCoolerHeightMm", 185,
                                        "maxPsuLengthMm", 200, "airflowFocus", true), 0),
                        partRow("case-equal", "CASE", "장착 수치가 같은 H9 파생 모델", 330_000,
                                Map.of("maxGpuLengthMm", 459, "maxCpuCoolerHeightMm", 185,
                                        "maxPsuLengthMm", 200, "airflowFocus", true), 0),
                        partRow("case-roomy", "CASE", "파워 여유 30mm 케이스", 269_000,
                                Map.of("maxGpuLengthMm", 512, "maxCpuCoolerHeightMm", 185,
                                        "maxPsuLengthMm", 230, "airflowFocus", true), 0),
                        partRow("case-blocked", "CASE", "파워가 들어가지 않는 케이스", 150_000,
                                Map.of("maxGpuLengthMm", 500, "maxCpuCoolerHeightMm", 185,
                                        "maxPsuLengthMm", 180, "airflowFocus", true), 0)
                );
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ToolBuildPart> parts = invocation.getArgument(0, List.class);
            ToolBuildPart pcCase = parts.stream().filter(part -> "CASE".equals(part.category())).findFirst().orElseThrow();
            int maxPsuLength = ((Number) pcCase.attributes().get("maxPsuLengthMm")).intValue();
            String sizeStatus = maxPsuLength < 200 ? "FAIL" : "PASS";
            return List.of(
                    Map.of("tool", "compatibility", "status", "PASS", "summary", "호환 가능", "details", Map.of()),
                    Map.of("tool", "power", "status", "PASS", "summary", "파워 충분",
                            "details", Map.of("ratedHeadroomW", 700, "ratedLoadPercent", 52)),
                    Map.of("tool", "size", "status", sizeStatus, "summary", "장착 치수 확인",
                            "details", Map.of("gpuLengthMm", 340, "maxGpuLengthMm", pcCase.attributes().get("maxGpuLengthMm"),
                                    "psuDepthMm", 200, "maxPsuLengthMm", maxPsuLength)),
                    Map.of("tool", "performance", "status", "PASS", "summary", "성능 확인",
                            "details", Map.of("cpuBenchmarkScore", 100, "gpuBenchmarkScore", 100)),
                    Map.of("tool", "price", "status", "PASS", "summary", "가격 확인", "details", Map.of())
            );
        }).when(toolCheckService).checkBuild(anyList(), anyInt());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class)))
                .thenReturn(new AiChatEngineResponse(
                        "통풍형 케이스를 추천합니다.",
                        AiChatIntent.BUILD_MODIFY,
                        List.of(),
                        List.of(),
                        List.of(part("CASE", "case-equal", 330_000)),
                        Map.of(
                                "partConstraint", Map.of("category", "CASE", "airflowFocused", true),
                                "draftEdit", Map.of("operation", "REPLACE", "category", "CASE")
                        ),
                        List.of(),
                        List.of(),
                        null
                ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        List<Map<String, Object>> draftItems = List.of(
                draftItem("cpu-current", "CPU", "Ryzen 9", 1,
                        Map.of("socket", "AM5", "cpuClass", "RYZEN_9_X3D", "coreCount", 16, "threadCount", 32, "tdpW", 170)),
                draftItem("board-current", "MOTHERBOARD", "X870E", 1,
                        Map.of("socket", "AM5", "memoryType", "DDR5", "chipset", "X870E", "pcieGeneration", 5, "hasWifi", true)),
                draftItem("ram-current", "RAM", "DDR5 64GB", 1,
                        Map.of("memoryType", "DDR5", "capacityGb", 64, "moduleCount", 2, "speedMhz", 6400)),
                draftItem("gpu-current", "GPU", "RTX 5090", 1,
                        Map.of("gpuClass", "RTX_5090", "vramGb", 32, "lengthMm", 340, "requiredSystemPowerW", 1000)),
                draftItem("ssd-current", "STORAGE", "PCIe 5 SSD", 1,
                        Map.of("capacityGb", 2000, "generation", 5, "readMbps", 12000, "writeMbps", 10000)),
                draftItem("psu-current", "PSU", "1500W PSU", 1,
                        Map.of("capacityW", 1500, "depthMm", 200, "efficiency", "PLATINUM", "atxSpec", "ATX 3.1")),
                draftItem("case-current", "CASE", "현재 H9 Flow", 1,
                        Map.of("maxGpuLengthMm", 459, "maxCpuCoolerHeightMm", 185,
                                "maxPsuLengthMm", 200, "airflowFocus", true)),
                draftItem("cooler-current", "COOLER", "360 AIO", 1,
                        Map.of("coolerType", "LIQUID", "tdpW", 320, "radiatorLengthMm", 360))
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "에어 플로우가 부족하다면 더 여유 있는 케이스 추천해줘",
                "currentQuoteDraft", draftWithItems(draftItems)
        ));

        assertThat(response.get("message").toString())
                .contains("통풍 부족으로 감점된 상태가 아닙니다")
                .contains("파워", "0mm")
                .contains("실제로 높아지고", "아래 미리보기 카드에서 적용할 수 있습니다")
                .doesNotContain("자동 반영")
                .doesNotContain("장착 수치가 같은 H9 파생 모델");
        assertThat(response.get("quickReplies")).asList()
                .contains("이 변경안 적용해줘", "현재 견적 그대로 둘게");
        assertThat(response.get("builds")).asList().singleElement().satisfies(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("badges")).asList()
                    .contains("DRAFT_EDIT_PREVIEW")
                    .doesNotContain("AUTO_APPLY_VERIFIED_CASE_IMPROVEMENT", "AUTO_APPLY_VERIFIED_REPAIR");
            assertThat(build.get("appliedPartCategories")).asList().containsExactly("CASE");
            assertThat(build.get("items")).asList().anySatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "case-roomy"));
        });
        assertThat(response.get("simulation")).isNull();
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void roomyCaseRecommendationRequiresVerifiedHeadroomGainWithoutRegression() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("FROM parts") && sql.contains("ORDER BY price ASC")) {
                return List.of(
                        partRow("case-current", "CASE", "현재 케이스", 150_000,
                                Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 180, "maxPsuLengthMm", 220), 0),
                        partRow("case-equal", "CASE", "치수가 같은 케이스", 160_000,
                                Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 180, "maxPsuLengthMm", 220), 0),
                        partRow("case-regressed", "CASE", "GPU만 넓고 쿨러가 좁은 케이스", 170_000,
                                Map.of("maxGpuLengthMm", 450, "maxCpuCoolerHeightMm", 170, "maxPsuLengthMm", 230), 0),
                        partRow("case-better", "CASE", "모든 장착 여유가 넓은 케이스", 180_000,
                                Map.of("maxGpuLengthMm", 450, "maxCpuCoolerHeightMm", 190, "maxPsuLengthMm", 240), 0)
                );
            }
            return List.of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<ToolBuildPart> parts = invocation.getArgument(0, List.class);
            ToolBuildPart pcCase = parts.stream()
                    .filter(part -> "CASE".equals(part.category()))
                    .findFirst()
                    .orElseThrow();
            int gpuHeadroom = ((Number) pcCase.attributes().get("maxGpuLengthMm")).intValue() - 300;
            int coolerHeadroom = ((Number) pcCase.attributes().get("maxCpuCoolerHeightMm")).intValue() - 140;
            int psuHeadroom = ((Number) pcCase.attributes().get("maxPsuLengthMm")).intValue() - 150;
            return List.of(
                    Map.of("tool", "compatibility", "status", "PASS", "summary", "호환 가능", "details", Map.of()),
                    Map.of("tool", "power", "status", "PASS", "summary", "파워 충분",
                            "details", Map.of("ratedHeadroomW", 400, "ratedLoadPercent", 60)),
                    Map.of("tool", "size", "status", "PASS", "summary", "장착 치수 확인",
                            "details", Map.of(
                                    "gpuHeadroomMm", gpuHeadroom,
                                    "coolerHeadroomMm", coolerHeadroom,
                                    "psuHeadroomMm", psuHeadroom)),
                    Map.of("tool", "performance", "status", "PASS", "summary", "성능 확인",
                            "details", Map.of("cpuBenchmarkScore", 80, "gpuBenchmarkScore", 80)),
                    Map.of("tool", "price", "status", "PASS", "summary", "가격 확인", "details", Map.of())
            );
        }).when(toolCheckService).checkBuild(anyList(), anyInt());
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        List<Map<String, Object>> draftItems = List.of(
                draftItem("cpu-current", "CPU", "CPU", 1, Map.of("tdpW", 120)),
                draftItem("gpu-current", "GPU", "GPU", 1, Map.of("gpuClass", "RTX_5070", "lengthMm", 300)),
                draftItem("psu-current", "PSU", "PSU", 1, Map.of("capacityW", 1000, "depthMm", 150)),
                draftItem("case-current", "CASE", "현재 케이스", 1,
                        Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 180, "maxPsuLengthMm", 220)),
                draftItem("cooler-current", "COOLER", "공랭 쿨러", 1,
                        Map.of("coolerType", "AIR", "heightMm", 140, "tdpW", 200))
        );

        Map<String, Object> response = service.chat(Map.of(
                "message", "장착 여유가 더 큰 케이스 추천해줘",
                "currentQuoteDraft", draftWithItems(draftItems)
        ));

        assertThat(response.get("message")).asString()
                .contains("확인 가능한 장착 여유가 넓은", "나빠지는 후보는 제외")
                .contains("모든 장착 여유가 넓은 케이스")
                .contains("아래 미리보기 카드에서 적용할 수 있습니다")
                .doesNotContain("자동 반영")
                .doesNotContain("치수가 같은 케이스", "GPU만 넓고 쿨러가 좁은 케이스");
        assertThat(response.get("quickReplies")).asList()
                .contains("이 변경안 적용해줘", "현재 견적 그대로 둘게");
        assertThat(response.get("builds")).asList().singleElement().satisfies(value -> {
            Map<?, ?> build = (Map<?, ?>) value;
            assertThat(build.get("badges")).asList()
                    .contains("DRAFT_EDIT_PREVIEW")
                    .doesNotContain("AUTO_APPLY_VERIFIED_CASE_IMPROVEMENT", "AUTO_APPLY_VERIFIED_REPAIR");
            assertThat(build.get("appliedPartCategories")).asList().containsExactly("CASE");
            assertThat(build.get("items")).asList().anySatisfy(item ->
                    assertThat((Map<String, Object>) item).containsEntry("partId", "case-better"));
        });
        verifyNoInteractions(aiChatEngine);
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
        cached.put("answerType", "BUDGET");
        cached.put("message", "캐시된 응답");
        cached.put("builds", List.of());
        cached.put("warnings", List.of());
        cached.put("evidenceIds", List.of());
        cached.put("agentSessionId", null);
        when(cacheService.lookup(anyMap(), eq("BUILD_CHAT_54_MINI_FAST"), eq(42L))).thenReturn(Optional.of(cached));

        Map<String, Object> response = service.chat(Map.of("message", "200만원 게임용 PC 추천해줘"), "BUILD_CHAT_54_MINI_FAST", user);

        assertThat(response).containsEntry("message", "캐시된 응답");
        assertThat(response).containsEntry("agentSessionId", null);
        assertThat(response.get("evidenceIds")).asList().isEmpty();
        verify(cacheService).lookup(anyMap(), eq("BUILD_CHAT_54_MINI_FAST"), eq(42L));
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatAsksForBudgetWithDirectionChipsForUsageOnlyRequestWithoutBudgetOrDraft() {
        // 무예산 용도-only("게임용 컴퓨터 추천해줘")는 조합 3장 즉답을 폐기하고, 서버가 즉시
        // 예산대 방향 칩을 붙여 클릭 한 번으로 다음 턴이 예산 추천으로 이어지게 한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.<Map<String, Object>>of())
                .when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "게임용 컴퓨터 추천해줘"));

        // 무관한 예산 조합이 주입되지 않는다(딱딱한 3장 폴백 폐기 확인).
        assertThat(response.get("builds")).asList().isEmpty();
        // 예산대 방향 칩 4개가 붙는다("N만원대" 문구는 다음 턴 parseBudgetWon으로 예산이 잡힌다).
        assertThat(response.get("quickReplies")).asList()
                .containsExactly(
                        "100만원 게이밍 PC 추천해줘",
                        "200만원 게이밍 PC 추천해줘",
                        "300만원 게이밍 PC 추천해줘",
                        "예산 무관 고성능 게이밍 PC 추천해줘");
        assertThat(response.get("clarification")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("missingSlots", List.of("budget"))
                .containsEntry("originalMessage", "게임용 컴퓨터 추천해줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatKeepsReturningBuildsForUsageRequestWhenBudgetIsPresent() {
        // 회귀 방지: 예산이 있으면(무예산 되묻기 조건이 아님) 기존대로 조합 카드가 나온다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());
        when(aiChatEngine.respondLlmRequired(any(AiChatEngineRequest.class), nullable(String.class))).thenReturn(buildResponse());
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of());

        Map<String, Object> response = service.chat(Map.of("message", "200만원 게임용 PC 추천해줘"));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("builds")).asList().isNotEmpty();
    }

    @Test
    void buildChatOffersMinimumCompletePlanAndPartChoicesWhenWholeTargetIsImpossible() {
        // 목표 예산이 내부 자산 최소 완성가보다 낮아도 dead-end로 끝내지 않고, 검증된 전체안과
        // 부품별 조정 선택지를 함께 제공한다. 채팅 호출 자체는 draft를 변경하지 않는다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "호환 안되는 거 전부 100만원 내에서 교체해줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "gpu-1", "category", "GPU", "name", "RTX 5090", "quantity", 1, "lineTotal", 1_200_000),
                        Map.of("partId", "cpu-1", "category", "CPU", "name", "Ryzen 9", "quantity", 1, "lineTotal", 900_000),
                        Map.of("partId", "ram-1", "category", "RAM", "name", "DDR5 64GB", "quantity", 1, "lineTotal", 600_000),
                        Map.of("partId", "ssd-1", "category", "STORAGE", "name", "NVMe 2TB", "quantity", 1, "lineTotal", 500_000)
                ))
        ));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("warnings")).asList().contains("BUDGET_BELOW_MINIMUM");
        assertThat(response.get("builds")).asList().singleElement().satisfies(value -> {
            Map<String, Object> build = (Map<String, Object>) value;
            assertThat(build).containsEntry("label", "전체 변경안")
                    .containsEntry("title", "최소 실행 가능 전체 변경안")
                    .containsEntry("totalPrice", 1_450_000);
            List<Map<String, Object>> items = (List<Map<String, Object>>) build.get("items");
            assertThat(items).hasSize(8);
            assertThat(items).extracting(item -> String.valueOf(item.get("category")))
                    .containsExactlyInAnyOrder("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");
            assertThat(build.get("toolResults")).asList().anySatisfy(result -> assertThat(result)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("tool", "price")
                    .containsEntry("status", "WARN"));
        });
        String message = response.get("message").toString();
        assertThat(message)
                .contains("100만원")
                .contains("145만원")
                .contains("45만원")
                .contains("목표 예산을 임의로 바꾸지 않았으며");
        assertThat(response.get("quickReplies")).asList()
                .containsExactly(
                        "GPU 더 저렴한 걸로 바꿔줘",
                        "CPU 더 저렴한 걸로 바꿔줘",
                        "RAM 더 저렴한 걸로 바꿔줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatOffersOneCompleteWholePlanInsideReachableTargetBand() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(passingWholePlanToolResults());
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "전체를 200만원으로 알아서 맞춰줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "gpu-old", "category", "GPU", "name", "기존 GPU", "quantity", 1, "lineTotal", 1_400_000),
                        Map.of("partId", "cpu-old", "category", "CPU", "name", "기존 CPU", "quantity", 1, "lineTotal", 900_000)
                ))
        ));

        assertThat(response).containsEntry("answerType", "BUDGET");
        assertThat(response.get("builds")).asList().singleElement().satisfies(value -> {
            Map<String, Object> build = (Map<String, Object>) value;
            assertThat(build).containsEntry("label", "전체 변경안")
                    .containsEntry("title", "예산 맞춤 전체 변경안");
            assertThat((Integer) build.get("totalPrice")).isBetween(1_750_000, 2_250_000);
            assertThat(build.get("items")).asList().hasSize(8);
        });
        assertThat(response.get("quickReplies")).asList()
                .contains("GPU 더 저렴한 걸로 바꿔줘", "CPU 더 저렴한 걸로 바꿔줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatDoesNotExposeWholePlanWithoutCompleteToolVerification() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubDensePartCatalog(jdbcTemplate);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(
                Map.of("tool", "compatibility", "status", "PASS", "summary", "호환성 통과"),
                Map.of("tool", "power", "status", "FAIL", "summary", "전력 검증 실패")
        ));
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatService service = new BuildChatService(
                jdbcTemplate, toolCheckService, aiChatEngine, BuildChatCacheService.disabled());

        Map<String, Object> response = service.chat(Map.of(
                "message", "전체를 100만원으로 알아서 맞춰줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "gpu-old", "category", "GPU", "name", "기존 GPU", "quantity", 1, "lineTotal", 1_400_000),
                        Map.of("partId", "cpu-old", "category", "CPU", "name", "기존 CPU", "quantity", 1, "lineTotal", 900_000)
                ))
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("builds")).asList().isEmpty();
        assertThat(response.get("message").toString()).contains("전체 변경안을 자동 검증하지 못해");
        assertThat(response.get("quickReplies")).asList()
                .containsExactly("GPU 더 저렴한 걸로 바꿔줘", "CPU 더 저렴한 걸로 바꿔줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatCounterProposalListsEveryTopCandidateWithItsOwnAddChip() {
        // applyPartConstraintCounterProposal의 TOP3 나열에서 추천픽 1개가 아니라 보여준 후보 전부에
        // 개별 담기 칩을 준다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (!sql.contains("FROM parts")) {
                return List.<Map<String, Object>>of();
            }
            // meetingCheapestFirst(price ASC, name ASC): 32GB 충족 후보 3개(예산 내)
            return List.of(
                    new java.util.HashMap<String, Object>(Map.of("id", "ram-a", "name", "램 후보 A", "price", 234_000,
                            "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0)),
                    new java.util.HashMap<String, Object>(Map.of("id", "ram-b", "name", "램 후보 B", "price", 254_000,
                            "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0)),
                    new java.util.HashMap<String, Object>(Map.of("id", "ram-c", "name", "램 후보 C", "price", 274_000,
                            "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0)));
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "요청을 분석했습니다.",
                AiChatIntent.PART_RECOMMEND,
                List.<AiChatAction>of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of(
                        "category", "RAM",
                        "minCapacityGb", 32,
                        "quantity", 1,
                        "maxBudgetWon", 500_000
                )),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "램 32기가를 20만원으로 맞춰줘"));

        // 스펙 충족(예산 내) → TOP3 나열, 후보 3개 각각의 담기 칩.
        assertThat(response.get("quickReplies")).asList()
                .containsExactly(
                        "램 후보 A 견적에 담아줘",
                        "램 후보 B 견적에 담아줘",
                        "램 후보 C 견적에 담아줘");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands = (List<Map<String, Object>>) response.get("quickReplyCommands");
        assertThat(commands).extracting(command -> command.get("partId"))
                .containsExactly("ram-a", "ram-b", "ram-c");
        assertThat(commands).allSatisfy(command -> assertThat(command)
                .containsEntry("type", "ADD_MULTI_ITEM_TO_DRAFT")
                .containsEntry("category", "RAM")
                .containsEntry("quantityDelta", 1));
        assertThat(response.get("clarification"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("originalMessage", "램 32기가를 20만원으로 맞춰줘");
    }

    @Test
    void buildChatBackfillsTopThreeAfterRemovingToolFailProducts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (!sql.contains("FROM parts")) {
                return List.<Map<String, Object>>of();
            }
            return List.of(
                    Map.of("id", "cpu-am5-a", "name", "AMD Ryzen 9 9950X3D", "price", 990_000,
                            "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                    Map.of("id", "cpu-am5-b", "name", "AMD Ryzen 9 9900X3D", "price", 950_000,
                            "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                    Map.of("id", "cpu-lga-a", "name", "Intel Core Ultra 9 285K", "price", 890_000,
                            "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                    Map.of("id", "cpu-lga-b", "name", "Intel Core Ultra 7 265K", "price", 500_000,
                            "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0),
                    Map.of("id", "cpu-lga-c", "name", "Intel Core Ultra 5 245K", "price", 320_000,
                            "capacity_gb", 0, "vram_gb", 0, "wattage_w", 0)
            );
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "CPU 사용 목적을 알려주시면 더 정확히 추천드릴게요.",
                AiChatIntent.PART_RECOMMEND,
                List.of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of("category", "CPU")),
                List.of(),
                List.of(),
                null
        ));
        when(compatibilityService.compatibleCandidateIds(
                eq(user), eq("CPU"), eq("REPLACE"), anyList(), eq(3)))
                .thenReturn(List.of("cpu-lga-a", "cpu-lga-b", "cpu-lga-c"));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "CPU 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "board-current",
                        "category", "MOTHERBOARD",
                        "name", "B860 Board",
                        "quantity", 1)))), user);

        assertThat(response.get("message")).asString()
                .contains("TOP3", "Intel Core Ultra 9 285K", "Intel Core Ultra 7 265K", "Intel Core Ultra 5 245K")
                .doesNotContain("AMD Ryzen 9 9950X3D", "AMD Ryzen 9 9900X3D");
        assertThat(response.get("quickReplies")).asList()
                .containsExactly(
                        "Intel Core Ultra 9 285K 견적에 담아줘",
                        "Intel Core Ultra 7 265K 견적에 담아줘",
                        "Intel Core Ultra 5 245K 견적에 담아줘");
    }

    @Test
    void buildChatKeepsExplicitCpuModelWhenBudgetIsAlsoSpecified() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("price <= ?")) {
                return List.<Map<String, Object>>of();
            }
            if (sql.contains("FROM parts")) {
                return List.of(Map.of(
                        "id", "cpu-am5",
                        "name", "AMD Ryzen 9 9950X3D",
                        "price", 990_000,
                        "capacity_gb", 0,
                        "vram_gb", 0,
                        "wattage_w", 0));
            }
            return List.<Map<String, Object>>of();
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "9950X3D 후보를 확인했습니다.",
                AiChatIntent.PART_RECOMMEND,
                List.of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of("category", "CPU", "maxBudgetWon", 500_000)),
                List.of(),
                List.of(),
                null
        ));
        when(compatibilityService.compatibleCandidateIds(
                eq(user), eq("CPU"), eq("REPLACE"), anyList(), anyInt()))
                .thenReturn(List.of("cpu-am5"));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "50만원 이하 9950X3D CPU 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of())), user);

        assertThat(response.get("message")).asString()
                .contains("9950X3D", "990,000원", "490,000원 더 필요")
                .doesNotContain("Intel", "265K", "245K");
        assertThat(response.get("warnings")).asList().contains("PART_BUDGET_SHORTFALL");
        assertThat(response.get("quickReplies")).asList()
                .contains("9950X3D 조건을 유지하고 예산을 높여 추천해줘");
    }

    @Test
    void buildChatDistinguishesExistingButIncompatibleExplicitModelFromMissingAsset() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(Map.of(
                "id", "cpu-am5",
                "name", "AMD Ryzen 9 9950X3D",
                "price", 990_000,
                "capacity_gb", 0,
                "vram_gb", 0,
                "wattage_w", 0
        ))).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "9950X3D 후보를 확인했습니다.",
                AiChatIntent.PART_RECOMMEND,
                List.of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of("category", "CPU")),
                List.of(),
                List.of(),
                null
        ));
        when(compatibilityService.compatibleCandidateIds(
                eq(user), eq("CPU"), eq("REPLACE"), anyList(), anyInt()))
                .thenReturn(List.of());
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "9950X3D CPU 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "board-current", "category", "MOTHERBOARD", "name", "B860 Board", "quantity", 1)))), user);

        assertThat(response.get("message")).asString()
                .contains("내부 자산에 있지만", "호환성 검사를 통과하지 못해")
                .doesNotContain("내부 자산에서 찾지 못했습니다");
        assertThat(response.get("quickReplies")).asList()
                .containsExactly("현재 견적의 호환 문제 설명해줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatUsesDeterministicCompatibilityPathForCurrentBuildPsuRecommendation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(
                Map.of("id", "psu-a", "name", "1200W PSU A", "price", 285_000,
                        "capacity_gb", 0, "vram_gb", 0, "wattage_w", 1200),
                Map.of("id", "psu-b", "name", "1200W PSU B", "price", 295_000,
                        "capacity_gb", 0, "vram_gb", 0, "wattage_w", 1200),
                Map.of("id", "psu-c", "name", "1000W PSU C", "price", 250_000,
                        "capacity_gb", 0, "vram_gb", 0, "wattage_w", 1000)
        )).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(compatibilityService.compatibleCandidateSelection(
                eq(user), eq("PSU"), eq("REPLACE"), anyList(), anyInt()))
                .thenReturn(new PartCompatibleCandidateService.CompatibleCandidateSelection(
                        List.of("psu-a", "psu-b", "psu-c"), List.of(), List.of()));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "현재 구성에 전력이 충분한 파워 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "gpu-current", "category", "GPU", "name", "RTX 5090", "quantity", 1)))), user);

        assertThat(response.get("answerType")).isEqualTo("PART");
        assertThat(response.get("message")).asString().contains("파워 추천 TOP3", "1200W PSU A");
        // 이 테스트의 검증력이 문구 하나에 실려 있었다(칩은 개수만 셌다) — 문구가 바뀌면 같이 무너진다.
        // 어떤 후보를 어떤 순서로 골랐는지를 칩으로 고정해, 문안이 또 바뀌어도 판정이 남게 한다.
        assertThat(response.get("quickReplies")).asList()
                .containsExactly("1200W PSU A 견적에 담아줘", "1200W PSU B 견적에 담아줘", "1000W PSU C 견적에 담아줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void conversationalPartAdviceUsesLlmOnlyToPhraseVerifiedCandidates() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "민수", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(
                Map.of("id", "psu-a", "name", "1200W PSU A", "price", 285_000,
                        "capacity_gb", 0, "vram_gb", 0, "wattage_w", 1200),
                Map.of("id", "psu-b", "name", "1200W PSU B", "price", 295_000,
                        "capacity_gb", 0, "vram_gb", 0, "wattage_w", 1200),
                Map.of("id", "psu-c", "name", "1000W PSU C", "price", 250_000,
                        "capacity_gb", 0, "vram_gb", 0, "wattage_w", 1000)
        )).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(compatibilityService.compatibleCandidateSelection(
                eq(user), eq("PSU"), eq("REPLACE"), anyList(), anyInt()))
                .thenReturn(new PartCompatibleCandidateService.CompatibleCandidateSelection(
                        List.of("psu-a", "psu-b", "psu-c"), List.of(), List.of()));
        when(aiChatEngine.explainVerifiedChangeAdvice(any(AiChatEngineRequest.class), eq("BUILD_CHAT_54_MINI_FAST")))
                .thenReturn(Optional.of(
                        "지금까지 말씀하신 조건과 현재 견적을 기준으로 1200W PSU A가 검증된 우선 후보입니다. "
                                + "이 제품으로 교체안을 확인할까요?"));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "지금까지 말한 조건 기준으로 현재 견적에 맞는 고성능 파워 제품 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "gpu-current", "category", "GPU", "name", "RTX 5090", "quantity", 1)))), user);

        assertThat(response).containsEntry("answerType", "PART");
        assertThat(response.get("message")).asString()
                .contains("지금까지 말씀하신 조건", "1200W PSU A", "교체안을 확인할까요?");
        assertThat(response.get("quickReplies")).asList()
                .containsExactly(
                        "1200W PSU A 견적에 담아줘",
                        "1200W PSU B 견적에 담아줘",
                        "1000W PSU C 견적에 담아줘");
        verify(aiChatEngine).explainVerifiedChangeAdvice(argThat(request -> {
            Map<String, Object> facts = (Map<String, Object>) request.context().get("verifiedChangeAdvice");
            return "PSU".equals(facts.get("category"))
                    && "1200W PSU A".equals(facts.get("primaryCandidate"))
                    && "REPLACE".equals(facts.get("actionKind"));
        }), eq("BUILD_CHAT_54_MINI_FAST"));
        verify(aiChatEngine, never()).respondLlmRequired(any(AiChatEngineRequest.class), any());
    }

    @Test
    void buildChatKeepsCurrentQuoteStorageRefinementOnDeterministicPath() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(
                Map.of("id", "ssd-a", "name", "NVMe SSD 2TB A", "price", 235_000,
                        "capacity_gb", 2000, "vram_gb", 0, "wattage_w", 0),
                Map.of("id", "ssd-b", "name", "NVMe SSD 2TB B", "price", 260_000,
                        "capacity_gb", 2000, "vram_gb", 0, "wattage_w", 0),
                Map.of("id", "ssd-c", "name", "NVMe SSD 4TB C", "price", 400_000,
                        "capacity_gb", 4000, "vram_gb", 0, "wattage_w", 0)
        )).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(compatibilityService.compatibleCandidateSelection(
                eq(user), eq("STORAGE"), eq("ADD"), anyList(), anyInt()))
                .thenReturn(new PartCompatibleCandidateService.CompatibleCandidateSelection(
                        List.of("ssd-a", "ssd-b", "ssd-c"), List.of(), List.of()));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "장착 불가 후보는 빼고 다시 선택지를 보여줘",
                "clarificationContext", Map.of("originalMessage",
                        "지금 견적에 2TB NVMe SSD 추천해줘 첫 번째 후보를 적용하면 현재 구성에서 문제가 없는지 설명해줘"),
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "board-current", "category", "MOTHERBOARD", "name", "현재 메인보드", "quantity", 1)))), user);

        assertThat(response.get("answerType")).isEqualTo("PART");
        assertThat(response.get("message")).asString().contains("조건(2000GB)", "추천 TOP3", "NVMe SSD 2TB A");
        assertThat(response.get("quickReplies")).asList().hasSize(3);
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatDoesNotRecommendTheAlreadySelectedSingleItemAgain() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(Map.of(
                "id", "psu-current",
                "name", "FSP HYPER K PRO 600W",
                "price", 67_000,
                "capacity_gb", 0,
                "vram_gb", 0,
                "wattage_w", 600
        ))).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(compatibilityService.compatibleCandidateSelection(
                eq(user), eq("PSU"), eq("REPLACE"), anyList(), anyInt()))
                .thenReturn(new PartCompatibleCandidateService.CompatibleCandidateSelection(
                        List.of(), List.of("psu-current"), List.of()));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "FSP HYPER K PRO 600W 파워 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "psu-current", "category", "PSU", "name", "FSP HYPER K PRO 600W", "quantity", 1)))), user);

        assertThat(response.get("message")).asString().contains("이미 현재 견적에 선택되어 있습니다");
        assertThat(response.get("quickReplies")).asList().containsExactly("파워 다른 후보 추천해줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatDoesNotRepeatAlreadySelectedRamInRecommendationTopThree() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(
                Map.of("id", "ram-current", "name", "현재 RAM", "price", 150_000,
                        "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0),
                Map.of("id", "ram-a", "name", "대안 RAM A", "price", 170_000,
                        "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0),
                Map.of("id", "ram-b", "name", "대안 RAM B", "price", 180_000,
                        "capacity_gb", 32, "vram_gb", 0, "wattage_w", 0)
        )).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "RAM 후보를 확인했습니다.",
                AiChatIntent.PART_RECOMMEND,
                List.of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of("category", "RAM", "minCapacityGb", 32, "quantity", 1)),
                List.of(),
                List.of(),
                null
        ));
        when(compatibilityService.compatibleCandidateSelection(
                eq(user), eq("RAM"), eq("ADD"), anyList(), anyInt()))
                .thenReturn(new PartCompatibleCandidateService.CompatibleCandidateSelection(
                        List.of("ram-current", "ram-a", "ram-b"), List.of("ram-current"), List.of()));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "32GB RAM 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "ram-current", "category", "RAM", "name", "현재 RAM", "quantity", 1)))), user);

        assertThat(response.get("message")).asString()
                .contains("대안 RAM A", "대안 RAM B")
                .doesNotContain("1) 현재 RAM");
        assertThat(response.get("quickReplies")).asList()
                .containsExactly("대안 RAM A 견적에 담아줘", "대안 RAM B 견적에 담아줘");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands = (List<Map<String, Object>>) response.get("quickReplyCommands");
        assertThat(commands).extracting(command -> command.get("partId"))
                .containsExactly("ram-a", "ram-b");
    }

    @Test
    void buildChatExplainsCompatibilityBlockInsteadOfRepeatingAnImpossibleRamRecommendation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(Map.of(
                "id", "ram-kit",
                "name", "DDR5 32GB Kit",
                "price", 180_000,
                "capacity_gb", 32,
                "vram_gb", 0,
                "wattage_w", 0
        ))).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "RAM 후보를 찾아볼게요.",
                AiChatIntent.PART_RECOMMEND,
                List.of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of("category", "RAM", "minCapacityGb", 32, "quantity", 1)),
                List.of(),
                List.of(),
                null
        ));
        when(compatibilityService.compatibleCandidateIds(
                eq(user), eq("RAM"), eq("ADD"), anyList(), eq(3))).thenReturn(List.of());
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        service.setPartCompatibleCandidateService(compatibilityService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "20만원 이하 32GB RAM 추천해줘",
                "currentQuoteDraft", Map.of("items", List.of(Map.of(
                        "partId", "ram-current", "category", "RAM", "name", "현재 RAM", "quantity", 2)))), user);

        assertThat(response.get("message")).asString()
                .contains("현재 견적", "호환성 검사를 통과하지 못합니다")
                .doesNotContain("내부 자산에서 찾지 못했습니다");
        assertThat(response.get("quickReplies")).asList()
                .containsExactly("현재 견적의 RAM 호환 문제 설명해줘", "현재 RAM 하나 빼줘")
                .doesNotContain("32GB RAM 최저가로 추천해줘");
        verifyNoInteractions(aiChatEngine);
    }

    @Test
    void buildChatKeepsSingleCategoryRecommendationChipsOnPreviewFlow() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.of(
                new java.util.HashMap<String, Object>(Map.of("id", "gpu-a", "name", "GPU 후보 A", "price", 900_000,
                        "capacity_gb", 0, "vram_gb", 16, "wattage_w", 300))
        )).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "요청을 분석했습니다.",
                AiChatIntent.PART_RECOMMEND,
                List.of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of("category", "GPU", "minVramGb", 16, "quantity", 1)),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "VRAM 16GB 그래픽카드 추천해줘"));

        assertThat(response.get("quickReplies")).asList().containsExactly("GPU 후보 A 견적에 담아줘");
        assertThat(response).doesNotContainKey("quickReplyCommands");
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

    private static AiChatEngineResponse overBudgetSoftExplicitGpuResponse() {
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
                Map.of(
                        "budget", 3_000_000,
                        "budgetMode", "MAX",
                        "budgetPolicy", "USER_BUDGET",
                        "hardConstraintPolicy", "NONE",
                        "requiredGpuClasses", List.of("RTX_5090"),
                        "requiredPartKeywords", List.of("RTX 5090")
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

    private static void stubMinimumPartPrices(JdbcTemplate jdbcTemplate, int unitPrice) {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            Object parameter = invocation.getArgument(1);
            String category = parameter instanceof Object[] params
                    ? String.valueOf(params[0])
                    : String.valueOf(parameter);
            return List.of(Map.of(
                    "id", "minimum-" + category.toLowerCase(java.util.Locale.ROOT),
                    "name", category + " minimum part",
                    "price", unitPrice,
                    "capacity_gb", "RAM".equals(category) ? 16 : 0,
                    "vram_gb", "GPU".equals(category) ? 8 : 0,
                    "wattage_w", "PSU".equals(category) ? 600 : 0
            ));
        });
    }

    private static List<Map<String, Object>> passingWholePlanToolResults() {
        return List.of(
                Map.of("tool", "compatibility", "status", "PASS", "summary", "호환성 통과"),
                Map.of("tool", "power", "status", "PASS", "summary", "전력 통과"),
                Map.of("tool", "size", "status", "PASS", "summary", "규격 통과"),
                Map.of("tool", "performance", "status", "PASS", "summary", "성능 통과"),
                Map.of("tool", "price", "status", "PASS", "summary", "가격 통과")
        );
    }
}
