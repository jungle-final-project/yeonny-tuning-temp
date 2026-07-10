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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
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
        assertThat(BuildChatService.budgetIntent("300만원 이상으로 게임용 PC 맞춰줘").mode()).isEqualTo("MIN");
    }

    @Test
    void detectsPartQuestionCategories() {
        assertThat(BuildChatService.detectPartCategory("GPU 추천해줘")).isEqualTo("GPU");
        assertThat(BuildChatService.detectPartCategory("CPU는 뭐가 좋아?")).isEqualTo("CPU");
        assertThat(BuildChatService.detectPartCategory("쿨러 추천")).isEqualTo("COOLER");
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
        });
        verifyNoInteractions(aiChatEngine);
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
                        "attributes", "{}")));
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
    void buildChatCounterProposesUsageMinimumWhenBudgetCannotCoverGpuRequiredUsage() {
        // "70만원 AI 학습용" 류: 용도상 GPU가 필수인데 예산이 실계산 최소 구성가 미만이면 근거 숫자로 역제안한다.
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
                "message", "70만원으로 AI 학습용 PC 맞춰줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "draft-cpu", "category", "CPU", "name", "기존 CPU", "quantity", 1, "price", 200_000)
                ))
        ));

        String message = response.get("message").toString();
        assertThat(message)
                .contains("AI 학습용 PC는 그래픽카드가 필요해")
                .contains("160만원")
                .contains("70만원 예산으로는 어렵습니다");
        assertThat(response.get("warnings")).asList().contains("BUDGET_BELOW_USAGE_MINIMUM");
        assertThat(response.get("quickReplies")).asList().contains("160만원 AI 학습용 PC 추천해줘");
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
    void buildChatDoesNotReattachClarificationEchoOnFollowUpTurnAndAddsFeatureChips() {
        // 되묻기는 최대 1회 — 후속 턴이 또 빈손이어도 에코를 재부착하지 않고, 종단 칩 플로어가
        // 기능 안내 칩을 보강해 dead-end를 막는다.
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

        assertThat(response).doesNotContainKey("clarification");
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
        assertThat(response.get("quickReplies")).asList().contains("게이밍 200만원");
        @SuppressWarnings("unchecked")
        Map<String, Object> clarification = (Map<String, Object>) response.get("clarification");
        assertThat(clarification).containsEntry("originalMessage", "아무거나 사줘");
        assertThat(response).doesNotContainKeys("actions", "partRecommendation", "simulation");
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
        assertThat(response.get("quickReplies")).asList().contains("게이밍 200만원");
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
    void buildChatDoesNotReattachSimulationEchoOnFollowUpAndAddsFeatureChips() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        // 되묻기 후속 턴("MSI X870")인데 여전히 target 해상 실패 — 에코는 재부착하지 않고 종단 칩만 붙인다
        Map<String, Object> response = service.chat(Map.of(
                "message", "MSI X870",
                "clarificationContext", Map.of("originalMessage", "메인보드를 바꾸면 성능 어때?"),
                "currentQuoteDraft", draftWithItems(List.of(
                        draftItem("board-current", "MOTHERBOARD", "B850 Board", 1, Map.of("memoryType", "DDR5"))
                ))
        ));

        assertThat(response).doesNotContainKey("clarification");
        assertThat(response).doesNotContainKey("simulation");
        assertThat(response.get("quickReplies")).asList()
                .contains("200만원 게이밍 PC 추천해줘", "지금 견적 나머지 채워줘", "CPU를 9700X로 바꾸면?");
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
                        AiChatIntent.PART_RECOMMEND,
                        List.<AiChatAction>of(),
                        List.of(),
                        List.of(),
                        Map.of("partConstraint", Map.of("category", "CASE", "airflowFocused", true)),
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
        // 무예산 용도-only("게임용 컴퓨터 추천해줘")는 조합 3장 즉답을 폐기하고, LLM이 되묻되 서버가
        // 예산대 방향 칩을 붙여 클릭 한 번으로 다음 턴이 예산 추천으로 이어지게 한다.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        doAnswer(invocation -> List.<Map<String, Object>>of())
                .when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        when(aiChatEngine.respondLlmRequired(any(), any())).thenReturn(new AiChatEngineResponse(
                "예산대를 알려주시면 딱 맞는 조합을 추천해 드릴게요.",
                AiChatIntent.ASK_FOLLOW_UP,
                List.<AiChatAction>of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                null
        ));
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of("message", "게임용 컴퓨터 추천해줘"));

        // 무관한 예산 조합이 주입되지 않는다(딱딱한 3장 폴백 폐기 확인).
        assertThat(response.get("builds")).asList().isEmpty();
        // 예산대 방향 칩 4개가 붙는다("N만원대" 문구는 다음 턴 parseBudgetWon으로 예산이 잡힌다).
        assertThat(response.get("quickReplies")).asList()
                .containsExactly(
                        "100만원대로 추천해줘",
                        "200만원대로 추천해줘",
                        "300만원대로 추천해줘",
                        "예산 무관 고성능으로 추천해줘");
        // LLM 되묻기 경로를 실제로 탔다.
        verify(aiChatEngine).respondLlmRequired(any(), any());
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
    void buildChatGuidesMultiPartReductionWhenDraftExceedsTargetWithoutSinglePartNamed() {
        // 드래프트 있음 + 예산 목표 + 특정 단일 부품 미지목 + 총액 > 목표 → 감액 우선순위 안내 + 카테고리 교체 칩.
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        AiChatEngine aiChatEngine = mock(AiChatEngine.class);
        BuildChatCacheService cacheService = mock(BuildChatCacheService.class);
        when(cacheService.lookup(any(), any(), any())).thenReturn(Optional.empty());
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService, aiChatEngine, cacheService);

        Map<String, Object> response = service.chat(Map.of(
                "message", "현재 견적을 800만원 이하로 맞춰줘",
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "gpu-1", "category", "GPU", "name", "RTX 5090", "quantity", 1, "lineTotal", 4_000_000),
                        Map.of("partId", "cpu-1", "category", "CPU", "name", "Ryzen 9", "quantity", 1, "lineTotal", 3_000_000),
                        Map.of("partId", "ram-1", "category", "RAM", "name", "DDR5 64GB", "quantity", 1, "lineTotal", 2_000_000),
                        Map.of("partId", "ssd-1", "category", "STORAGE", "name", "NVMe 2TB", "quantity", 1, "lineTotal", 1_500_000)
                ))
        ));

        assertThat(response).containsEntry("answerType", "GENERAL");
        assertThat(response.get("builds")).asList().isEmpty();
        String message = response.get("message").toString();
        assertThat(message)
                .contains("1050만원")
                .contains("800만원")
                .contains("250만원")
                .contains("한 번에 한 부품씩")
                .contains("가장 비싼 GPU(400만원)");
        // 라인총액 내림차순 상위 3개 카테고리 교체 칩 — 재진입 시 detectPartCategory가 잡아 단일 modify로 닫힌다.
        assertThat(response.get("quickReplies")).asList()
                .containsExactly(
                        "GPU 더 저렴한 걸로 바꿔줘",
                        "CPU 더 저렴한 걸로 바꿔줘",
                        "RAM 더 저렴한 걸로 바꿔줘");
        // LLM을 거치지 않고 결정적으로 응답한다.
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
}
