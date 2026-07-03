package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.part.PartAliasReviewService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class DefaultAiChatEngineTest {
    private JdbcTemplate jdbcTemplate;
    private AgentTraceService agentTraceService;
    private AgentRagRetrievalService agentRagRetrievalService;
    private OpenAiResponsesClient openAiResponsesClient;
    private PartAliasReviewService partAliasReviewService;
    private DefaultAiChatEngine engine;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        agentTraceService = mock(AgentTraceService.class);
        agentRagRetrievalService = mock(AgentRagRetrievalService.class);
        openAiResponsesClient = mock(OpenAiResponsesClient.class);
        partAliasReviewService = mock(PartAliasReviewService.class);
        engine = new DefaultAiChatEngine(
                jdbcTemplate,
                agentTraceService,
                agentRagRetrievalService,
                openAiResponsesClient,
                AiProfileConfigTest.config("AS_CHAT_FAST", "BUILD_CHAT_FAST"),
                new PartReplacementRanker(partAliasReviewService),
                new PartRouteResolver(jdbcTemplate)
        );

        doAnswer(invocation -> {
                    Object category = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    return partRows(String.valueOf(category)).stream().limit(limit).toList();
                })
                .when(jdbcTemplate)
                .queryForList(anyString(), anyString(), anyInt());
    }

    @Test
    void fullBuildRecommendationReturnsThreeBuildsAndDraftActions() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "QHD 게임용 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .contains(AiChatActionType.OPEN_SELF_QUOTE, AiChatActionType.ADD_BUILD_TO_DRAFT);
        verifyNoJdbcWrites();
    }

    @Test
    void explicitRtx5090BuildKeepsGpuClassAsHardConstraint() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "RTX 5090 글카가 들어간 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext().get("requiredGpuClasses")).asList().contains("RTX_5090");
        assertThat(response.parsedContext()).containsEntry("hardConstraintPolicy", "MUST_INCLUDE");
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "GPU".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.attributes()).containsEntry("gpuClass", "RTX_5090")));
        verifyNoJdbcWrites();
    }

    @Test
    void fullBuildRecommendationHonorsExplicitCpuModelToken() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "CPU 9700X 들어간 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext()).containsEntry("hardConstraintPolicy", "MUST_INCLUDE");
        assertThat(response.parsedContext().get("requiredPartKeywords")).asList().contains("9700X");
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "CPU".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.name()).contains("9700X")));
        verifyNoJdbcWrites();
    }

    @Test
    void fullBuildRecommendationHonorsMotherboardBrandToken() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "MSI 메인보드 들어간 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext()).containsEntry("hardConstraintPolicy", "MUST_INCLUDE");
        assertThat(response.parsedContext().get("requiredPartKeywords")).asList().contains("MSI");
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "MOTHERBOARD".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.name()).contains("MSI")));
        verifyNoJdbcWrites();
    }

    @Test
    void fullBuildRecommendationHonorsRamSingleModuleConstraint() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "RAM 32GB 한 개 들어간 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext())
                .containsEntry("hardConstraintPolicy", "MUST_INCLUDE")
                .containsEntry("targetCapacityGb", 32)
                .containsEntry("targetModuleCount", 1)
                .containsEntry("targetQuantity", 1);
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "RAM".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.attributes())
                                .containsEntry("capacityGb", 32)
                                .containsEntry("moduleCount", 1)));
        assertThat(response.actions())
                .filteredOn(action -> action.type() == AiChatActionType.ADD_BUILD_TO_DRAFT)
                .singleElement()
                .satisfies(action -> assertThat(objectMaps(action.payload().get("items")))
                        .filteredOn(item -> "RAM".equals(item.get("category")))
                        .singleElement()
                        .satisfies(item -> assertThat(item).containsEntry("quantity", 1)));
        verifyNoJdbcWrites();
    }

    @Test
    void fullBuildRecommendationHonorsCaseBrandAndModelToken() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "케이스 리안리 216 모델 포함해서 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext()).containsEntry("hardConstraintPolicy", "MUST_INCLUDE");
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "CASE".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.partId()).isEqualTo("case-lianli-216")));
        verifyNoJdbcWrites();
    }

    @Test
    void fullBuildRecommendationDoesNotFallbackWhenRequiredCaseUnavailable() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "케이스 리안리 999 모델 포함해서 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext()).containsEntry("hardConstraintPolicy", "MUST_INCLUDE");
        assertThat(response.parsedContext().get("requiredPartKeywords")).asList().contains("LIANLI", "999");
        assertThat(response.recommendations()).isEmpty();
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsExactly(AiChatActionType.OPEN_SELF_QUOTE);
        assertThat(response.assistantMessage()).contains("조건을 만족하는 내부 자산 후보를 찾지 못했습니다");
        verifyNoJdbcWrites();
    }

    @Test
    void openBudgetEnthusiastRequestDoesNotCreateDefaultBudget() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "끝판왕 컴퓨터 만들어줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext())
                .containsEntry("performanceTier", "ENTHUSIAST")
                .containsEntry("budgetPolicy", "OPEN_BUDGET")
                .containsEntry("budget", null);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "GPU".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.partId()).isEqualTo("gpu-5090")));
        verifyNoJdbcWrites();
    }

    @Test
    void performanceRequestWithoutBudgetStaysUnspecifiedInsteadOfDefaultBudget() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "QHD 배그 144Hz 목표로 맞춰줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext())
                .containsEntry("performanceTier", "PERFORMANCE")
                .containsEntry("budgetPolicy", "UNSPECIFIED")
                .containsEntry("resolution", "QHD")
                .containsEntry("budget", null);
        assertThat(response.parsedContext().get("usageTags")).asList().contains("GAMING");
        verifyNoJdbcWrites();
    }

    @Test
    void minimumBudgetRequestDoesNotReturnValueBuildBelowRequestedFloor() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "300만원 이상으로 게임용 PC 맞춰줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations().get(0).name()).contains("기준 이상");
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.estimatedTotalPrice()).isGreaterThanOrEqualTo(3_000_000));
        verifyNoJdbcWrites();
    }

    @Test
    void targetBudgetRequestStaysInsideBudgetBandEvenWithPremiumWords() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "800만원으로 최고급 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext())
                .containsEntry("budget", 8_000_000)
                .containsEntry("budgetPolicy", "USER_BUDGET")
                .containsEntry("budgetMode", "TARGET");
        assertThat(response.recommendations()).isNotEmpty();
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.estimatedTotalPrice())
                        .isBetween(7_000_000, 9_000_000));
        verifyNoJdbcWrites();
    }

    @Test
    void maxBudgetRequestDoesNotReturnBuildAboveBudgetWithoutHardConstraint() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "800만원 이하로 게임용 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext())
                .containsEntry("budget", 8_000_000)
                .containsEntry("budgetMode", "MAX");
        assertThat(response.recommendations()).isNotEmpty();
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.estimatedTotalPrice())
                        .isLessThanOrEqualTo(8_000_000));
        verifyNoJdbcWrites();
    }

    @Test
    void partRecommendationReturnsPartCandidatesAndAddPartActions() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "RTX 5070 중에 뭐가 좋아?",
                "SELF_QUOTE",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.PART_RECOMMEND);
        assertThat(response.partRecommendations()).hasSize(2);
        assertThat(response.partRecommendations())
                .allSatisfy(part -> assertThat(part.category()).isEqualTo("GPU"));
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsOnly(AiChatActionType.ADD_PART_TO_DRAFT);
        verifyNoJdbcWrites();
    }

    @Test
    void partRecommendationHonorsExplicitCpuModelToken() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "CPU 9700X인 거 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.PART_RECOMMEND);
        assertThat(response.partRecommendations()).isNotEmpty();
        assertThat(response.partRecommendations())
                .allSatisfy(part -> assertThat(part.name()).contains("9700X"));
        assertThat(response.parsedContext().get("requiredPartKeywords")).asList().contains("9700X");
        verifyNoJdbcWrites();
    }

    @Test
    void partRecommendationHonorsRamCapacityAndSingleModuleRequest() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "램 32기가 한 개 달린 거 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.PART_RECOMMEND);
        assertThat(response.partRecommendations())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("ram-32-single");
        assertThat(response.partRecommendations())
                .allSatisfy(part -> assertThat(part.attributes())
                        .containsEntry("capacityGb", 32)
                        .containsEntry("moduleCount", 1));
        assertThat(response.parsedContext())
                .containsEntry("targetCapacityGb", 32)
                .containsEntry("targetModuleCount", 1)
                .containsEntry("targetQuantity", 1);
        verifyNoJdbcWrites();
    }

    @Test
    void partRecommendationHonorsCaseBrandAndModelToken() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "케이스 리안리 216 모델꺼로 맞춰줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.PART_RECOMMEND);
        assertThat(response.partRecommendations())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("case-lianli-216");
        assertThat(response.parsedContext().get("requiredPartKeywords"))
                .asList()
                .contains("LIANLI", "216");
        verifyNoJdbcWrites();
    }

    @Test
    void partRecommendationHonorsMotherboardBrandToken() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "메인보드 MSI 걸로 맞춰줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.PART_RECOMMEND);
        assertThat(response.partRecommendations()).hasSize(2);
        assertThat(response.partRecommendations())
                .allSatisfy(part -> assertThat(part.name()).contains("MSI"));
        assertThat(response.parsedContext().get("requiredPartKeywords"))
                .asList()
                .contains("MSI");
        verifyNoJdbcWrites();
    }

    @Test
    void buildModifyReturnsReplaceDraftPartAction() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "이 견적에서 램 64기가로 바꿔줘",
                "SELF_QUOTE",
                null,
                null,
                "draft-1",
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.BUILD_MODIFY);
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsExactly(AiChatActionType.REPLACE_DRAFT_PART);
        assertThat(response.actions().get(0).payload()).containsEntry("category", "RAM");
        verifyNoJdbcWrites();
    }

    @Test
    void buildModifyCheaperGpuUsesCurrentDraftPriceAsCeiling() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "그래픽카드 너무 비싼데 더 싼 후보 추천해줘",
                "SELF_QUOTE",
                null,
                null,
                "draft-1",
                Map.of("currentQuoteDraft", Map.of(
                        "items", List.of(Map.of(
                                "partId", "gpu-5090",
                                "category", "GPU",
                                "name", "GeForce RTX 5090 32GB",
                                "currentPrice", 5_000_000,
                                "quantity", 1
                        ))
                )),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.BUILD_MODIFY);
        assertThat(response.parsedContext()).containsEntry("category", "GPU");
        assertThat(response.parsedContext().get("draftEdit"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("priceDirection", "CHEAPER");
        assertThat(response.partRecommendations())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("gpu-5080", "gpu-5070-ti", "gpu-5070");
        assertThat(response.partRecommendations())
                .allSatisfy(part -> assertThat(part.price()).isLessThan(5_000_000));
        verifyNoJdbcWrites();
    }

    @Test
    void buildModifyBetterGpuDoesNotRecommendLowerGpuClass() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "그래픽카드 더 좋은 걸로 추천해줘",
                "SELF_QUOTE",
                null,
                null,
                "draft-1",
                Map.of("currentQuoteDraft", Map.of(
                        "items", List.of(Map.of(
                                "partId", "gpu-5070-ti",
                                "category", "GPU",
                                "name", "GeForce RTX 5070 Ti 16GB",
                                "currentPrice", 1_200_000,
                                "quantity", 1
                        ))
                )),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.BUILD_MODIFY);
        assertThat(response.partRecommendations())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("gpu-5080", "gpu-5090");
        assertThat(response.partRecommendations())
                .extracting(part -> String.valueOf(part.attributes().get("gpuClass")))
                .doesNotContain("RTX_5060", "RTX_5070");
        verifyNoJdbcWrites();
    }

    @Test
    void buildModifyBetterCpuUsesCpuRankInsteadOfPriceOnly() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "CPU 더 좋은 걸로 바꿔줘",
                "SELF_QUOTE",
                null,
                null,
                "draft-1",
                Map.of("currentQuoteDraft", Map.of(
                        "items", List.of(Map.of(
                                "partId", "cpu-mid",
                                "category", "CPU",
                                "name", "CPU Mid",
                                "currentPrice", 300_000,
                                "quantity", 1
                        ))
                )),
                1L
        ));

        assertThat(response.partRecommendations())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("cpu-high");
        verifyNoJdbcWrites();
    }

    @Test
    void buildModifyBetterMotherboardKeepsCurrentCpuSocketAndMemoryType() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "보드 더 좋은 걸로 추천해줘",
                "SELF_QUOTE",
                null,
                null,
                "draft-1",
                Map.of("currentQuoteDraft", Map.of(
                        "items", List.of(
                                Map.of(
                                        "partId", "cpu-mid",
                                        "category", "CPU",
                                        "name", "CPU Mid",
                                        "currentPrice", 300_000,
                                        "quantity", 1,
                                        "attributes", Map.of("socket", "AM5")
                                ),
                                Map.of(
                                        "partId", "motherboard-mid",
                                        "category", "MOTHERBOARD",
                                        "name", "AM5 B850 Board",
                                        "currentPrice", 240_000,
                                        "quantity", 1,
                                        "attributes", Map.of("socket", "AM5", "memoryType", "DDR5", "chipset", "B850", "pcieGeneration", "4.0")
                                ),
                                Map.of(
                                        "partId", "ram-ddr5",
                                        "category", "RAM",
                                        "name", "DDR5 RAM",
                                        "currentPrice", 180_000,
                                        "quantity", 2,
                                        "attributes", Map.of("memoryType", "DDR5")
                                )
                        )
                )),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.BUILD_MODIFY);
        assertThat(response.partRecommendations())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("motherboard-asus-x870");
        assertThat(response.partRecommendations())
                .extracting(part -> String.valueOf(part.attributes().get("socket")))
                .containsOnly("AM5");
        verifyNoJdbcWrites();
    }

    @Test
    void buildModifyCheaperPsuKeepsStrongestLowerPriceCandidate() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "파워가 너무 비싸니 더 싼 걸로 추천해줘",
                "SELF_QUOTE",
                null,
                null,
                "draft-1",
                Map.of("currentQuoteDraft", Map.of(
                        "items", List.of(Map.of(
                                "partId", "psu-high",
                                "category", "PSU",
                                "name", "PSU High",
                                "currentPrice", 260_000,
                                "quantity", 1
                        ))
                )),
                1L
        ));

        assertThat(response.partRecommendations())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("psu-mid", "psu-low");
        verifyNoJdbcWrites();
    }

    @Test
    void priceAlertHelpExtractsTargetPriceAction() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "이 GPU 80만원 되면 알려줘",
                "PART_DETAIL",
                "GPU",
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.PRICE_ALERT_HELP);
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsExactly(AiChatActionType.CREATE_PRICE_ALERT);
        assertThat(response.actions().get(0).payload()).containsEntry("targetPrice", 800_000);
        verifyNoJdbcWrites();
    }

    @Test
    void vagueMessageAsksFollowUpInsteadOfGuessingRecommendation() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.ASK_FOLLOW_UP);
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsExactly(AiChatActionType.ASK_FOLLOW_UP);
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredBuildChatFailsWhenOpenAiKeyIsMissing() {
        when(openAiResponsesClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> engine.respondLlmRequired(new AiChatEngineRequest(
                "200만원 QHD 게임용 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredBuildChatUsesStructuredPlanAndKeepsExplicitRtx5090Constraint() {
        when(openAiResponsesClient.isConfigured()).thenReturn(true);
        when(agentRagRetrievalService.retrieveEvidenceSet(any(), eq(AgentRunProfiles.requirementParse()), anyString(), anyInt()))
                .thenReturn(List.of(new AgentRagEvidenceDraft(
                        "requirement-rule-explicit-gpu-class-hard-constraint",
                        "Explicit RTX 5090 should be a hard constraint.",
                        "Explicit GPU class hard constraint parse rule.",
                        BigDecimal.valueOf(0.99),
                        Map.of("sourceEvidenceId", "evidence-5090", "purpose", "REQUIREMENT_PARSE")
                )));
        when(openAiResponsesClient.createStructuredJsonResult(
                anyString(),
                anyString(),
                eq("buildgraph_ai_build_chat_plan"),
                any(),
                eq("gpt-5.5"),
                eq("low"),
                eq(900)
        ))
                .thenReturn(new LlmResponseResult("""
                        {
                          "intent": "FULL_BUILD_RECOMMEND",
                          "assistantMessage": "RTX 5090 조건을 유지해 추천 조합을 만들겠습니다.",
                          "selectedCategory": null,
                          "parsedContext": {
                            "budget": null,
                            "usageTags": ["GAMING"],
                            "resolution": null,
                            "preferredVendors": ["NVIDIA"],
                            "priority": null,
                            "performanceTier": "ENTHUSIAST",
                            "budgetPolicy": "OPEN_BUDGET",
                            "mustHave": [],
                            "requiredGpuClasses": ["RTX_5090"],
                            "requiredPartKeywords": [],
                            "hardConstraintPolicy": "MUST_INCLUDE",
                            "confidence": {
                              "usageTags": "HIGH",
                              "budget": "LOW",
                              "resolution": "LOW",
                              "preferredVendors": "HIGH"
                            },
                            "parseNotes": "사용자가 RTX 5090을 명시했습니다."
                          },
                          "draftEdit": {
                            "operation": "NONE",
                            "category": null,
                            "priceDirection": "ANY",
                            "targetMaxPrice": null,
                            "targetQuantity": null,
                            "reason": null
                          }
                        }
                        """, LlmProvider.OPENAI, "gpt-5.5", "low", 1234, 100, 80, 180));

        AiChatEngineResponse response = engine.respondLlmRequired(new AiChatEngineRequest(
                "5090 글카가 들어간 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.assistantMessage()).contains("RTX 5090");
        assertThat(response.evidenceIds()).containsExactly("evidence-5090");
        assertThat(response.parsedContext().get("requiredGpuClasses")).asList().containsExactly("RTX_5090");
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "GPU".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.attributes()).containsEntry("gpuClass", "RTX_5090")));
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredBuildModifyHonorsMotherboardBrandToken() {
        stubBuildChatPlan("""
                {
                  "intent": "BUILD_MODIFY",
                  "assistantMessage": "MSI 메인보드로 맞춰드릴게요.",
                  "selectedCategory": "MOTHERBOARD",
                  "parsedContext": {
                    "budget": null,
                    "usageTags": [],
                    "resolution": null,
                    "preferredVendors": [],
                    "priority": null,
                    "performanceTier": "STANDARD",
                    "budgetPolicy": "UNSPECIFIED",
                    "mustHave": [],
                    "requiredGpuClasses": [],
                    "requiredPartKeywords": [],
                    "hardConstraintPolicy": "NONE",
                    "confidence": {}
                  },
                  "draftEdit": {
                    "operation": "REPLACE",
                    "category": "MOTHERBOARD",
                    "priceDirection": "ANY",
                    "targetMaxPrice": null,
                    "targetQuantity": null,
                    "reason": "MSI brand requested"
                  }
                }
                """);

        AiChatEngineResponse response = engine.respondLlmRequired(new AiChatEngineRequest(
                "메인보드 MSI 걸로 맞춰줘",
                "HOME",
                "MOTHERBOARD",
                null,
                null,
                Map.of("currentQuoteDraft", Map.of("items", List.of(Map.of("category", "MOTHERBOARD", "name", "Current Board")))),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.BUILD_MODIFY);
        assertThat(response.partRecommendations()).hasSize(2);
        assertThat(response.partRecommendations())
                .allSatisfy(part -> assertThat(part.name()).contains("MSI"));
        assertThat(response.parsedContext().get("requiredPartKeywords")).asList().contains("MSI");
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredBuildModifyDoesNotFallbackWhenHardModelIsUnavailable() {
        stubBuildChatPlan("""
                {
                  "intent": "BUILD_MODIFY",
                  "assistantMessage": "케이스를 리안리 999 모델로 맞춰드릴게요.",
                  "selectedCategory": "CASE",
                  "parsedContext": {
                    "budget": null,
                    "usageTags": [],
                    "resolution": null,
                    "preferredVendors": [],
                    "priority": null,
                    "performanceTier": "STANDARD",
                    "budgetPolicy": "UNSPECIFIED",
                    "mustHave": [],
                    "requiredGpuClasses": [],
                    "requiredPartKeywords": [],
                    "hardConstraintPolicy": "NONE",
                    "confidence": {}
                  },
                  "draftEdit": {
                    "operation": "REPLACE",
                    "category": "CASE",
                    "priceDirection": "ANY",
                    "targetMaxPrice": null,
                    "targetQuantity": null,
                    "reason": "Exact case model requested"
                  }
                }
                """);

        AiChatEngineResponse response = engine.respondLlmRequired(new AiChatEngineRequest(
                "케이스 리안리 999 모델꺼로 맞춰줘",
                "HOME",
                "CASE",
                null,
                null,
                Map.of("currentQuoteDraft", Map.of("items", List.of(Map.of("category", "CASE", "name", "Current Case")))),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.BUILD_MODIFY);
        assertThat(response.partRecommendations()).isEmpty();
        assertThat(response.assistantMessage()).contains("조건에 맞는 내부 자산 후보를 찾지 못했습니다");
        assertThat(response.parsedContext().get("requiredPartKeywords")).asList().contains("LIANLI", "999");
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredBuildChatCanReturnOpenRouteForNonFastNavigationIntent() {
        stubBuildChatPlan("""
                {
                  "intent": "ASK_FOLLOW_UP",
                  "assistantMessage": "내 견적함으로 이동하겠습니다.",
                  "selectedCategory": null,
                  "parsedContext": {
                    "budget": null,
                    "usageTags": [],
                    "resolution": null,
                    "preferredVendors": [],
                    "priority": null,
                    "performanceTier": "STANDARD",
                    "budgetPolicy": "UNSPECIFIED",
                    "mustHave": [],
                    "requiredGpuClasses": [],
                    "requiredPartKeywords": [],
                    "hardConstraintPolicy": "NONE",
                    "confidence": {}
                  },
                  "draftEdit": {
                    "operation": "NONE",
                    "category": null,
                    "priceDirection": "ANY",
                    "targetMaxPrice": null,
                    "targetQuantity": null,
                    "reason": null
                  },
                  "routeIntent": {
                    "shouldNavigate": true,
                    "routeType": "MY_QUOTES",
                    "category": null,
                    "partQuery": null,
                    "confidence": "HIGH",
                    "reason": "사용자가 견적함 위치를 물었습니다."
                  }
                }
                """);

        AiChatEngineResponse response = engine.respondLlmRequired(new AiChatEngineRequest(
                "견적함 어디야",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.actions())
                .filteredOn(action -> action.type() == AiChatActionType.OPEN_ROUTE)
                .singleElement()
                .satisfies(action -> assertThat(action.payload()).containsEntry("route", "/my/quotes"));
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredPartDetailRouteUsesSingleHighConfidenceActivePartMatch() {
        stubBuildChatPlan("""
                {
                  "intent": "ASK_FOLLOW_UP",
                  "assistantMessage": "상품 상세로 이동하겠습니다.",
                  "selectedCategory": "GPU",
                  "parsedContext": {
                    "budget": null,
                    "usageTags": [],
                    "resolution": null,
                    "preferredVendors": [],
                    "priority": null,
                    "performanceTier": "STANDARD",
                    "budgetPolicy": "UNSPECIFIED",
                    "mustHave": [],
                    "requiredGpuClasses": [],
                    "requiredPartKeywords": [],
                    "hardConstraintPolicy": "NONE",
                    "confidence": {}
                  },
                  "draftEdit": {
                    "operation": "NONE",
                    "category": null,
                    "priceDirection": "ANY",
                    "targetMaxPrice": null,
                    "targetQuantity": null,
                    "reason": null
                  },
                  "routeIntent": {
                    "shouldNavigate": true,
                    "routeType": "PART_DETAIL",
                    "category": "GPU",
                    "partQuery": "ASUS Astral RTX 5090",
                    "confidence": "HIGH",
                    "reason": "사용자가 특정 상품 상세를 요청했습니다."
                  }
                }
                """);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("GPU"),
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

        AiChatEngineResponse response = engine.respondLlmRequired(new AiChatEngineRequest(
                "ASUS Astral 5090 상세 보여줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.actions())
                .filteredOn(action -> action.type() == AiChatActionType.OPEN_ROUTE)
                .singleElement()
                .satisfies(action -> assertThat(action.payload()).containsEntry("route", "/parts/00000000-0000-4000-8000-000000005090"));
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredPartDetailRoutePrefersExactKoreanModelOverPrefixModel() {
        stubBuildChatPlan("""
                {
                  "intent": "ASK_FOLLOW_UP",
                  "assistantMessage": "해당 CPU 상세페이지로 이동하겠습니다.",
                  "selectedCategory": "CPU",
                  "parsedContext": {
                    "budget": null,
                    "usageTags": [],
                    "resolution": null,
                    "preferredVendors": [],
                    "priority": null,
                    "performanceTier": "STANDARD",
                    "budgetPolicy": "UNSPECIFIED",
                    "mustHave": [],
                    "requiredGpuClasses": [],
                    "requiredPartKeywords": [],
                    "hardConstraintPolicy": "NONE",
                    "confidence": {}
                  },
                  "draftEdit": {
                    "operation": "NONE",
                    "category": null,
                    "priceDirection": "ANY",
                    "targetMaxPrice": null,
                    "targetQuantity": null,
                    "reason": null
                  },
                  "routeIntent": {
                    "shouldNavigate": true,
                    "routeType": "PART_DETAIL",
                    "category": "CPU",
                    "partQuery": "AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩)",
                    "confidence": "HIGH",
                    "reason": "사용자가 특정 CPU 상품 상세를 요청했습니다."
                  }
                }
                """);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("CPU"),
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

        AiChatEngineResponse response = engine.respondLlmRequired(new AiChatEngineRequest(
                "AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩) 상세페이지로 이동해",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.actions())
                .filteredOn(action -> action.type() == AiChatActionType.OPEN_ROUTE)
                .singleElement()
                .satisfies(action -> assertThat(action.payload()).containsEntry("route", "/parts/a75d6544-2296-4c4c-a7cd-64596e66f6d7"));
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredPartDetailRouteAvoidsAmbiguousProductAutoNavigation() {
        stubBuildChatPlan("""
                {
                  "intent": "ASK_FOLLOW_UP",
                  "assistantMessage": "GPU 목록으로 이동하겠습니다.",
                  "selectedCategory": "GPU",
                  "parsedContext": {
                    "budget": null,
                    "usageTags": [],
                    "resolution": null,
                    "preferredVendors": [],
                    "priority": null,
                    "performanceTier": "STANDARD",
                    "budgetPolicy": "UNSPECIFIED",
                    "mustHave": [],
                    "requiredGpuClasses": [],
                    "requiredPartKeywords": [],
                    "hardConstraintPolicy": "NONE",
                    "confidence": {}
                  },
                  "draftEdit": {
                    "operation": "NONE",
                    "category": null,
                    "priceDirection": "ANY",
                    "targetMaxPrice": null,
                    "targetQuantity": null,
                    "reason": null
                  },
                  "routeIntent": {
                    "shouldNavigate": true,
                    "routeType": "PART_DETAIL",
                    "category": "GPU",
                    "partQuery": "5090",
                    "confidence": "HIGH",
                    "reason": "상품 후보가 여러 개일 수 있습니다."
                  }
                }
                """);
        when(jdbcTemplate.queryForList(
                anyString(),
                eq("GPU"),
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

        AiChatEngineResponse response = engine.respondLlmRequired(new AiChatEngineRequest(
                "5090 보여줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.actions())
                .filteredOn(action -> action.type() == AiChatActionType.OPEN_ROUTE)
                .singleElement()
                .satisfies(action -> assertThat(action.payload()).containsEntry("route", "/self-quote?category=GPU"));
        verifyNoJdbcWrites();
    }

    @Test
    void analyzeQuoteRequirementRecordsRagTraceAndReturnsStructuredContext() {
        when(openAiResponsesClient.isConfigured()).thenReturn(false);
        when(agentTraceService.createQueuedSession(any(), eq("SYSTEM"), eq(AgentPurpose.REQUIREMENT_PARSE), isNull()))
                .thenReturn("agent-session-1");
        when(agentRagRetrievalService.retrieveEvidenceSet(any(), eq(AgentRunProfiles.requirementParse())))
                .thenReturn(List.of(new AgentRagEvidenceDraft(
                        "requirement-example-gaming-resolution-refresh",
                        "QHD game examples",
                        "QHD gaming evidence",
                        BigDecimal.valueOf(0.94),
                        Map.of("purpose", "REQUIREMENT_PARSE")
                )));
        when(agentTraceService.recordRagEvidence(eq("agent-session-1"), any()))
                .thenReturn("evidence-1");

        QuoteRequirementAnalysisResult result = engine.analyzeQuoteRequirement(new QuoteRequirementAnalysisRequest(
                "00000000-0000-4000-8000-000000001001",
                "200만원 QHD 게임용 PC 추천해줘",
                Map.of(),
                Map.of("usageTags", List.of("GAMING"))
        ));

        assertThat(result.agentSessionId()).isEqualTo("agent-session-1");
        assertThat(result.evidenceIds()).containsExactly("evidence-1");
        assertThat(result.parsedContext())
                .containsEntry("parseMode", "AI_CHAT_ENGINE_DETERMINISTIC")
                .containsEntry("parser", "ai-chat-engine-quote-v1");
        assertThat(result.parsedContext().get("usageTags")).asList().contains("GAMING");
        verifyNoJdbcWrites();
    }

    @Test
    void analyzeQuoteRequirementExtractsExplicitRtx5090HardConstraintWithoutLlm() {
        when(openAiResponsesClient.isConfigured()).thenReturn(false);
        when(agentTraceService.createQueuedSession(any(), eq("SYSTEM"), eq(AgentPurpose.REQUIREMENT_PARSE), isNull()))
                .thenReturn("agent-session-1");
        when(agentRagRetrievalService.retrieveEvidenceSet(any(), eq(AgentRunProfiles.requirementParse())))
                .thenReturn(List.of(new AgentRagEvidenceDraft(
                        "requirement-rule-explicit-gpu-class-hard-constraint",
                        "Explicit RTX 5090 should be a hard constraint.",
                        "Explicit GPU class hard constraint parse rule.",
                        BigDecimal.valueOf(0.99),
                        Map.of("purpose", "REQUIREMENT_PARSE")
                )));
        when(agentTraceService.recordRagEvidence(eq("agent-session-1"), any()))
                .thenReturn("evidence-1");

        QuoteRequirementAnalysisResult result = engine.analyzeQuoteRequirement(new QuoteRequirementAnalysisRequest(
                "00000000-0000-4000-8000-000000001001",
                "5090 글카가 들어간 PC 추천해줘",
                Map.of(),
                Map.of()
        ));

        assertThat(result.parsedContext().get("requiredGpuClasses")).asList().containsExactly("RTX_5090");
        assertThat(result.parsedContext()).containsEntry("hardConstraintPolicy", "MUST_INCLUDE");
        assertThat(result.parsedContext()).containsEntry("budgetPolicy", "OPEN_BUDGET");
        assertThat(result.parsedContext()).containsEntry("performanceTier", "ENTHUSIAST");
        verifyNoJdbcWrites();
    }

    private void verifyNoJdbcWrites() {
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMaps(Object value) {
        return value instanceof List<?> list
                ? list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList()
                : List.of();
    }

    private void stubBuildChatPlan(String json) {
        when(openAiResponsesClient.isConfigured()).thenReturn(true);
        when(agentRagRetrievalService.retrieveEvidenceSet(any(), eq(AgentRunProfiles.requirementParse()), anyString(), anyInt()))
                .thenReturn(List.of());
        when(openAiResponsesClient.createStructuredJsonResult(
                anyString(),
                anyString(),
                eq("buildgraph_ai_build_chat_plan"),
                any(),
                eq("gpt-5.5"),
                eq("low"),
                eq(900)
        ))
                .thenReturn(new LlmResponseResult(json, LlmProvider.OPENAI, "gpt-5.5", "low", 1234, 100, 80, 180));
    }

    private static List<Map<String, Object>> partRows(String category) {
        if ("GPU".equals(category)) {
            return List.of(
                    partRow(category, "gpu-5090", "GeForce RTX 5090 32GB", 5_000_000, Map.of("toolReady", true, "gpuClass", "RTX_5090")),
                    partRow(category, "gpu-5080", "GeForce RTX 5080 16GB", 2_200_000, Map.of("toolReady", true, "gpuClass", "RTX_5080")),
                    partRow(category, "gpu-5070-ti", "GeForce RTX 5070 Ti 16GB", 1_200_000, Map.of("toolReady", true, "gpuClass", "RTX_5070_TI")),
                    partRow(category, "gpu-5060", "GeForce RTX 5060 8GB", 500_000, Map.of("toolReady", true, "gpuClass", "RTX_5060")),
                    partRow(category, "gpu-5070", "GeForce RTX 5070 12GB", 900_000, Map.of("toolReady", true, "gpuClass", "RTX_5070"))
            );
        }
        if ("CPU".equals(category)) {
            return List.of(
                    partRow(category, "cpu-high", "CPU High", 500_000, Map.of("toolReady", true, "cpuClass", "RYZEN_9", "coreCount", 16, "threadCount", 32, "socket", "AM5")),
                    partRow(category, "cpu-9700x", "AMD Ryzen 7 9700X", 377_500, Map.of("toolReady", true, "cpuClass", "RYZEN_7_9700X", "hardwareClass", "RYZEN_7_9700X", "coreCount", 8, "threadCount", 16, "socket", "AM5")),
                    partRow(category, "cpu-mid", "CPU Mid", 300_000, Map.of("toolReady", true, "cpuClass", "RYZEN_7", "coreCount", 8, "threadCount", 16, "socket", "AM5")),
                    partRow(category, "cpu-low", "CPU Low", 180_000, Map.of("toolReady", true, "cpuClass", "RYZEN_5", "coreCount", 6, "threadCount", 12, "socket", "AM5"))
            );
        }
        if ("RAM".equals(category)) {
            return List.of(
                    partRow(category, "ram-64-kit", "DDR5 64GB Kit", 900_000, Map.of("toolReady", true, "capacityGb", 64, "moduleCount", 2, "memoryType", "DDR5", "speedMhz", 6400)),
                    partRow(category, "ram-32-kit", "DDR5 32GB Kit", 700_000, Map.of("toolReady", true, "capacityGb", 32, "moduleCount", 2, "memoryType", "DDR5", "speedMhz", 6000)),
                    partRow(category, "ram-32-single", "Samsung DDR5 32GB UDIMM", 500_000, Map.of("toolReady", true, "capacityGb", 32, "moduleCount", 1, "memoryType", "DDR5", "speedMhz", 5600))
            );
        }
        if ("MOTHERBOARD".equals(category)) {
            return List.of(
                    partRow(category, "motherboard-msi-z890", "MSI MPG Z890I EDGE TI WIFI", 520_000, Map.of("toolReady", true, "socket", "LGA1851", "chipset", "Z890", "memoryType", "DDR5", "pcieGeneration", "5.0", "hasWifi", true, "formFactor", "ATX")),
                    partRow(category, "motherboard-asus-x870", "ASUS ROG STRIX X870-I GAMING WIFI", 410_000, Map.of("toolReady", true, "socket", "AM5", "chipset", "X870E", "memoryType", "DDR5", "pcieGeneration", "5.0", "hasWifi", true, "formFactor", "ATX")),
                    partRow(category, "motherboard-msi-b850", "MSI MPG B850I EDGE TI WIFI", 240_000, Map.of("toolReady", true, "socket", "AM5", "chipset", "B850", "memoryType", "DDR5", "pcieGeneration", "4.0", "hasWifi", true, "formFactor", "ATX")),
                    partRow(category, "motherboard-ddr4", "AM5 DDR4 Invalid Board", 190_000, Map.of("toolReady", true, "socket", "AM5", "chipset", "B850", "memoryType", "DDR4", "pcieGeneration", "4.0", "hasWifi", false, "formFactor", "ATX"))
            );
        }
        if ("CASE".equals(category)) {
            return List.of(
                    partRow(category, "case-lianli-216", "Lian Li LANCOOL 216", 148_000, Map.of("toolReady", true, "maxGpuLengthMm", 392, "maxCpuCoolerHeightMm", 180, "maxPsuLengthMm", 220, "airflowFocus", true)),
                    partRow(category, "case-lianli-a3", "Lian Li A3-mATX", 112_000, Map.of("toolReady", true, "maxGpuLengthMm", 415, "maxCpuCoolerHeightMm", 165, "maxPsuLengthMm", 220, "airflowFocus", false)),
                    partRow(category, "case-fractal", "Fractal Meshify 3 XL", 340_000, Map.of("toolReady", true, "maxGpuLengthMm", 512, "maxCpuCoolerHeightMm", 185, "maxPsuLengthMm", 250, "airflowFocus", true))
            );
        }
        if ("PSU".equals(category)) {
            return List.of(
                    partRow(category, "psu-high", "PSU High", 260_000, Map.of("toolReady", true, "capacityW", 1000, "efficiency", "PLATINUM", "atxSpec", "3.1", "modular", true)),
                    partRow(category, "psu-mid", "PSU Mid", 150_000, Map.of("toolReady", true, "capacityW", 850, "efficiency", "GOLD", "atxSpec", "3.1", "modular", true)),
                    partRow(category, "psu-low", "PSU Low", 80_000, Map.of("toolReady", true, "capacityW", 650, "efficiency", "BRONZE", "atxSpec", "2.4", "modular", false))
            );
        }
        return List.of(
                partRow(category, "part-a", category + " Alpha", 900_000),
                partRow(category, "part-b", category + " Bravo", 700_000),
                partRow(category, "part-c", category + " Charlie", 500_000)
        );
    }

    private static Map<String, Object> partRow(String category, String id, String name, int price) {
        return partRow(category, id, name, price, Map.of("toolReady", true));
    }

    private static Map<String, Object> partRow(String category, String id, String name, int price, Map<String, Object> attributes) {
        return Map.of(
                "id", id,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "attributes", attributesJson(attributes)
        );
    }

    private static String attributesJson(Map<String, Object> attributes) {
        return "{" + attributes.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + jsonValue(entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("") + "}";
    }

    private static String jsonValue(Object value) {
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return "\"" + String.valueOf(value) + "\"";
    }
}
