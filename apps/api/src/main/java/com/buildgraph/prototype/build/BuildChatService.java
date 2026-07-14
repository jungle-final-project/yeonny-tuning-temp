package com.buildgraph.prototype.build;

import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.agent.PartReplacementRanker;
import com.buildgraph.prototype.agent.PartRouteResolver;
import com.buildgraph.prototype.part.PartCompatibleCandidateService;
import com.buildgraph.prototype.part.ToolBuildPart;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.part.ToolApplicabilityPolicy;
import com.buildgraph.prototype.recommendation.CandidateReranker;
import com.buildgraph.prototype.recommendation.NoopCandidateReranker;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BuildChatService {
    private static final Logger log = LoggerFactory.getLogger(BuildChatService.class);
    private static final Pattern BUDGET_MANWON = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:만원|만)");
    private static final Pattern BUDGET_BAEKMANWON = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*백\\s*만\\s*원?");
    private static final Pattern BUDGET_CHEONMANWON = Pattern.compile("(\\d+(?:\\.\\d+)?)?\\s*천\\s*(?:(\\d+(?:\\.\\d+)?)\\s*백)?\\s*만\\s*(원)?");
    // 선행 숫자 필수 — "억 소리 나네" 같은 관용구의 숫자 없는 "억"은 예산으로 보지 않는다
    private static final Pattern BUDGET_EOK = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*억\\s*(?:(\\d+(?:\\.\\d+)?)\\s*천)?\\s*만?\\s*원?");
    private static final Pattern BUDGET_WON = Pattern.compile("(\\d{6,})\\s*원?");
    // 숫자 없는 "만원" 단독(=1만원). 앞에 숫자가 붙은 "300만원"류는 위 패턴들이 선점한다.
    private static final Pattern BUDGET_MANWON_BARE = Pattern.compile("(?<![\\d.])만\\s*원");
    private static final Pattern EXPLICIT_GPU_MODEL = Pattern.compile("(?i)(?:rtx|geforce|지포스)?\\s*(40[6-9]0|50[6-9]0)(?:\\s*(ti|super))?");
    private static final Pattern EXPLICIT_CPU_MODEL = Pattern.compile("(?i)\\b\\d{4,5}x3d\\b|\\b\\d{4,5}x\\b|\\bi[3579]-?\\d{4,5}\\b");
    private static final Pattern CAPACITY_GB_PATTERN = Pattern.compile("(\\d+)\\s*(?:gb|기가|기가바이트)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WATT_PATTERN = Pattern.compile("(\\d{3,4})\\s*w", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT_PART_ADD_SUFFIX = Pattern.compile(
            "\\s*(?:견적(?:에|으로)?\\s*)?(?:담아\\s*줘|넣어\\s*줘|추가해\\s*줘|담아|넣어|추가해)\\s*[.!?]?\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RECOMMENDATION_COUNT_AFTER_LABEL = Pattern.compile(
            "(추천\\s*(?:조합|PC)(?:을|를)?\\s*)\\d+개"
    );
    private static final Pattern RECOMMENDATION_COUNT_BEFORE_LABEL = Pattern.compile(
            "\\d+개의\\s*(추천\\s*(?:조합|PC))"
    );
    // 팀 정책: 벤치마크 수치는 보장이 아니라 참고용이며, 제공 범위(내부 DB 등록 조합)를 함께 고지한다
    static final String SIMULATION_DISCLAIMER =
            "본 수치는 내부 벤치마크 DB 기준 참고용 추정치이며, 내부 DB에 등록된 부품·게임·해상도 조합에 한해 제공됩니다. "
                    + "실제 성능은 게임 버전, 그래픽 옵션, 드라이버, 해상도, 냉각·전원 환경에 따라 달라질 수 있습니다.";

    private static final List<Tier> TIERS = List.of(
            new Tier("budget", "가성비", "가성비형"),
            new Tier("balanced", "균형", "균형형"),
            new Tier("performance", "성능", "고성능형")
    );
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "CPU", "CPU",
            "MOTHERBOARD", "메인보드",
            "RAM", "RAM",
            "GPU", "GPU",
            "STORAGE", "SSD",
            "PSU", "파워",
            "CASE", "케이스",
            "COOLER", "쿨러"
    );
    // RAM/SSD는 견적에 여러 상품을 함께 담을 수 있다. 구체 상품 추천 칩을 누른 경우에는
    // 다시 자연어 변경 요청으로 해석하지 않고, 사용자가 고른 상품을 1개 직접 추가한다.
    private static final Set<String> DIRECT_MULTI_ITEM_QUICK_REPLY_CATEGORIES = Set.of("RAM", "STORAGE");
    private static final Set<String> SINGLE_ITEM_CATEGORIES = Set.of("CPU", "MOTHERBOARD", "GPU", "PSU", "CASE", "COOLER");
    private static final Set<String> MULTI_ITEM_CATEGORIES = Set.of("RAM", "STORAGE");
    private static final int PART_RECOMMENDATION_LIMIT = 3;
    private static final int PART_RECOMMENDATION_CANDIDATE_POOL_SIZE = 50;
    private static final int HOME_RECOMMENDED_BUILD_BUDGET_WON = 2_000_000;
    private static final Set<String> COMPLETE_BUILD_CATEGORIES = Set.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );
    private static final Set<String> HOME_REQUIRED_TOOLS = Set.of(
            "compatibility", "power", "size", "performance", "price"
    );
    private static final Set<String> SUPPORT_SYMPTOM_CATEGORIES = Set.of(
            "DISPLAY_FREEZE",
            "POWER_RESTART",
            "BOOT_FAILURE",
            "PERFORMANCE_STUTTER",
            "THERMAL_NOISE",
            "STORAGE",
            "NETWORK",
            "AUDIO",
            "GENERAL"
    );
    private static final String SCORE_EXPLANATION_PROFILE = "BUILD_CHAT_54_MINI_FAST";
    // dead-end 방지용 기능 안내 칩 — 우아한 거절과 종단 칩 플로어가 공유한다
    private static final List<String> FEATURE_GUIDE_QUICK_REPLIES =
            List.of("200만원 게이밍 PC 추천해줘", "지금 견적 나머지 채워줘", "CPU를 9700X로 바꾸면?");
    // 무예산 용도-only 되묻기 턴에 붙이는 예산대 방향 칩 — "N만원대" 문구는 다음 턴 parseBudgetWon으로
    // 예산이 잡혀 클릭 한 번으로 추천이 이어진다("200만원대"→200만원).
    private static final List<String> USAGE_ONLY_BUDGET_DIRECTION_CHIPS = List.of(
            "100만원대로 추천해줘",
            "200만원대로 추천해줘",
            "300만원대로 추천해줘",
            "예산 무관 고성능으로 추천해줘");
    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;
    private final AiChatEngine aiChatEngine;
    private final BuildChatCacheService buildChatCacheService;
    private final PartReplacementRanker partReplacementRanker;
    private final CandidateReranker candidateReranker;
    private final PartRouteResolver partRouteResolver;
    private final BuildChatIntentRouter intentRouter;
    private final BuildChatSemanticCacheService semanticCacheService;
    private final BuildChatFeasibilityService feasibilityService;
    private final BuildEvaluationService buildEvaluationService;
    private PartCompatibleCandidateService partCompatibleCandidateService;

    @Autowired
    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            PartReplacementRanker partReplacementRanker,
            CandidateReranker candidateReranker,
            PartRouteResolver partRouteResolver,
            BuildChatIntentRouter intentRouter,
            BuildChatSemanticCacheService semanticCacheService,
            BuildEvaluationService buildEvaluationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCheckService = toolCheckService;
        this.aiChatEngine = aiChatEngine;
        this.buildChatCacheService = buildChatCacheService;
        this.partReplacementRanker = partReplacementRanker;
        this.candidateReranker = candidateReranker;
        this.partRouteResolver = partRouteResolver;
        this.intentRouter = intentRouter;
        this.semanticCacheService = semanticCacheService;
        this.feasibilityService = new BuildChatFeasibilityService(jdbcTemplate);
        this.buildEvaluationService = buildEvaluationService;
    }

    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            PartReplacementRanker partReplacementRanker,
            CandidateReranker candidateReranker,
            PartRouteResolver partRouteResolver,
            BuildChatIntentRouter intentRouter,
            BuildChatSemanticCacheService semanticCacheService
    ) {
        this(
                jdbcTemplate,
                toolCheckService,
                aiChatEngine,
                buildChatCacheService,
                partReplacementRanker,
                candidateReranker,
                partRouteResolver,
                intentRouter,
                semanticCacheService,
                defaultBuildEvaluationService(jdbcTemplate, toolCheckService)
        );
    }

    public BuildChatService(JdbcTemplate jdbcTemplate, ToolCheckService toolCheckService, AiChatEngine aiChatEngine, BuildChatCacheService buildChatCacheService) {
        this(jdbcTemplate, toolCheckService, aiChatEngine, buildChatCacheService, null, new NoopCandidateReranker(), new PartRouteResolver(jdbcTemplate), new BuildChatIntentRouter(), BuildChatSemanticCacheService.disabled());
    }

    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            PartReplacementRanker partReplacementRanker
    ) {
        this(jdbcTemplate, toolCheckService, aiChatEngine, buildChatCacheService, partReplacementRanker, new NoopCandidateReranker(), new PartRouteResolver(jdbcTemplate), new BuildChatIntentRouter(), BuildChatSemanticCacheService.disabled());
    }

    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            CandidateReranker candidateReranker
    ) {
        this(jdbcTemplate, toolCheckService, aiChatEngine, buildChatCacheService, null, candidateReranker, new PartRouteResolver(jdbcTemplate), new BuildChatIntentRouter(), BuildChatSemanticCacheService.disabled());
    }

    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            PartReplacementRanker partReplacementRanker,
            CandidateReranker candidateReranker
    ) {
        this(jdbcTemplate, toolCheckService, aiChatEngine, buildChatCacheService, partReplacementRanker, candidateReranker, new PartRouteResolver(jdbcTemplate), new BuildChatIntentRouter(), BuildChatSemanticCacheService.disabled());
    }

    private BuildChatTierSnapshotStore tierSnapshotStore;

    @Value("${ai.build-chat.tier-snapshot.tolerance-pct:15}")
    private double tierSnapshotTolerancePct = 15;

    @Autowired(required = false)
    public void setTierSnapshotStore(BuildChatTierSnapshotStore tierSnapshotStore) {
        this.tierSnapshotStore = tierSnapshotStore;
    }

    @Autowired(required = false)
    public void setPartCompatibleCandidateService(PartCompatibleCandidateService partCompatibleCandidateService) {
        this.partCompatibleCandidateService = partCompatibleCandidateService;
    }

    public Map<String, Object> chat(Map<String, Object> request) {
        return chat(request, (String) null);
    }

    public Map<String, Object> chat(Map<String, Object> request, String requestedAiProfile) {
        return chat(request, requestedAiProfile, null);
    }

    public Map<String, Object> chat(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        return chat(request, null, user);
    }

    public Map<String, Object> chat(Map<String, Object> request, String requestedAiProfile, CurrentUserService.CurrentUser user) {
        long startedNanos = System.nanoTime();
        Map<String, Object> rawBody = request == null ? Map.of() : request;
        String rawMessage = requireText(rawBody.get("message"), "message는 필수입니다.");
        // 직전 되묻기(clarification)에 대한 후속 답변이면 원 요청과 합성해 한 문장처럼 라우팅한다.
        // 서버는 상태를 저장하지 않고 프론트가 originalMessage를 에코하는 무상태 왕복이다.
        // 해결되지 않은 되묻기는 한 번만 허용하되, 성공한 후보/미리보기 턴은 합성 문맥을 다시 내려
        // 3~5턴의 비교·재추천 요청에서도 대상 카테고리가 유지되게 한다.
        String clarificationOriginal = text(objectMap(rawBody.get("clarificationContext")).get("originalMessage"));
        boolean clarificationFollowUp = clarificationOriginal != null && !clarificationOriginal.isBlank();
        Map<String, Object> body;
        String message;
        if (clarificationFollowUp && isSelfContainedClarificationReply(rawMessage)) {
            message = rawMessage;
            Map<String, Object> standalone = new LinkedHashMap<>(rawBody);
            standalone.remove("clarificationContext");
            body = standalone;
        } else if (clarificationFollowUp) {
            message = clarificationOriginal + " " + rawMessage;
            Map<String, Object> merged = new LinkedHashMap<>(rawBody);
            merged.put("message", message);
            merged.remove("clarificationContext");
            body = merged;
        } else {
            body = rawBody;
            message = rawMessage;
        }
        Long userId = user == null ? null : user.internalId();
        BudgetIntent rawBudgetIntent = budgetIntent(message);
        BuildChatIntentDecision intentDecision = intentRouter.decide(body, message);
        log.debug(
                "Build Chat intent decision: intent={}, confidence={}, sideEffectRisk={}, preferredPath={}, cachePolicy={}, ambiguityReasons={}",
                intentDecision.intent(),
                intentDecision.confidence(),
                intentDecision.sideEffectRisk(),
                intentDecision.preferredPath(),
                intentDecision.cachePolicy(),
                intentDecision.ambiguityReasons()
        );
        log.debug(
                "Build Chat request received: userId={}, requestedAiProfile={}, cacheLookup=true, cacheService={}",
                userId,
                requestedAiProfile,
                buildChatCacheService.getClass().getName()
        );
        if (intentDecision.intent() == BuildChatIntent.SUPPORT_GUIDANCE) {
            Map<String, Object> response = shoppingSupportGuidanceResponse(message, Map.of());
            attachFollowUpContext(response, message);
            logBuildChatPath("FAST_SUPPORT_GUIDANCE", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.LOCATE_BOARD_PART
                && "HIGH".equals(intentDecision.confidence())
                && !intentDecision.targetCategories().isEmpty()) {
            Map<String, Object> response = boardFocusResponse(intentDecision.targetCategories());
            logBuildChatPath("FAST_BOARD_FOCUS", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.EXPLAIN_BUILD_SCORE) {
            Map<String, Object> response = buildScoreExplanationResponse(body, message, user, intentDecision);
            logBuildChatPath("LIVE_BUILD_ASSESSMENT", startedNanos, userId, SCORE_EXPLANATION_PROFILE, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.SIMULATE_REPLACEMENT) {
            Optional<Map<String, Object>> simulationCard = performanceSimulationResponse(body, message);
            // 카드가 못 나왔고(교체 대상 미해상) 속성 요청 가능성이 있으면 dead-end로 끝내지 않고 LLM 경로로
            // 흘려보낸다(UNSUPPORTED→LLM 강등과 동일 철학) — LLM이 "수랭/PCIe 5.0/통풍" 같은 속성을
            // partConstraint로 구조화하면 서버가 속성 조회로 1:1 비교 카드 또는 후보를 제시한다.
            // LLM도 못 잡으면 아래 공통 경로의 안전망(속성 카드 실패 시 역제안·에코·칩)이 받는다.
            boolean attributeFallThrough = simulationCard.isEmpty()
                    && simulationAttributeFallThroughEligible(body, message);
            if (!attributeFallThrough) {
                Map<String, Object> response = simulationCard
                        .orElseGet(() -> simulationClarificationResponse(body, message));
                // 시뮬 카드가 못 나온 dead-end면 원문을 에코해 다음 짧은 답("MSI X870")이 원 요청과 합성되게
                // 한다(무상태 후속). 되묻기는 최대 1회 — 이미 후속 턴이면 재부착하지 않는다.
                if (response.get("simulation") == null && response.get("clarification") == null) {
                    response.put("clarification", MockData.map("missingSlots", List.of(), "originalMessage", message));
                }
                // 에코도 카드도 없는 순수 dead-end에는 다음 행동 칩을 보강한다(형태 판정 — 에코가 붙었으면 개입 안 함).
                ensureNextAction(response, intentDecision.intent(), rawBudgetIntent);
                logBuildChatPath("FAST_SIMULATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
                return response;
            }
        }
        Optional<Map<String, Object>> exactPartPreview = exactSingletonPartPreviewResponse(body, message, user);
        if (exactPartPreview.isPresent()) {
            Map<String, Object> response = exactPartPreview.get();
            attachFollowUpContext(response, message);
            logBuildChatPath("FAST_EXACT_PART_PREVIEW", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        Optional<Map<String, Object>> underspecifiedPartMutation = underspecifiedPartMutationClarificationResponse(body, message);
        if (underspecifiedPartMutation.isPresent()) {
            Map<String, Object> response = underspecifiedPartMutation.get();
            logBuildChatPath("FAST_PART_SELECTION_CLARIFICATION", startedNanos, userId, requestedAiProfile, false,
                    BuildChatGuardStats.empty());
            return response;
        }
        Optional<Map<String, Object>> directionalDraftEdit = directionalDraftEditFastResponse(body, message);
        if (directionalDraftEdit.isPresent()) {
            Map<String, Object> response = directionalDraftEdit.get();
            attachFollowUpContext(response, message);
            logBuildChatPath("FAST_DIRECTIONAL_DRAFT_EDIT", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        Optional<Map<String, Object>> fastCaseImprovement = fastCaseScoreImprovementResponse(body, message);
        if (fastCaseImprovement.isPresent()) {
            Map<String, Object> response = fastCaseImprovement.get();
            attachFollowUpContext(response, message);
            logBuildChatPath("FAST_CASE_SCORE_IMPROVEMENT", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.ASK_CLARIFICATION) {
            Map<String, Object> response = clarificationResponse(message, rawMessage, intentDecision.ambiguityReasons(), clarificationFollowUp);
            logBuildChatPath("FAST_CLARIFICATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        // 라우터가 UNSUPPORTED로 본 문장도 즉답 거절하지 않는다 — 키워드 오인("램을 바꿔줘" 류)이
        // 잦아, LLM 제약 파서까지 흘려보내 실제 의도(부품 제약·드래프트 변경·설명)를 살린다.
        // LLM이 불가하거나 실패할 때만 우아한 거절(기능 안내 + 바로 눌러볼 칩)로 마무리한다.
        boolean recommendFlow = intentDecision.intent() == BuildChatIntent.BUILD_RECOMMEND;
        // 명시 부품 하드 조건은 모델명·예산이 정확히 일치해야만 재사용할 수 있다. exact cache는 계속
        // 사용하되, miss 가능성이 높은 semantic lookup을 위해 임베딩을 먼저 호출하지 않는다. 복잡한
        // 요구사항 해석은 아래 LLM 경로에 그대로 맡긴다.
        boolean semanticCacheAllowed = !rawBudgetIntent.explicitHardConstraint();
        long stageStartNanos = System.nanoTime();
        var cachedResponse = buildChatCacheService.lookup(body, requestedAiProfile, userId);
        long redisMs = elapsedMs(stageStartNanos);
        if (cachedResponse.isPresent()) {
            Map<String, Object> response = cachedResponse.get();
            logBuildChatPath("CACHE_HIT", startedNanos, userId, requestedAiProfile, true, BuildChatGuardStats.empty(),
                    "redisMs=" + redisMs);
            return response;
        }
        Optional<Map<String, Object>> fastPartRecommendation = deterministicPartRecommendationResponse(body, message, user);
        if (fastPartRecommendation.isPresent()) {
            Map<String, Object> response = fastPartRecommendation.get();
            attachFollowUpContext(response, message);
            buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
            logBuildChatPath("FAST_PART_RECOMMEND", startedNanos, userId, requestedAiProfile, false,
                    BuildChatGuardStats.empty(), "redisMs=" + redisMs);
            return response;
        }
        if (recommendFlow
                && tierSnapshotStore != null
                && rawBudgetIntent.hasBudget()
                && !rawBudgetIntent.explicitHardConstraint()
                && objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()) {
            stageStartNanos = System.nanoTime();
            // 스냅샷은 티어 예산 기준 TARGET 밴드로 미리 계산돼 있다. 요청 예산이 티어와 다를 수 있으므로
            // (허용 오차 15%), 실제 요청 예산 기준 모드 규칙(TARGET ±12.5%, MAX 이하)을 서빙 시점에
            // 다시 검증하고, 하나라도 어긋나면 빠른 경로를 포기하고 일반 경로로 흘린다.
            Optional<BuildChatTierSnapshotStore.TierSnapshot> tierSnapshot =
                    tierSnapshotStore.bestFor(rawBudgetIntent.budget(), rawBudgetIntent.mode(), tierSnapshotTolerancePct)
                            .filter(snapshot -> snapshotSatisfiesBudgetMode(snapshot.builds(), rawBudgetIntent.budget(), rawBudgetIntent.mode()));
            long tierMs = elapsedMs(stageStartNanos);
            if (tierSnapshot.isPresent()) {
                BuildChatTierSnapshotStore.TierSnapshot snapshot = tierSnapshot.get();
                Map<String, Object> response = fastResponse(
                        "BUDGET",
                        "내부 자산과 자동 검증 기준으로 미리 계산한 추천 조합을 바로 가져왔습니다.",
                        snapshot.builds(),
                        snapshot.warnings()
                );
                buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
                logBuildChatPath("FAST_TIER_SNAPSHOT", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty(),
                        "redisMs=" + redisMs + " tierMs=" + tierMs + " tierBudgetWon=" + snapshot.tierBudgetWon());
                return response;
            }
        }
        stageStartNanos = System.nanoTime();
        Optional<Map<String, Object>> completionResponse = recommendFlow
                ? draftCompletionFastResponse(body, message, rawBudgetIntent)
                : Optional.empty();
        long completionMs = elapsedMs(stageStartNanos);
        if (completionResponse.isPresent()) {
            Map<String, Object> response = completionResponse.get();
            buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
            logBuildChatPath("FAST_DRAFT_COMPLETION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback(),
                    "redisMs=" + redisMs + " completionMs=" + completionMs);
            return response;
        }
        stageStartNanos = System.nanoTime();
        Optional<Map<String, Object>> deterministicResponse = recommendFlow
                ? deterministicFastResponse(body, message, rawBudgetIntent)
                : Optional.empty();
        long deterministicMs = elapsedMs(stageStartNanos);
        if (deterministicResponse.isPresent()) {
            Map<String, Object> response = deterministicResponse.get();
            buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
            if (semanticCacheAllowed) {
                semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
            }
            logBuildChatPath("FAST_DETERMINISTIC", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback(),
                    "redisMs=" + redisMs + " deterministicMs=" + deterministicMs);
            return response;
        }
        stageStartNanos = System.nanoTime();
        var semanticCachedResponse = semanticCacheAllowed
                ? semanticCacheService.lookup(body, requestedAiProfile, intentDecision)
                : Optional.<Map<String, Object>>empty();
        long semanticMs = elapsedMs(stageStartNanos);
        if (semanticCachedResponse.isPresent()) {
            Map<String, Object> response = semanticCachedResponse.get();
            logBuildChatPath("SEMANTIC_CACHE_HIT", startedNanos, userId, requestedAiProfile, true, BuildChatGuardStats.empty(),
                    "redisMs=" + redisMs + " deterministicMs=" + deterministicMs + " semanticMs=" + semanticMs);
            return response;
        }
        // 다중부품 감액 요청(드래프트+예산목표+단일부품 미지목+총액>목표)은 LLM을 거치지 않고 감액
        // 우선순위 안내와 카테고리 교체 칩으로 결정적으로 응답한다(draftEdit 단일 카테고리 계약은 유지).
        Optional<Map<String, Object>> multiPartReduction = multiPartReductionResponse(body, message, rawBudgetIntent);
        if (multiPartReduction.isPresent()) {
            Map<String, Object> response = multiPartReduction.get();
            buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
            if (semanticCacheAllowed) {
                semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
            }
            logBuildChatPath("FAST_MULTI_PART_REDUCTION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback(),
                    "redisMs=" + redisMs + " deterministicMs=" + deterministicMs + " semanticMs=" + semanticMs);
            return response;
        }
        stageStartNanos = System.nanoTime();
        // 사실 우선: 서버가 방금 계산한 사실(파싱 예산·최소 구성가·부품 조건 최저가·드래프트 요약)을
        // LLM 프롬프트에 주입한다 — LLM이 이미 아는 사실을 되묻거나("예산이 빠졌어요")
        // 사실과 다른 캔드 답("찾지 못했다")을 쓰는 것을 원천 차단한다.
        Map<String, Object> engineBody = new LinkedHashMap<>(body);
        engineBody.put("serverFacts", intentDecision.intent() == BuildChatIntent.LOCATE_BOARD_PART
                ? Map.of()
                : buildServerFacts(message, rawBudgetIntent, body, user));
        if ((recommendFlow || isExplicitHardFullBuildRecommendation(message, body, rawBudgetIntent))
                && rawBudgetIntent.explicitHardConstraint()
                && objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()) {
            // 라우터가 전체 견적 추천으로 확정했고 편집할 draft도 없는 경우다. LLM 판단은 유지하되
            // 이동·AS·mutation 필드를 포함한 범용 schema 대신 견적 요구사항 전용 schema를 사용한다.
            engineBody.put("_buildRecommendationParseOnly", true);
        }
        AiChatEngineResponse engineResponse;
        try {
            engineResponse = aiChatEngine.respondLlmRequired(new AiChatEngineRequest(
                    message,
                    chatSurface(body),
                    firstText(
                            text(body.get("selectedCategory")),
                            firstText(detectRecommendationTargetCategory(message), detectPartCategory(message))
                    ),
                    text(body.get("buildId")),
                    text(body.get("draftId")),
                    engineBody,
                    userId
            ), requestedAiProfile);
        } catch (RuntimeException error) {
            if (recommendFlow) {
                throw error;
            }
            // 라우터가 UNSUPPORTED로 봤던 문장인데 LLM까지 불가한 경우 — dead-end 문구 대신
            // 지금 눌러볼 수 있는 기능 칩과 함께 우아하게 거절한다.
            log.warn("Build Chat LLM unavailable for demoted UNSUPPORTED intent, returning graceful refusal", error);
            Map<String, Object> refusal = gracefulRefusalResponse(intentDecision.targetCategory());
            attachFollowUpContext(refusal, message);
            logBuildChatPath("FAST_UNSUPPORTED_FALLBACK", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return refusal;
        }
        long engineMs = elapsedMs(stageStartNanos);
        if (engineResponse.intent() == AiChatIntent.SUPPORT_GUIDANCE) {
            Map<String, Object> response = shoppingSupportGuidanceResponse(
                    message,
                    objectMap(objectMap(engineResponse.parsedContext()).get("supportIntent"))
            );
            attachFollowUpContext(response, message);
            logBuildChatPath("LLM_SUPPORT_GUIDANCE", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty(),
                    "redisMs=" + redisMs + " engineMs=" + engineMs);
            return response;
        }
        BuildChatGuardStats guardStats = new BuildChatGuardStats();
        Map<String, Object> response = responseMap(engineResponse, rawBudgetIntent, guardStats);
        if (intentDecision.intent() == BuildChatIntent.LOCATE_BOARD_PART) {
            List<String> focusCategories = llmBoardFocusCategories(engineResponse.parsedContext(), body);
            if (!focusCategories.isEmpty()) {
                Map<String, Object> focusResponse = boardFocusResponse(focusCategories);
                buildChatCacheService.storeAsync(body, requestedAiProfile, userId, focusResponse);
                logBuildChatPath("LLM_BOARD_FOCUS", startedNanos, userId, requestedAiProfile, false, guardStats,
                        "redisMs=" + redisMs + " engineMs=" + engineMs);
                return focusResponse;
            }
        }
        boolean readOnlySimulationFlow = intentDecision.intent() == BuildChatIntent.SIMULATE_REPLACEMENT;
        boolean readOnlyBoardFocusFlow = intentDecision.intent() == BuildChatIntent.LOCATE_BOARD_PART;
        if (readOnlySimulationFlow || readOnlyBoardFocusFlow) {
            response.put("builds", List.of());
        }
        // 라우터가 전체 견적(BUILD_RECOMMEND)으로 판정했는데 LLM 엔진이 내부적으로 부품 추천으로
        // 분류해 answerType=PART가 되는 경우를 교정한다 (조합 카드가 있으면 BUDGET으로 표기).
        if (intentDecision.intent() == BuildChatIntent.BUILD_RECOMMEND
                && !objectMaps(response.get("builds")).isEmpty()
                && "PART".equals(text(response.get("answerType")))) {
            response.put("answerType", "BUDGET");
        }
        // 데모 안전망: 견적 요청인데 LLM이 빈 조합을 반환하면(용도-only 등) 내부 자산 기준
        // 폴백 조합으로 보강해 빈 화면을 막는다. 라우터가 이미 BUILD_RECOMMEND로 판정한 경우만.
        // 단 드래프트가 있는 되묻기/수정 턴(ASK_FOLLOW_UP·BUILD_MODIFY)은 무관한 예산 조합을 끼워넣지
        // 않는다 — 되묻기 응답에 카드가 생기면 원문 에코 조건이 깨진다. 드래프트 없는 용도-only 턴은 유지.
        boolean draftModifyTurn = intentDecision.intent() == BuildChatIntent.BUILD_RECOMMEND
                && objectMaps(response.get("builds")).isEmpty()
                && !objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()
                && (engineResponse.intent() == AiChatIntent.ASK_FOLLOW_UP
                        || engineResponse.intent() == AiChatIntent.BUILD_MODIFY);
        // 무예산 용도-only 되묻기 턴(드래프트도 예산도 없이 용도만 있어 LLM이 되물은 경우)에는
        // 무관한 예산 조합을 주입하지 않고, 대신 예산대 방향 칩만 붙인다(아래 에코 블록 근처).
        boolean usageOnlyFollowUp = engineResponse.intent() == AiChatIntent.ASK_FOLLOW_UP
                && !rawBudgetIntent.hasBudget()
                && objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty();
        if (intentDecision.intent() == BuildChatIntent.BUILD_RECOMMEND
                && objectMaps(response.get("builds")).isEmpty()
                && !draftModifyTurn
                && !usageOnlyFollowUp
                && !hasEffectiveHardConstraint(engineResponse.parsedContext(), rawBudgetIntent)) {
            List<String> fallbackWarnings = new ArrayList<>();
            List<Map<String, Object>> fallbackBuilds = rawBudgetIntent.hasBudget()
                    ? nearBudgetLadderBuilds(rawBudgetIntent.budget(), rawBudgetIntent.mode(), List.of(), fallbackWarnings, new BuildChatGuardStats())
                    : openBudgetFallbackBuilds(fallbackWarnings);
            if (!fallbackBuilds.isEmpty()) {
                response.put("answerType", "BUDGET");
                response.put("builds", fallbackBuilds);
                List<String> mergedWarnings = new ArrayList<>(stringList(response.get("warnings")));
                mergedWarnings.addAll(fallbackWarnings);
                response.put("warnings", distinct(mergedWarnings));
            }
        }
        // 범용 역제안 후처리 — LLM이 구조화한 제약을 부품 DB와 대조해, 불가능한 요청이면
        // dead-end 대신 실데이터 숫자(최저가·부족액·최소 구성가)와 다음 행동 칩을 제시한다.
        // 미리보기(변경)를 먼저 시도하고, 미리보기가 못 만들어진 변경 요청은 부품 제약 역제안이 이어받는다.
        // 단, "여유 있는 케이스 추천"은 LLM이 BUILD_MODIFY로 흔들려도 실제 개선 검증이 먼저다.
        // 이 순서를 지키지 않으면 검증 전 추천 1개가 변경 미리보기로 확정된다.
        boolean caseImprovementHandled = !readOnlyBoardFocusFlow
                && applyCaseScoreImprovementProposal(response, engineResponse, message, body);
        if (!readOnlySimulationFlow && !readOnlyBoardFocusFlow && !caseImprovementHandled) {
            applyDraftEditPreview(response, engineResponse, body);
        }
        if (!readOnlyBoardFocusFlow) {
            // 속성 요청(수랭/PCIe 세대/통풍) + 드래프트에 같은 카테고리 존재 → 1:1 스펙비교 카드로 답한다.
            // 카드가 못 나오면(비교 대상 없음·후보 없음) 아래 역제안이 후보 나열로 이어받는다.
            if (!caseImprovementHandled) {
                applyAttributeSimulationCard(response, engineResponse, message, body);
                applyPartConstraintCounterProposal(response, engineResponse, message, body, user);
            }
            applyUsageMinimumCounterProposal(response, engineResponse, rawBudgetIntent);
        }
        if (recommendFlow
                && objectMaps(response.get("builds")).isEmpty()
                && response.get("simulation") == null
                && hasEffectiveHardConstraint(engineResponse.parsedContext(), rawBudgetIntent)) {
            List<String> unresolvedWarnings = new ArrayList<>(stringList(response.get("warnings")));
            unresolvedWarnings.add("PART_CONSTRAINT_NOT_FOUND");
            response.put("answerType", "BUDGET");
            response.put("message", "요청하신 \"" + message.trim()
                    + "\" 조건을 모두 만족하면서 자동 검증을 통과한 내부 자산 조합을 찾지 못했습니다. "
                    + "조건을 유지한 채 예산을 조정하거나 일부 조건을 완화해 다시 확인해 주세요.");
            response.put("warnings", distinct(unresolvedWarnings));
            response.put("quickReplies", List.of(
                    "조건을 유지하고 예산을 높여 추천해줘",
                    "일부 조건을 완화해서 추천해줘"));
            response.remove("clarification");
        }
        // LLM이 스스로 되물은 턴("용량이나 DDR 조건 알려주세요")은 원문을 에코해 다음 짧은 답("ddr5")이
        // 원 요청과 합성되게 한다 — 이게 없으면 후속 답이 맥락을 잃고 대화가 끊긴다.
        // GENERAL 계열(PRICE_ALERT_HELP 등)은 의도적으로 제외 — 에코 오염 진입점을 넓히지 않는다.
        boolean askedFollowUp = engineResponse.intent() == AiChatIntent.ASK_FOLLOW_UP
                || ((engineResponse.intent() == AiChatIntent.PART_RECOMMEND
                                || engineResponse.intent() == AiChatIntent.BUILD_MODIFY
                                || engineResponse.intent() == AiChatIntent.EXPLAIN)
                        && objectMaps(response.get("builds")).isEmpty()
                        && stringList(response.get("quickReplies")).isEmpty());
        // 되묻기는 최대 1회 — 이미 되묻기 후속 턴이면 에코를 재부착하지 않는다.
        // 속성 1:1 카드로 답을 낸 턴은 완결 응답이므로 에코를 붙이지 않는다(카드가 주인공).
        if (askedFollowUp && !clarificationFollowUp
                && response.get("clarification") == null && response.get("simulation") == null) {
            response.put("clarification", MockData.map(
                    "missingSlots", List.of(),
                    "originalMessage", message
            ));
        }
        // 무예산 용도-only 되묻기에는 예산대 방향 칩을 붙여 클릭 한 번으로 다음 턴이 예산으로 진행되게 한다.
        if (usageOnlyFollowUp && stringList(response.get("quickReplies")).isEmpty()) {
            response.put("quickReplies", USAGE_ONLY_BUDGET_DIRECTION_CHIPS);
        }
        alignBuildCountMessage(response);
        ensureNextAction(response, intentDecision.intent(), rawBudgetIntent);
        if (shouldPreserveSuccessfulTurnContext(response)) {
            attachFollowUpContext(response, message);
        }
        candidateReranker.recordShadowScores(body, response, userId, requestedAiProfile);
        log.debug("Build Chat response generated: userId={}, requestedAiProfile={}, cacheStore=true", userId, requestedAiProfile);
        buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
        if (semanticCacheAllowed) {
            semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
        }
        logBuildChatPath("LLM_FULL", startedNanos, userId, requestedAiProfile, false, guardStats,
                "redisMs=" + redisMs + " deterministicMs=" + deterministicMs + " semanticMs=" + semanticMs + " engineMs=" + engineMs);
        return response;
    }

    private Map<String, Object> buildScoreExplanationResponse(
            Map<String, Object> body,
            String message,
            CurrentUserService.CurrentUser user,
            BuildChatIntentDecision decision
    ) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        AssessmentFocus requestedFocus = assessmentFocus(body, decision);
        BuildEvaluationService.BuildEvaluation evaluation = buildEvaluationService.evaluateCurrentDraft(
                user.internalId(),
                null,
                requestedFocus.category(),
                requestedFocus.tool()
        );
        AssessmentFocus verifiedFocus = verifiedAssessmentFocus(requestedFocus, evaluation);
        if (!Objects.equals(verifiedFocus, requestedFocus)) {
            evaluation = buildEvaluationService.evaluate(
                    evaluation.parts(),
                    evaluation.budgetWon(),
                    verifiedFocus.category(),
                    verifiedFocus.tool()
            );
        }
        Map<String, Object> assessment = evaluation.buildAssessment();
        String fallbackMessage = deterministicAssessmentMessage(assessment);
        String assistantMessage = fallbackMessage;
        List<String> warnings = new ArrayList<>();
        List<String> evidenceIds = List.of();
        String agentSessionId = null;
        try {
            Map<String, Object> engineContext = new LinkedHashMap<>();
            engineContext.put("serverFacts", MockData.map(
                    "buildAssessment", assessment,
                    "responsePolicy", "Use only these facts. Write one short Korean sentence. Do not invent numbers, parts, or actions."
            ));
            engineContext.put("assessmentContext", body.get("assessmentContext"));
            AiChatEngineResponse engineResponse = aiChatEngine.explainBuildAssessment(new AiChatEngineRequest(
                    message,
                    chatSurface(body),
                    verifiedFocus.category(),
                    text(body.get("buildId")),
                    text(body.get("draftId")),
                    engineContext,
                    user.internalId()
            ), SCORE_EXPLANATION_PROFILE);
            assistantMessage = safeAssessmentMessage(engineResponse.assistantMessage(), assessment, fallbackMessage);
            evidenceIds = engineResponse.evidenceIds() == null ? List.of() : engineResponse.evidenceIds();
            agentSessionId = engineResponse.agentSessionId();
        } catch (RuntimeException error) {
            log.warn("Build score explanation LLM unavailable; returning deterministic assessment", error);
            warnings.add("SCORE_EXPLANATION_LLM_FALLBACK");
        }

        return MockData.map(
                "answerType", "GENERAL",
                "message", assistantMessage,
                "builds", List.of(),
                "simulation", null,
                "buildAssessment", assessment,
                "warnings", warnings,
                "quickReplies", assessmentQuickReplies(assessment),
                "evidenceIds", evidenceIds,
                "agentSessionId", agentSessionId
        );
    }

    private static AssessmentFocus assessmentFocus(Map<String, Object> body, BuildChatIntentDecision decision) {
        Map<String, Object> context = objectMap(body.get("assessmentContext"));
        if (!context.isEmpty()) {
            String source = text(context.get("source"));
            String focusType = text(context.get("focusType"));
            if (!"QUOTE_DRAFT_CURRENT".equals(source)
                    || (!"SCORE".equals(focusType) && !"ISSUE".equals(focusType))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assessmentContext 값이 올바르지 않습니다.");
            }
        }
        String category = normalizeAssessmentCategory(firstText(text(context.get("category")), decision.targetCategory()));
        String tool = normalizeAssessmentTool(text(context.get("tool")));
        return new AssessmentFocus(category, tool);
    }

    private static AssessmentFocus verifiedAssessmentFocus(
        AssessmentFocus focus,
        BuildEvaluationService.BuildEvaluation evaluation
    ) {
        String category = focus.category();
        String requestedCategory = category;
        boolean categoryPresent = requestedCategory == null || evaluation.parts().stream()
                .map(part -> part.category() == null ? "" : part.category())
                .anyMatch(requestedCategory::equalsIgnoreCase);
        if (!categoryPresent) {
            category = null;
        }
        String tool = focus.tool();
        String requestedTool = tool;
        boolean toolPresent = requestedTool == null || evaluation.toolResults().stream()
                .map(result -> text(result.get("tool")))
                .anyMatch(requestedTool::equalsIgnoreCase);
        if (!toolPresent) {
            tool = null;
        }
        return new AssessmentFocus(category, tool);
    }

    private static String deterministicAssessmentMessage(Map<String, Object> assessment) {
        Integer scoreValue = firstNumber(assessment.get("score"));
        Integer maxScoreValue = firstNumber(assessment.get("maxScore"));
        int score = scoreValue == null ? 0 : scoreValue;
        int maxScore = maxScoreValue == null ? 1000 : maxScoreValue;
        String summary = firstText(text(assessment.get("summary")), "현재 견적의 확인 가능한 근거를 기준으로 평가했습니다.");
        return "현재 견적의 종합 점수는 " + score + "/" + maxScore + "점입니다. " + summary;
    }

    private static String safeAssessmentMessage(String candidate, Map<String, Object> assessment, String fallback) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 280) {
            return fallback;
        }
        String normalized = candidate.replaceAll("\\s+", " ").trim();
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (containsAnyText(lowered, "교체했습니다", "담았습니다", "저장했습니다", "적용했습니다", "삭제했습니다")) {
            return fallback;
        }
        Map<String, Object> facts = new LinkedHashMap<>(assessment);
        facts.remove("evaluatedAt");
        Matcher numeric = Pattern.compile("\\d[\\d,.]*").matcher(normalized);
        String factText = facts.toString().replace(",", "");
        while (numeric.find()) {
            String token = numeric.group().replace(",", "");
            if (!factText.contains(token)) {
                return fallback;
            }
        }
        return normalized;
    }

    private static List<String> assessmentQuickReplies(Map<String, Object> assessment) {
        List<String> prompts = objectMaps(assessment.get("recommendations")).stream()
                .map(item -> text(item.get("prompt")))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(3)
                .toList();
        return prompts.isEmpty()
                ? List.of("현재 견적에서 더 개선할 수 있는 부분 알려줘")
                : prompts;
    }

    private static String normalizeAssessmentCategory(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return CATEGORY_LABELS.containsKey(normalized) ? normalized : null;
    }

    private static String normalizeAssessmentTool(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Set.of("compatibility", "power", "size", "performance", "price").contains(normalized)
                ? normalized
                : null;
    }

    private static BuildEvaluationService defaultBuildEvaluationService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService
    ) {
        return new BuildEvaluationService(
                jdbcTemplate,
                toolCheckService,
                new BuildCompositeScoreService(),
                new BuildScoreAdviceService()
        );
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static String chatSurface(Map<String, Object> body) {
        String surface = text(objectMap(body.get("uiContext")).get("surface"));
        return "SELF_QUOTE".equalsIgnoreCase(surface) ? "SELF_QUOTE" : "HOME";
    }

    private static List<String> llmBoardFocusCategories(Map<String, Object> parsedContext, Map<String, Object> body) {
        if (!supportsBoardPartFocus(body)) {
            return List.of();
        }
        Map<String, Object> focus = objectMap(parsedContext == null ? null : parsedContext.get("boardFocusIntent"));
        if (!Boolean.TRUE.equals(focus.get("shouldFocus")) || !"HIGH".equals(text(focus.get("confidence")))) {
            return List.of();
        }
        return stringList(focus.get("categories")).stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(CATEGORY_LABELS::containsKey)
                .distinct()
                .limit(CATEGORY_LABELS.size())
                .toList();
    }

    private static boolean supportsBoardPartFocus(Map<String, Object> body) {
        Map<String, Object> uiContext = objectMap(body.get("uiContext"));
        if (!"SELF_QUOTE".equalsIgnoreCase(text(uiContext.get("surface")))) {
            return false;
        }
        return stringList(uiContext.get("capabilities")).stream()
                .anyMatch(capability -> "BOARD_PART_FOCUS".equalsIgnoreCase(capability));
    }

    private Map<String, Object> boardFocusResponse(List<String> categories) {
        List<String> safeCategories = categories == null ? List.of() : categories.stream()
                .map(value -> value == null ? null : value.toUpperCase(Locale.ROOT))
                .filter(Objects::nonNull)
                .filter(CATEGORY_LABELS::containsKey)
                .distinct()
                .limit(CATEGORY_LABELS.size())
                .toList();
        String label = safeCategories.stream().map(CATEGORY_LABELS::get).collect(java.util.stream.Collectors.joining(" · ")) + " 위치";
        Map<String, Object> response = fastResponse(
                "GENERAL",
                label + "를 현재 구성도에서 강조했습니다.",
                List.of()
        );
        response.put("boardFocus", MockData.map(
                "type", "PART_LOCATION",
                "categories", safeCategories,
                "label", label
        ));
        return response;
    }

    private static void attachFollowUpContext(Map<String, Object> response, String message) {
        if (response == null || message == null || message.isBlank() || response.get("clarification") != null) {
            return;
        }
        response.put("clarification", MockData.map(
                "missingSlots", List.of(),
                "originalMessage", message
        ));
    }

    private static boolean shouldPreserveSuccessfulTurnContext(Map<String, Object> response) {
        if (response == null || response.get("clarification") != null) {
            return false;
        }
        if (response.get("supportGuidance") != null) {
            return true;
        }
        if ("PART".equals(text(response.get("answerType")))
                && !stringList(response.get("quickReplies")).isEmpty()) {
            return true;
        }
        return objectMaps(response.get("builds")).stream()
                .anyMatch(build -> stringList(build.get("badges")).contains("DRAFT_EDIT_PREVIEW"));
    }

    // 종단 칩 플로어: 카드·칩·되묻기·시뮬레이션이 전부 빈 dead-end 응답에는 다음 행동 칩만 보강한다
    // (LLM 문구는 유지). 판정은 문구가 아니라 응답 형태로만 한다. LLM 경로와 시뮬 경로가 함께 쓴다.
    private void ensureNextAction(Map<String, Object> response, BuildChatIntent intent, BudgetIntent rawBudgetIntent) {
        if (objectMaps(response.get("builds")).isEmpty()
                && stringList(response.get("quickReplies")).isEmpty()
                && response.get("clarification") == null
                && response.get("simulation") == null) {
            if (intent == BuildChatIntent.BUILD_RECOMMEND
                    && rawBudgetIntent.hasBudget()
                    && rawBudgetIntent.budget() >= minimumBuildTotal()) {
                response.put("quickReplies", List.of(
                        formatBudgetLabel(rawBudgetIntent.budget()) + " PC 추천해줘",
                        "가능한 최소 구성으로 추천해줘"));
            } else {
                response.put("quickReplies", FEATURE_GUIDE_QUICK_REPLIES);
            }
        }
    }

    public record TierBuilds(List<Map<String, Object>> builds, List<String> warnings) {
    }

    // 티어 스냅샷 서빙 게이트: 모든 카드가 요청 예산 기준 모드 규칙을 만족할 때만 즉시 응답에 쓴다.
    // totalPrice가 없는 카드는 검증 불가이므로 서빙하지 않는다(일반 경로 폴백).
    private static boolean snapshotSatisfiesBudgetMode(List<Map<String, Object>> builds, int budgetWon, String mode) {
        if (builds == null || builds.isEmpty()) {
            return false;
        }
        for (Map<String, Object> build : builds) {
            if (!satisfiesBudgetMode(build, budgetWon, mode)
                    || hasBlockingToolFailure(objectMaps(build.get("toolResults")))) {
                return false;
            }
        }
        return true;
    }

    private static boolean satisfiesBudgetMode(Map<String, Object> build, int budgetWon, String mode) {
        Integer totalPrice = numberValue(build.get("totalPrice"));
        if (totalPrice == null) {
            return false;
        }
        if ("MAX".equals(mode)) {
            return totalPrice <= budgetWon;
        }
        if ("MIN".equals(mode)) {
            return totalPrice >= budgetWon;
        }
        return !"TARGET".equals(mode) || withinTargetBudgetBand(totalPrice, budgetWon);
    }

    // 예산 티어 스냅샷용 계산: "N만원 PC"는 계약상 target 예산이므로 스냅샷도 TARGET 규칙
    // (티어 예산 ±12.5% 밴드)으로 미리 계산한다. 실제 요청 예산과의 밴드 재검증은 서빙 시점에 한다.
    public TierBuilds computeBudgetTierBuilds(int budgetWon) {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> builds = nearBudgetLadderBuilds(budgetWon, "TARGET", List.of(), warnings, new BuildChatGuardStats());
        if (!builds.isEmpty()) {
            warnings.add("명시 예산 범위에 맞춰 내부 자산 기준 보조 견적을 재구성했습니다.");
        }
        return new TierBuilds(builds, warnings);
    }

    /**
     * 홈의 기본 추천 조합도 Build Chat과 같은 deterministic 생성·Tool 검증 결과를 사용한다.
     * 카테고리별 가격 정렬 결과를 인덱스로 조합하면 호환 불가 카드가 만들어질 수 있으므로,
     * 완전한 8개 카테고리 구성·예산 밴드·Tool FAIL 부재를 응답 직전에 다시 확인한다.
     */
    public synchronized Map<String, Object> homeRecommendedBuilds() {
        Optional<BuildChatTierSnapshotStore.TierSnapshot> stored = tierSnapshotStore == null
                ? Optional.empty()
                : tierSnapshotStore.bestFor(HOME_RECOMMENDED_BUILD_BUDGET_WON, "TARGET", 0);
        if (stored.isPresent()) {
            List<Map<String, Object>> storedSafeBuilds = safeHomeBuilds(stored.get().builds());
            if (!storedSafeBuilds.isEmpty()) {
                return MockData.map(
                        "items", storedSafeBuilds,
                        "generatedAt", stored.get().computedAt().toString(),
                        "fallbackUsed", false
                );
            }
        }

        TierBuilds generated = computeBudgetTierBuilds(HOME_RECOMMENDED_BUILD_BUDGET_WON);
        Instant generatedAt = Instant.now();
        List<Map<String, Object>> safeBuilds = safeHomeBuilds(generated.builds());
        if (tierSnapshotStore != null && !safeBuilds.isEmpty()) {
            tierSnapshotStore.put(new BuildChatTierSnapshotStore.TierSnapshot(
                    HOME_RECOMMENDED_BUILD_BUDGET_WON,
                    List.copyOf(safeBuilds),
                    List.copyOf(generated.warnings()),
                    generatedAt
            ));
        }
        return MockData.map(
                "items", safeBuilds,
                "generatedAt", generatedAt.toString(),
                "fallbackUsed", true
        );
    }

    private static List<Map<String, Object>> safeHomeBuilds(List<Map<String, Object>> candidates) {
        return candidates.stream()
                .filter(BuildChatService::isSafeCompleteHomeBuild)
                .limit(3)
                .toList();
    }

    static boolean isSafeCompleteHomeBuild(Map<String, Object> build) {
        List<Map<String, Object>> toolResults = objectMaps(build.get("toolResults"));
        Set<String> checkedTools = toolResults.stream()
                .map(result -> text(result.get("tool")).toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (!satisfiesBudgetMode(build, HOME_RECOMMENDED_BUILD_BUDGET_WON, "TARGET")
                || hasBlockingToolFailure(toolResults)
                || !checkedTools.containsAll(HOME_REQUIRED_TOOLS)) {
            return false;
        }
        Set<String> categories = objectMaps(build.get("items")).stream()
                .map(item -> text(item.get("category")).toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        return categories.containsAll(COMPLETE_BUILD_CATEGORIES);
    }

    static Integer parseBudgetWon(String message) {
        if (message == null) {
            return null;
        }
        String normalized = normalizeKoreanNumerals(message.replace(",", "").toLowerCase(Locale.ROOT));
        // 천만원 패턴보다 먼저 검사해야 "1억2천만원"이 2천만원으로 오독되지 않는다
        Matcher eokMatcher = BUDGET_EOK.matcher(normalized);
        if (eokMatcher.find()) {
            double eok = Double.parseDouble(eokMatcher.group(1));
            double cheonman = eokMatcher.group(2) == null ? 0 : Double.parseDouble(eokMatcher.group(2));
            return clampWon(eok * 100_000_000 + cheonman * 10_000_000);
        }
        Matcher cheonManWonMatcher = BUDGET_CHEONMANWON.matcher(normalized);
        // "천만에요" 같은 관용구 오탐을 막기 위해 선행 숫자, 백 단위, "원" 중 하나는 있어야 예산으로 본다
        if (cheonManWonMatcher.find()
                && (cheonManWonMatcher.group(1) != null || cheonManWonMatcher.group(2) != null || cheonManWonMatcher.group(3) != null)) {
            double thousands = cheonManWonMatcher.group(1) == null ? 1 : Double.parseDouble(cheonManWonMatcher.group(1));
            double hundreds = cheonManWonMatcher.group(2) == null ? 0 : Double.parseDouble(cheonManWonMatcher.group(2));
            return clampWon(thousands * 10_000_000 + hundreds * 1_000_000);
        }
        Matcher baekManWonMatcher = BUDGET_BAEKMANWON.matcher(normalized);
        if (baekManWonMatcher.find()) {
            return clampWon(Double.parseDouble(baekManWonMatcher.group(1)) * 1_000_000);
        }
        Matcher manWonMatcher = BUDGET_MANWON.matcher(normalized);
        if (manWonMatcher.find()) {
            return clampWon(Double.parseDouble(manWonMatcher.group(1)) * 10_000);
        }
        Matcher wonMatcher = BUDGET_WON.matcher(normalized);
        if (wonMatcher.find()) {
            // 원 단위 숫자는 상한이 없으므로 Integer 범위를 넘으면 NumberFormatException으로 500이 났다.
            // 다른 예산 파서(만원/백만원/천만원)와 동일하게 포화 캐스팅한다.
            try {
                long won = Long.parseLong(wonMatcher.group(1));
                return (int) Math.min(won, Integer.MAX_VALUE);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        // 숫자 없는 "만원" 단독은 구어에서 1만원이다("만원으로 맞춰줘"). 숫자가 붙은 표기는
        // 위 패턴들이 이미 소진했으므로 여기 도달한 "만원"은 bare 케이스뿐이다.
        Matcher bareManWonMatcher = BUDGET_MANWON_BARE.matcher(normalized);
        if (bareManWonMatcher.find()) {
            return 10_000;
        }
        return null;
    }

    // 만/억/원 앞에 붙는 "N백M십" 합성 수사만 하나의 숫자로 합친다("2백5십"→"250", "십"→"10").
    // 천은 기존 천만/억 패턴이 이미 읽고(선행 숫자·백·원 유무로 "천만에요" 관용구까지 구분), 천 뒤에 붙은
    // 백·십 런은 그 패턴을 깨지 않도록 건드리지 않는다.
    private static final Pattern KOREAN_HUNDRED_TEN_RUN =
            Pattern.compile("((?:[0-9]*[백십])+[0-9]*)(?=[만억원])");

    // "삼백만원", "일천삼백만원", "2천만원" 같은 한글/혼합 숫자 표기를 아라비아 숫자로 정규화한다
    private static String normalizeKoreanNumerals(String value) {
        String result = value;
        String digits = "일이삼사오육칠팔구";
        for (int index = 0; index < digits.length(); index += 1) {
            result = result.replace(String.valueOf(digits.charAt(index)), String.valueOf(index + 1));
        }
        Matcher matcher = KOREAN_HUNDRED_TEN_RUN.matcher(result);
        StringBuilder collapsed = new StringBuilder();
        while (matcher.find()) {
            boolean afterCheon = matcher.start() > 0 && result.charAt(matcher.start() - 1) == '천';
            String run = matcher.group(1);
            matcher.appendReplacement(collapsed,
                    Matcher.quoteReplacement(afterCheon ? run : String.valueOf(collapseHundredTenRun(run))));
        }
        matcher.appendTail(collapsed);
        return collapsed.toString();
    }

    private static long collapseHundredTenRun(String run) {
        long total = 0;
        long pending = 0;
        for (int index = 0; index < run.length(); index += 1) {
            char ch = run.charAt(index);
            if (ch >= '0' && ch <= '9') {
                pending = pending * 10 + (ch - '0');
            } else {
                total += (pending == 0 ? 1 : pending) * (ch == '백' ? 100 : 10);
                pending = 0;
            }
        }
        return total + pending;
    }

    // 곱셈 결과가 Integer 범위를 넘으면 (int) 캐스팅이 조용히 랩어라운드해 음수/잘못된 예산이
    // 그리디 엔진에 유입됐다. Integer.MAX_VALUE로 포화시켜 방지한다.
    private static int clampWon(double won) {
        return (int) Math.min(Math.round(won), (long) Integer.MAX_VALUE);
    }

    static BudgetIntent budgetIntent(String message) {
        Integer budget = parseBudgetWon(message);
        if (budget == null || budget <= 0) {
            return BudgetIntent.empty();
        }
        String normalized = normalizeCommand(message);
        String mode;
        if (containsAnyNormalized(normalized, "이하", "안으로", "안에서", "안에", "안쪽", "넘지않", "넘지말", "내로", "예산내", "범위내", "아래", "까지")) {
            mode = "MAX";
        } else if (containsAnyNormalized(normalized, "이상", "최소", "부터", "넘게")) {
            mode = "MIN";
        } else {
            mode = "TARGET";
        }
        return new BudgetIntent(budget, mode, hasRawHardPartConstraint(message), hasRawNegatedPartConstraint(message));
    }

    private static boolean isStandaloneBuildRecommend(String message, Map<String, Object> request, BudgetIntent rawBudgetIntent) {
        // 견적 초안 존재 여부는 map이 비었는지가 아니라 items가 있는지로 판정해야 함(빈 items 배열을 "초안 있음"으로 오인 방지)
        if (!objectMaps(objectMap(request.get("currentQuoteDraft")).get("items")).isEmpty() || (rawBudgetIntent != null && rawBudgetIntent.explicitHardConstraint())) {
            return false;
        }
        String normalized = normalizeCommand(message);
        boolean preferenceBuildLike = containsAnyNormalized(normalized, "위주", "말고", "cuda", "로컬ai", "실험용", "그래픽카드로추천");
        boolean buildLike = containsAnyNormalized(normalized,
                "pc", "피시", "컴퓨터", "컴", "견적", "본체", "구성", "데스크탑", "데스크톱", "워크스테이션", "머신", "사양", "조합",
                "목표", "게임", "게이밍", "qhd", "fhd", "4k", "hz", "배그", "발로란트", "오버워치", "사이버펑크", "로스트아크")
                || preferenceBuildLike;
        boolean recommendLike = containsAnyNormalized(normalized,
                "추천", "맞춰", "맞추", "구성", "용pc", "pc", "뽑아", "짜줘", "짜주", "골라", "조립", "만들어", "세팅", "내줘", "부탁", "필요", "구해줘");
        boolean budgetUseCaseLike = rawBudgetIntent != null
                && rawBudgetIntent.hasBudget()
                && hasSpecificBuildSignal(message, normalized);
        boolean mutationLike = containsAnyNormalized(normalized,
                "바꿔", "교체", "담아", "넣어", "빼", "삭제", "상세", "이동", "열어", "알림");
        return !mutationLike && hasSpecificBuildSignal(message, normalized) && (buildLike && recommendLike || budgetUseCaseLike);
    }

    static boolean isExplicitHardFullBuildRecommendation(
            String message,
            Map<String, Object> request,
            BudgetIntent rawBudgetIntent
    ) {
        if (rawBudgetIntent == null
                || !rawBudgetIntent.hasBudget()
                || !rawBudgetIntent.explicitHardConstraint()
                || !objectMaps(objectMap(request.get("currentQuoteDraft")).get("items")).isEmpty()) {
            return false;
        }
        String normalized = normalizeCommand(message);
        boolean buildContext = containsAnyNormalized(normalized,
                "pc", "피시", "컴퓨터", "견적", "본체", "조합", "게임", "게이밍", "개발", "작업용", "qhd", "fhd", "4k");
        boolean recommendationCommand = containsAnyNormalized(normalized,
                "추천", "맞춰", "맞추", "구성", "짜줘", "짜주", "만들어", "세팅", "조립");
        return buildContext && recommendationCommand;
    }

    private static boolean hasSpecificBuildSignal(String message, String normalized) {
        return parseBudgetWon(message) != null
                || containsAnyNormalized(normalized,
                "게임", "게이밍", "qhd", "fhd", "4k", "144", "hz", "배그", "발로란트", "발로", "오버워치", "옵치",
                "사이버펑크", "로스트아크", "로아", "디아블로", "디아", "몬헌", "몬스터헌터", "배틀필드", "스타크래프트",
                "스타2", "롤", "리그오브레전드", "메이플", "피파", "롤토체스", "레이드", "오픈월드",
                "영상", "편집", "프리미어", "블렌더", "렌더", "개발", "도커", "docker", "ide", "코딩", "ai", "cuda", "로컬ai", "실험용",
                "방송", "스트리밍", "송출", "유튜브", "포토샵", "디자인", "3d",
                "엔비디아", "라데온", "nvidia", "고성능", "최고급", "끝판왕", "하이엔드", "풀스펙", "저소음", "조용",
                "작은", "컴팩트", "저장", "로딩", "사무", "문서작업", "엑셀", "학습", "흰색", "화이트", "업그레이드",
                "가성비", "입문", "보급", "균형");
    }

    static String detectPartCategory(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        // 수랭/공랭은 쿨러 자체를 뜻하기도 하고 케이스의 장착 지원 조건을 수식하기도 한다.
        // 명시적 핵심 명사(쿨러/케이스)가 함께 나오면 한국어 수식 구조상 뒤쪽 대상을 우선한다.
        int caseIndex = Math.max(normalized.lastIndexOf("케이스"), lastKeywordIndex(normalized, "case"));
        int coolerIndex = Math.max(normalized.lastIndexOf("쿨러"), lastKeywordIndex(normalized, "cooler"));
        if (caseIndex >= 0 && caseIndex > coolerIndex) {
            return "CASE";
        }
        if (coolerIndex >= 0) {
            return "COOLER";
        }
        List<CategoryKeywords> checks = List.of(
                new CategoryKeywords("MOTHERBOARD", List.of("메인보드", "마더보드", "보드", "motherboard")),
                new CategoryKeywords("COOLER", List.of("쿨러", "cooler", "수랭", "공랭")),
                new CategoryKeywords("STORAGE", List.of("m.2", "m2", "ssd", "스토리지", "저장장치", "저장 공간", "nvme")),
                new CategoryKeywords("PSU", List.of("파워", "psu", "전원공급", "전원 공급")),
                new CategoryKeywords("CASE", List.of("케이스", "case")),
                new CategoryKeywords("GPU", List.of("gpu", "지피유", "그래픽카드", "그래픽 카드", "그래픽", "글카", "vga", "rtx", "cuda", "nvidia", "엔비디아", "geforce", "지포스")),
                new CategoryKeywords("CPU", List.of("cpu", "씨퓨", "씨피유", "프로세서", "라이젠", "ryzen", "intel", "인텔")),
                new CategoryKeywords("RAM", List.of("ram", "램", "메모리", "memory"))
        );
        return checks.stream()
                .filter(check -> check.keywords().stream().anyMatch(keyword -> containsCategoryKeyword(normalized, keyword)))
                .map(CategoryKeywords::category)
                .findFirst()
                .orElse(null);
    }

    /**
     * 관계형 추천 문장에서는 앞의 기준 부품이 아니라 추천 동사에 가장 가까운 카테고리가 대상이다.
     * 예: "현재 메인보드에 맞는 CPU 추천"은 CPU, "CPU에 맞는 메인보드 후보"는 메인보드.
     */
    static String detectRecommendationTargetCategory(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        int recommendationBoundary = firstKeywordIndex(
                normalized,
                List.of("추천", "후보", "골라", "어떤 게 좋", "어떤게 좋", "뭐가 좋", "뭐가좋")
        );
        if (recommendationBoundary < 0) {
            return null;
        }
        List<CategoryKeywords> categories = List.of(
                new CategoryKeywords("MOTHERBOARD", List.of("메인보드", "마더보드", "motherboard", "보드")),
                new CategoryKeywords("COOLER", List.of("쿨러", "cooler", "수랭", "공랭")),
                new CategoryKeywords("STORAGE", List.of("m.2", "m2", "ssd", "스토리지", "저장장치", "nvme")),
                new CategoryKeywords("PSU", List.of("파워", "psu", "전원공급", "전원 공급")),
                new CategoryKeywords("CASE", List.of("케이스", "case")),
                new CategoryKeywords("GPU", List.of("그래픽카드", "그래픽 카드", "글카", "gpu", "지피유", "vga", "rtx")),
                new CategoryKeywords("CPU", List.of("cpu", "씨퓨", "씨피유", "프로세서", "라이젠", "ryzen", "인텔", "intel")),
                new CategoryKeywords("RAM", List.of("ram", "램", "메모리", "memory"))
        );
        String selected = null;
        int selectedIndex = -1;
        String prefix = normalized.substring(0, recommendationBoundary);
        for (CategoryKeywords category : categories) {
            for (String keyword : category.keywords()) {
                int index = prefix.lastIndexOf(keyword);
                if (index > selectedIndex) {
                    selected = category.category();
                    selectedIndex = index;
                }
            }
        }
        return selected;
    }

    private static int firstKeywordIndex(String text, List<String> keywords) {
        int selected = -1;
        for (String keyword : keywords) {
            int index = text.indexOf(keyword);
            if (index >= 0 && (selected < 0 || index < selected)) {
                selected = index;
            }
        }
        return selected;
    }

    private static boolean containsCategoryKeyword(String normalized, String keyword) {
        if (keyword.chars().allMatch(character -> character < 128)) {
            return Pattern.compile("(?<![a-z])" + Pattern.quote(keyword) + "(?![a-z])")
                    .matcher(normalized)
                    .find();
        }
        return normalized.contains(keyword);
    }

    private static int lastKeywordIndex(String normalized, String keyword) {
        Matcher matcher = Pattern.compile("(?<![a-z])" + Pattern.quote(keyword) + "(?![a-z])")
                .matcher(normalized);
        int lastIndex = -1;
        while (matcher.find()) {
            lastIndex = matcher.start();
        }
        return lastIndex;
    }

    private Map<String, Object> responseMap(
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent,
            BuildChatGuardStats guardStats
    ) {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> builds = engineBuilds(engineResponse, rawBudgetIntent, warnings, guardStats);
        warnings.addAll(buildWarnings(builds));
        warnings.addAll(stringList(engineResponse.parsedContext().get("warnings")));
        return MockData.map(
                "answerType", answerType(engineResponse.intent()),
                "message", engineResponse.assistantMessage(),
                "builds", builds,
                "warnings", distinct(warnings),
                "evidenceIds", engineResponse.evidenceIds(),
                "agentSessionId", engineResponse.agentSessionId()
        );
    }

    private Optional<Map<String, Object>> performanceSimulationResponse(Map<String, Object> request, String message) {
        // 시뮬 진입 판정은 라우터(isSimulationIntent)가 단독으로 한다 — 여기서 재검사하지 않고
        // 카테고리·드래프트·해상 가능 target 유무만 보고 카드 또는 되묻기로 흘려보낸다.
        String category = firstText(detectPartCategory(message), EXPLICIT_GPU_MODEL.matcher(message == null ? "" : message).find() ? "GPU" : null);
        if (category == null) {
            return Optional.empty();
        }
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        if (draftItems.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> currentItem = findDraftItem(draftItems, category, message);
        if (currentItem.isEmpty()) {
            return Optional.empty();
        }
        PartCandidate currentPart = draftPartCandidate(currentItem);
        PartCandidate currentCpu = draftPartCandidate(findDraftItem(draftItems, "CPU", message));
        Optional<PartCandidate> targetPart = simulationTargetPart(category, message)
                .or(() -> simulationTargetFromDraftEditPreview(request, category, currentItem));
        if (targetPart.isEmpty()) {
            // 교체 대상 미해상 — 호출부가 "속성 요청(수랭/PCIe/통풍) → LLM 흘려보내기"와 "되묻기"를
            // 가를 수 있도록 카드 없음(empty)만 알린다. dead-end 문구/에코는 호출부가 결정한다.
            return Optional.empty();
        }

        PartCandidate target = targetPart.get();
        List<String> warnings = new ArrayList<>();
        List<PartCandidate> previewParts = simulationPreviewParts(draftItems, target);
        List<Map<String, Object>> toolResults = toolResults(previewParts, totalPrice(previewParts), warnings);
        boolean hasBlockingFail = hasBlockingToolFailure(toolResults);
        if (hasBlockingFail) {
            warnings.add("현재 견적 기준 호환성 문제가 있을 수 있어 실제 교체 전 케이스/파워/소켓 조건을 확인해야 합니다.");
        }

        BenchmarkSnapshot currentBenchmark = latestBenchmark(currentPart).orElse(null);
        BenchmarkSnapshot targetBenchmark = latestBenchmark(target).orElse(null);
        List<Map<String, Object>> currentFps = "GPU".equals(category) ? simulationFpsEvidence(currentCpu, currentPart, message) : List.of();
        List<Map<String, Object>> targetFps = "GPU".equals(category) ? simulationFpsEvidence(currentCpu, target, message) : List.of();
        Map<String, Object> simulation = simulationPayload(category, currentPart, target, currentBenchmark, targetBenchmark, currentFps, targetFps, warnings);
        Map<String, Object> response = fastResponse(
                "GENERAL",
                simulationSummary(category, currentPart, target, !simulationFpsComparisons(currentFps, targetFps).isEmpty()),
                warnings
        );
        response.put("simulation", simulation);

        return Optional.of(response);
    }

    private Optional<PartCandidate> simulationTargetFromDraftEditPreview(
            Map<String, Object> request,
            String category,
            Map<String, Object> currentItem
    ) {
        String currentPartId = text(currentItem.get("partId"));
        for (Map<String, Object> build : objectMaps(request.get("currentBuilds"))) {
            if (!stringList(build.get("badges")).contains("DRAFT_EDIT_PREVIEW")) {
                continue;
            }
            for (Map<String, Object> item : objectMaps(build.get("items"))) {
                String targetPartId = text(item.get("partId"));
                if (!category.equals(text(item.get("category")))
                        || targetPartId == null
                        || targetPartId.equals(currentPartId)
                        || !isUuid(targetPartId)) {
                    continue;
                }
                try {
                    return Optional.of(partByPublicId(targetPartId));
                } catch (RuntimeException ignored) {
                    // The preview may be stale after a catalog update. Do not invent a target from client data.
                }
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> simulationClarificationResponse(Map<String, Object> request, String message) {
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        String category = firstText(detectPartCategory(message), EXPLICIT_GPU_MODEL.matcher(message == null ? "" : message).find() ? "GPU" : null);
        String guidance;
        if (draftItems.isEmpty()) {
            guidance = "성능 비교는 현재 견적 기준으로 계산합니다. 먼저 셀프 견적 그래프에서 부품을 담거나 견적 추천을 받아주세요.";
        } else if (category == null) {
            guidance = "어떤 부품을 바꿀지 알려주세요. 예: \"CPU를 9700X로 바꾸면?\", \"그래픽카드를 5090으로 바꾸면?\"";
        } else {
            guidance = categoryLabel(category) + " 시뮬레이션을 하려면 바꿀 제품을 조금 더 구체적으로 알려주세요.";
        }
        return fastResponse("GENERAL", guidance, List.of("SIMULATION_TARGET_NOT_FOUND"));
    }

    // 속성 어휘를 가진 카테고리(오너 확정 3종): 쿨러 냉각방식 / SSD PCIe 세대 / 케이스 통풍.
    // 이 스코프 밖(GPU·메인보드 등)은 속성 요청이 아니므로 시뮬 미해상 시 되묻기를 유지한다.
    private static final Set<String> ATTRIBUTE_CATEGORIES = Set.of("COOLER", "STORAGE", "CASE");

    // 시뮬 카드가 못 나왔을 때 LLM 속성 경로로 흘려보낼지 판정한다.
    // 조건: 속성 어휘 카테고리 + 드래프트에 그 부품 존재(교체 대상은 있는데 모델·용량·와트로 target을
    // 못 잡음 = "수랭으로/PCIe 5.0으로/통풍 좋은" 같은 속성 요청 유력). 그 외(모호·타 카테고리)는 되묻기 유지.
    private boolean simulationAttributeFallThroughEligible(Map<String, Object> body, String message) {
        String category = firstText(
                detectPartCategory(message),
                EXPLICIT_GPU_MODEL.matcher(message == null ? "" : message).find() ? "GPU" : null);
        if (category == null || !ATTRIBUTE_CATEGORIES.contains(category)) {
            return false;
        }
        List<Map<String, Object>> draftItems = objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"));
        return !draftItems.isEmpty() && !findDraftItem(draftItems, category, message).isEmpty();
    }

    private Optional<PartCandidate> simulationTargetPart(String category, String message) {
        if (category == null) {
            return Optional.empty();
        }
        String gpuClass = "GPU".equals(category) ? targetGpuClass(message) : null;
        String modelToken = simulationModelToken(category, message);
        Integer capacityGb = parseCapacityGb(message);
        Integer wattage = parseWattage(message);

        List<String> filters = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        params.add(category);
        if (gpuClass != null) {
            filters.add("p.attributes->>'gpuClass' = ?");
            params.add(gpuClass);
        }
        if (modelToken != null) {
            filters.add("(upper(p.name) LIKE '%' || upper(?) || '%' OR upper(coalesce(p.manufacturer, '')) LIKE '%' || upper(?) || '%')");
            params.add(modelToken);
            params.add(modelToken);
        }
        if (capacityGb != null && ("RAM".equals(category) || "STORAGE".equals(category))) {
            filters.add(safeNumericAttribute("capacityGb", "kitCapacityGb", "memoryGb") + " >= ?");
            params.add(capacityGb);
        }
        if (wattage != null && "PSU".equals(category)) {
            filters.add(safeNumericAttribute("capacityW", "wattage") + " >= ?");
            params.add(wattage);
        }

        // 바꿀 대상 제품을 특정할 신호(모델/용량/와트)가 하나도 없으면 임의 후보를 잡지 않는다.
        // 축소 정책상 target 불명확 시뮬레이션은 임의 비교 대신 되묻기로 처리해야 한다
        // (호출부 performanceSimulationResponse가 empty를 받으면 simulationClarificationResponse로 유도).
        if (filters.isEmpty()) {
            return Optional.empty();
        }

        List<String> order = new ArrayList<>();
        if (gpuClass != null) {
            order.add("CASE WHEN p.attributes->>'gpuClass' = '" + gpuClass.replace("'", "''") + "' THEN 0 ELSE 1 END");
        }
        if (modelToken != null) {
            order.add("CASE WHEN upper(p.name) LIKE '%' || upper('" + modelToken.replace("'", "''") + "') || '%' THEN 0 ELSE 1 END");
        }
        if (capacityGb != null && ("RAM".equals(category) || "STORAGE".equals(category))) {
            order.add(safeNumericAttribute("capacityGb", "kitCapacityGb", "memoryGb") + " ASC");
        }
        if (wattage != null && "PSU".equals(category)) {
            order.add(safeNumericAttribute("capacityW", "wattage") + " ASC");
        }
        order.add("b.score DESC NULLS LAST");
        order.add("p.price ASC");
        order.add("p.name ASC");

        String sql = """
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.attributes,
                               b.score AS benchmark_score
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT score
                          FROM benchmark_summaries bs
                          WHERE bs.part_id = p.id
                            AND bs.deleted_at IS NULL
                          ORDER BY bs.score DESC NULLS LAST, bs.created_at DESC
                          LIMIT 1
                        ) b ON true
                        WHERE p.category = ?
                          AND p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                          AND p.price IS NOT NULL
                          AND (""" + String.join(" OR ", filters) + """
                          )
                        """ + "\nORDER BY " + String.join(", ", order) + "\nLIMIT 1\n";

        return jdbcTemplate.queryForList(sql, params.toArray())
                .stream()
                .findFirst()
                .map(this::partCandidate);
    }

    private static String targetGpuClass(String message) {
        Matcher matcher = EXPLICIT_GPU_MODEL.matcher(message == null ? "" : message);
        if (!matcher.find()) {
            return null;
        }
        String model = matcher.group(1);
        String suffix = matcher.group(2);
        String result = "RTX_" + model;
        if (suffix != null && !suffix.isBlank()) {
            result += "_" + suffix.toUpperCase(Locale.ROOT);
        }
        return result;
    }

    private static final Pattern MODEL_TOKEN_PATTERN = Pattern.compile("(?i)([a-z가-힣]*\\s*\\d{2,5}[a-z0-9가-힣-]*)");
    // "A에서 B로 바꾸면"처럼 출발지·도착지가 함께 오면 도착지(B) 구간만 대상으로 삼는다.
    private static final Pattern REPLACEMENT_DESTINATION = Pattern.compile("에서\\s*(.+?)\\s*(?:으로|로)(?![0-9a-z가-힣])", Pattern.CASE_INSENSITIVE);
    // 모델 토큰 끝에 달라붙는 한국어 조사 — 카탈로그 매칭을 방해하므로 떼어낸다.
    private static final Pattern TRAILING_PARTICLE = Pattern.compile("(?:에서|으로|로|를|은|는|이|가)$");

    static String simulationModelToken(String category, String message) {
        String text = message == null ? "" : message;
        if ("GPU".equals(category)) {
            String gpuClass = targetGpuClass(message);
            return gpuClass == null ? null : gpuClass.replace("RTX_", "RTX ").replace("_", " ");
        }
        if ("CPU".equals(category)) {
            Matcher matcher = EXPLICIT_CPU_MODEL.matcher(text);
            return matcher.find() ? matcher.group() : null;
        }
        Matcher destination = REPLACEMENT_DESTINATION.matcher(text);
        String scanText = destination.find() ? destination.group(1) : text;
        Matcher model = MODEL_TOKEN_PATTERN.matcher(scanText);
        if (model.find()) {
            return stripTrailingParticle(model.group(1).trim());
        }
        String brand = brandToken(scanText);
        return brand == null ? null : brand;
    }

    private static String stripTrailingParticle(String token) {
        if (token == null) {
            return null;
        }
        String stripped = TRAILING_PARTICLE.matcher(token).replaceFirst("").trim();
        return stripped.isEmpty() ? token : stripped;
    }

    private static String safeNumericAttribute(String... keys) {
        String coalesce = java.util.Arrays.stream(keys)
                .map(key -> "p.attributes->>'" + key + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        return "coalesce(NULLIF(regexp_replace(coalesce(" + coalesce + ", '0'), '[^0-9]', '', 'g'), '')::int, 0)";
    }

    private PartCandidate draftPartCandidate(Map<String, Object> item) {
        String publicId = text(item.get("partId"));
        if (publicId != null && isUuid(publicId)) {
            try {
                return partByPublicId(publicId);
            } catch (RuntimeException ignored) {
                // Draft data is still usable for lightweight simulation if the catalog row was removed.
            }
        }
        PartCandidate candidate = partCandidateFromDraftItem(item);
        if (candidate == null) {
            return new PartCandidate(null, null, text(item.get("category")), firstText(text(item.get("name")), "부품"), text(item.get("manufacturer")), 0, objectMap(item.get("attributes")));
        }
        return candidate;
    }

    private List<PartCandidate> simulationPreviewParts(List<Map<String, Object>> draftItems, PartCandidate target) {
        List<PartCandidate> result = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> item : draftItems) {
            String category = text(item.get("category"));
            if (target.category().equals(category)) {
                if (!replaced) {
                    result.add(target);
                    replaced = true;
                }
                continue;
            }
            PartCandidate candidate = draftPartCandidate(item);
            if (candidate != null) {
                result.add(candidate);
            }
        }
        if (!replaced) {
            result.add(target);
        }
        return result;
    }

    private Optional<BenchmarkSnapshot> latestBenchmark(PartCandidate part) {
        if (part == null || part.internalId() == null) {
            return Optional.empty();
        }
        return jdbcTemplate.queryForList("""
                        SELECT score,
                               summary
                        FROM benchmark_summaries
                        WHERE part_id = ?
                          AND deleted_at IS NULL
                        ORDER BY score DESC NULLS LAST, created_at DESC, id DESC
                        LIMIT 1
                        """, part.internalId())
                .stream()
                .findFirst()
                .map(row -> new BenchmarkSnapshot(doubleValue(row.get("score")), text(row.get("summary"))));
    }

    private List<Map<String, Object>> simulationFpsEvidence(PartCandidate cpu, PartCandidate gpu, String message) {
        if (gpu == null) {
            return List.of();
        }
        String gpuClass = hardwareClass(gpu);
        if (gpuClass == null) {
            return List.of();
        }
        String cpuClass = hardwareClass(cpu);
        Long gpuId = gpu.internalId() == null ? -1L : gpu.internalId();
        Long cpuId = cpu == null || cpu.internalId() == null ? -1L : cpu.internalId();
        String gameKey = gameKeyFromText(message);
        String resolution = resolutionFromText(message);
        List<Object> params = new ArrayList<>();
        params.add(gpuId);
        params.add(gpuClass);
        params.add(cpuId);
        params.add(cpuClass);
        String resolutionRank = "0 AS resolution_rank\n";
        if (resolution != null) {
            resolutionRank = "CASE WHEN resolution = ? THEN 0 ELSE 1 END AS resolution_rank\n";
            params.add(resolution);
        }
        params.add(gpuId);
        params.add(gpuClass);
        String gameFilter = "";
        if (gameKey != null) {
            gameFilter = " AND game_key = ?\n";
            params.add(gameKey);
        }
        params.add(4);
        return jdbcTemplate.queryForList("""
                        SELECT game_title,
                               game_key,
                               resolution,
                               graphics_preset,
                               avg_fps,
                               one_percent_low_fps,
                               source_name,
                               confidence,
                               metadata,
                               CASE WHEN gpu_part_id = ? THEN 0 WHEN metadata->>'gpuClass' = ? THEN 1 ELSE 2 END AS gpu_rank,
                               CASE WHEN cpu_part_id = ? THEN 0 WHEN metadata->>'cpuClass' = ? THEN 1 ELSE 2 END AS cpu_rank,
                               """ + resolutionRank + """
                        FROM game_fps_benchmarks
                        WHERE deleted_at IS NULL
                          AND (gpu_part_id = ? OR metadata->>'gpuClass' = ?)
                        """ + gameFilter + """
                        ORDER BY gpu_rank,
                                 cpu_rank,
                                 resolution_rank,
                                 CASE confidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                                 source_checked_at DESC,
                                 id DESC
                        LIMIT ?
                        """, params.toArray());
    }

    private Map<String, Object> simulationPayload(
            String category,
            PartCandidate currentPart,
            PartCandidate targetPart,
            BenchmarkSnapshot currentBenchmark,
            BenchmarkSnapshot targetBenchmark,
            List<Map<String, Object>> currentFps,
            List<Map<String, Object>> targetFps,
            List<String> warnings
    ) {
        List<Map<String, Object>> fpsComparisons = simulationFpsComparisons(currentFps, targetFps);
        List<Map<String, Object>> specComparisons = simulationSpecComparisons(category, currentPart, targetPart);
        List<String> simulationWarnings = new ArrayList<>(warnings);
        if (fpsComparisons.isEmpty() && "GPU".equals(category)) {
            simulationWarnings.add("요청 조건과 정확히 맞는 게임 FPS 자료가 부족해 확인 가능한 벤치마크와 스펙 중심으로 표시했습니다.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "PERFORMANCE_COMPARISON");
        result.put("category", category);
        result.put("currentPart", simulationPart(currentPart));
        result.put("targetPart", simulationPart(targetPart));
        result.put("summary", simulationSummary(category, currentPart, targetPart, !fpsComparisons.isEmpty()));
        result.put("scoreComparison", simulationScoreComparison(currentBenchmark, targetBenchmark));
        result.put("fpsComparisons", fpsComparisons);
        result.put("specComparisons", specComparisons);
        result.put("warnings", distinct(simulationWarnings));
        result.put("disclaimer", SIMULATION_DISCLAIMER);
        return result;
    }

    private static Map<String, Object> simulationPart(PartCandidate part) {
        return MockData.map(
                "partId", part.publicId(),
                "category", part.category(),
                "name", part.name(),
                "manufacturer", part.manufacturer(),
                "price", part.price()
        );
    }

    private static Map<String, Object> simulationScoreComparison(BenchmarkSnapshot currentBenchmark, BenchmarkSnapshot targetBenchmark) {
        Double current = currentBenchmark == null ? null : currentBenchmark.score();
        Double target = targetBenchmark == null ? null : targetBenchmark.score();
        if (current == null && target == null) {
            return null;
        }
        return MockData.map(
                "label", "내부 벤치마크 정규화 점수 (참고용)",
                "currentScore", current,
                "targetScore", target,
                "delta", current == null || target == null ? null : target - current
        );
    }

    private static String simulationSummary(String category, PartCandidate currentPart, PartCandidate targetPart, boolean hasFpsRows) {
        if ("GPU".equals(category) && hasFpsRows) {
            return currentPart.name() + "에서 " + targetPart.name() + "(으)로 바꾸면 게임 FPS가 해상도별로 달라질 수 있습니다. 아래 벤치마크 표를 참고하세요.";
        }
        return categoryLabel(category) + "를 " + targetPart.name() + "(으)로 바꿨을 때 확인 가능한 벤치마크와 주요 스펙 차이를 정리했습니다.";
    }

    private List<Map<String, Object>> simulationFpsComparisons(List<Map<String, Object>> currentFps, List<Map<String, Object>> targetFps) {
        if (targetFps == null || targetFps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> target : targetFps.stream().limit(3).toList()) {
            String key = text(target.get("game_key")) + "|" + text(target.get("resolution"));
            Map<String, Object> current = currentFps == null ? Map.of() : currentFps.stream()
                    .filter(item -> key.equals(text(item.get("game_key")) + "|" + text(item.get("resolution"))))
                    .findFirst()
                    .orElse(Map.of());
            Double targetAvg = doubleValue(target.get("avg_fps"));
            Double currentAvg = doubleValue(current.get("avg_fps"));
            rows.add(MockData.map(
                    "gameTitle", firstText(text(target.get("game_title")), "게임"),
                    "resolution", firstText(text(target.get("resolution")), "해상도 미상"),
                    "graphicsPreset", text(target.get("graphics_preset")),
                    "currentFps", currentAvg,
                    "targetFps", targetAvg,
                    "deltaFps", currentAvg == null || targetAvg == null ? null : targetAvg - currentAvg,
                    "source", text(target.get("source_name"))
            ));
        }
        return rows;
    }

    private static List<Map<String, Object>> simulationSpecComparisons(String category, PartCandidate currentPart, PartCandidate targetPart) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (category) {
            case "GPU" -> {
                addSpecNumber(rows, "VRAM", currentPart, targetPart, "GB", "vramGb", "memoryGb");
                addSpecNumber(rows, "소비전력", currentPart, targetPart, "W", "wattage", "tdpW");
                addSpecNumber(rows, "그래픽카드 길이", currentPart, targetPart, "mm", "lengthMm");
            }
            case "CPU" -> {
                addSpecNumber(rows, "코어", currentPart, targetPart, "개", "coreCount", "cores");
                addSpecNumber(rows, "스레드", currentPart, targetPart, "개", "threadCount", "threads");
                addSpecNumber(rows, "TDP", currentPart, targetPart, "W", "tdpW", "wattage");
            }
            case "RAM" -> {
                addSpecNumber(rows, "총 용량", currentPart, targetPart, "GB", "capacityGb", "kitCapacityGb", "memoryGb");
                addSpecNumber(rows, "모듈 수", currentPart, targetPart, "개", "moduleCount");
                addSpecNumber(rows, "메모리 속도", currentPart, targetPart, "MHz", "speedMhz");
                addSpecText(rows, "메모리 세대", currentPart, targetPart, "memoryType", "ddrGeneration");
            }
            case "STORAGE" -> {
                addSpecNumber(rows, "용량", currentPart, targetPart, "GB", "capacityGb");
                addSpecText(rows, "인터페이스", currentPart, targetPart, "interface", "formFactor");
                addSpecNumber(rows, "순차 읽기", currentPart, targetPart, "MB/s", "readMbps");
                addSpecNumber(rows, "순차 쓰기", currentPart, targetPart, "MB/s", "writeMbps");
            }
            case "PSU" -> {
                addSpecNumber(rows, "정격 출력", currentPart, targetPart, "W", "capacityW", "wattage");
                addSpecText(rows, "효율 등급", currentPart, targetPart, "efficiency");
                addSpecText(rows, "ATX 규격", currentPart, targetPart, "atxSpec", "pcieSpec");
                addSpecText(rows, "GPU 커넥터", currentPart, targetPart, "gpuConnector", "powerConnector");
            }
            case "MOTHERBOARD" -> {
                addSpecText(rows, "칩셋", currentPart, targetPart, "chipset");
                addSpecText(rows, "메모리 규격", currentPart, targetPart, "memoryType");
                addSpecText(rows, "폼팩터", currentPart, targetPart, "formFactor");
                addSpecText(rows, "Wi-Fi", currentPart, targetPart, "hasWifi");
            }
            case "CASE" -> {
                addSpecNumber(rows, "GPU 장착 길이", currentPart, targetPart, "mm", "maxGpuLengthMm");
                addSpecNumber(rows, "CPU 쿨러 높이", currentPart, targetPart, "mm", "maxCpuCoolerHeightMm");
                addSpecNumber(rows, "PSU 길이", currentPart, targetPart, "mm", "maxPsuLengthMm");
                addSpecText(rows, "전면 메쉬", currentPart, targetPart, "frontMesh", "airflowFocus");
            }
            case "COOLER" -> {
                addSpecText(rows, "냉각 방식", currentPart, targetPart, "coolerType");
                addSpecNumber(rows, "TDP 대응", currentPart, targetPart, "W", "tdpW");
                addSpecNumber(rows, "높이", currentPart, targetPart, "mm", "heightMm");
                addSpecNumber(rows, "라디에이터", currentPart, targetPart, "mm", "radiatorLengthMm");
            }
            default -> {
            }
        }
        return rows;
    }

    private static void addSpecNumber(List<Map<String, Object>> rows, String label, PartCandidate currentPart, PartCandidate targetPart, String unit, String... keys) {
        Integer current = firstAttributeNumber(currentPart, keys);
        Integer target = firstAttributeNumber(targetPart, keys);
        if (current == null && target == null) {
            return;
        }
        rows.add(MockData.map(
                "label", label,
                "currentValue", current == null ? null : current + unit,
                "targetValue", target == null ? null : target + unit,
                "deltaText", current == null || target == null ? null : signedNumber(target - current, unit)
        ));
    }

    private static void addSpecText(List<Map<String, Object>> rows, String label, PartCandidate currentPart, PartCandidate targetPart, String... keys) {
        String current = firstAttributeText(currentPart, keys);
        String target = firstAttributeText(targetPart, keys);
        if (current == null && target == null) {
            return;
        }
        rows.add(MockData.map(
                "label", label,
                "currentValue", current,
                "targetValue", target,
                "deltaText", null
        ));
    }

    private static Integer firstAttributeNumber(PartCandidate part, String... keys) {
        if (part == null || part.attributes() == null) {
            return null;
        }
        for (String key : keys) {
            Integer value = numberValue(part.attributes().get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstAttributeText(PartCandidate part, String... keys) {
        if (part == null || part.attributes() == null) {
            return null;
        }
        for (String key : keys) {
            String value = text(part.attributes().get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String signedNumber(int value, String unit) {
        if (value == 0) {
            return "변화 없음";
        }
        return (value > 0 ? "+" : "") + value + unit;
    }

    private static String hardwareClass(PartCandidate part) {
        if (part == null) {
            return null;
        }
        String explicit = firstText(text(part.attributes().get("hardwareClass")), firstText(text(part.attributes().get("gpuClass")), text(part.attributes().get("cpuClass"))));
        if (explicit != null) {
            return explicit;
        }
        String name = firstText(part.name(), "").toLowerCase(Locale.ROOT);
        if ("GPU".equals(part.category())) {
            if (name.contains("5090")) return "RTX_5090";
            if (name.contains("5080")) return "RTX_5080";
            if (name.matches(".*5070\\s*ti.*") || name.contains("5070ti")) return "RTX_5070_TI";
            if (name.contains("5070")) return "RTX_5070";
            if (name.matches(".*5060\\s*ti.*") || name.contains("5060ti")) return "RTX_5060_TI";
            if (name.contains("5060")) return "RTX_5060";
        }
        if ("CPU".equals(part.category())) {
            if (name.contains("9950x3d")) return "RYZEN_9_9950X3D";
            if (name.contains("9950x")) return "RYZEN_9_9950X";
            if (name.contains("9900x3d")) return "RYZEN_9_9900X3D";
            if (name.contains("9800x3d")) return "RYZEN_7_9800X3D";
            if (name.contains("9700x")) return "RYZEN_7_9700X";
            if (name.contains("9600x")) return "RYZEN_5_9600X";
        }
        return null;
    }

    private static String gameKeyFromText(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAnyText(text, "배그", "pubg", "battleground")) return "pubg";
        if (containsAnyText(text, "로아", "로스트아크", "lost ark")) return "lost-ark";
        if (containsAnyText(text, "발로란트", "valorant")) return "valorant";
        if (containsAnyText(text, "오버워치", "overwatch")) return "overwatch-2";
        if (containsAnyText(text, "사이버펑크", "사펑", "cyberpunk")) return "cyberpunk-2077";
        return null;
    }

    private static String resolutionFromText(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAnyText(text, "4k", "uhd", "2160")) return "4K";
        if (containsAnyText(text, "qhd", "1440", "2560")) return "QHD";
        if (containsAnyText(text, "fhd", "1080", "1920")) return "FHD";
        return null;
    }

    private static boolean containsAnyText(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String brandToken(String message) {
        String normalized = normalizeCommand(message);
        for (String token : List.of(
                "msi", "asus", "gigabyte", "기가바이트", "리안리", "lianli", "corsair", "커세어",
                "samsung", "삼성", "superflower", "슈퍼플라워", "fsp", "arctic", "녹투아", "noctua"
        )) {
            if (normalized.contains(normalizeCommand(token))) {
                return token;
            }
        }
        return null;
    }

    // 모호 요청 되묻기: 견적을 같이 던지지 않고 질문 + 선택지 칩만 준다. 칩 문구는 그 자체로
    // 완전한 프롬프트(용도+예산)라서 클릭 즉시 프리웜 티어 스냅샷에 적중해 즉답이 된다.
    private Map<String, Object> clarificationResponse(String message, String rawFollowUp, List<String> reasons, boolean followUp) {
        if (followUp) {
            // 이미 한 번 되물었는데도 예산·용도를 못 읽었다 — 임의 예산(300만) 3안을 가정하지 않고,
            // 사용자의 말을 인용해 무엇을 못 읽었는지 솔직하게 밝히고 한 번 더 정확히 묻는다.
            String quoted = rawFollowUp == null || rawFollowUp.isBlank() ? "" : "말씀하신 \"" + rawFollowUp.trim() + "\"에서 ";
            Map<String, Object> retry = fastResponse(
                    "GENERAL",
                    quoted + "예산과 용도를 정확히 읽지 못했어요. 숫자를 포함한 예산(예: 100만원)이나 "
                            + "용도(게임/사무/영상편집)를 한 번만 더 알려주시면 바로 추천해드릴게요.",
                    List.of("LOW_INFORMATION")
            );
            retry.put("quickReplies", List.of("사무용 100만원", "게이밍 200만원", "게이밍 300만원", "영상편집 400만원"));
            retry.put("clarification", MockData.map(
                    "missingSlots", List.of("budget", "useCase"),
                    "originalMessage", message
            ));
            return retry;
        }
        boolean resolutionHint = reasons != null && reasons.contains("RESOLUTION_CONTEXT");
        boolean usageOnly = reasons != null && reasons.contains("USAGE_ONLY");
        String question = usageOnly
                ? "요청하신 용도는 확인했어요. 생각하신 예산대를 골라주시면 그 범위에 맞춰 추천해드릴게요."
                : resolutionHint
                ? "어떤 해상도 기준으로 맞출까요? 해상도에 따라 필요한 그래픽카드 급이 크게 달라져요. 예산까지 알려주시면 바로 추천해드릴게요."
                : "용도와 예산을 알려주시면 정확하게 맞춰드릴 수 있어요. 아래에서 골라도 되고, 직접 입력해도 돼요.";
        List<String> quickReplies = usageOnly
                ? USAGE_ONLY_BUDGET_DIRECTION_CHIPS
                : resolutionHint
                ? List.of("FHD 게이밍 150만원", "QHD 게이밍 250만원", "4K 게이밍 400만원")
                : List.of("사무용 100만원", "게이밍 200만원", "게이밍 300만원", "영상편집 400만원");
        Map<String, Object> response = fastResponse("GENERAL", question, List.of("LOW_INFORMATION"));
        response.put("quickReplies", quickReplies);
        response.put("clarification", MockData.map(
                "missingSlots", usageOnly ? List.of("budget") : List.of("budget", "useCase"),
                "originalMessage", message
        ));
        return response;
    }

    private Map<String, Object> fastResponse(String answerType, String message, List<String> warnings) {
        return fastResponse(answerType, message, List.of(), warnings);
    }

    private Map<String, Object> fastResponse(
            String answerType,
            String message,
            List<Map<String, Object>> builds,
            List<String> warnings
    ) {
        Map<String, Object> response = MockData.map(
                "answerType", answerType,
                "message", message,
                "builds", builds == null ? List.of() : builds,
                "warnings", distinct(warnings),
                "evidenceIds", List.of(),
                "agentSessionId", null
        );
        alignBuildCountMessage(response);
        return response;
    }

    static void alignBuildCountMessage(Map<String, Object> response) {
        if (response == null) {
            return;
        }
        int buildCount = objectMaps(response.get("builds")).size();
        String message = text(response.get("message"));
        if (buildCount == 0 || message == null || message.isBlank()) {
            return;
        }
        String aligned = RECOMMENDATION_COUNT_AFTER_LABEL.matcher(message)
                .replaceAll(match -> match.group(1) + buildCount + "개");
        aligned = RECOMMENDATION_COUNT_BEFORE_LABEL.matcher(aligned)
                .replaceAll(match -> buildCount + "개의 " + match.group(1));
        response.put("message", aligned);
    }

    private Map<String, Object> shoppingSupportGuidanceResponse(
            String message,
        Map<String, Object> structuredSupportIntent
    ) {
        String requestedCategory = text(structuredSupportIntent.get("symptomCategory"));
        String symptomCategory = requestedCategory != null && SUPPORT_SYMPTOM_CATEGORIES.contains(requestedCategory)
                ? requestedCategory
                : inferSupportSymptomCategory(message);
        SupportGuidanceProfile profile = supportGuidanceProfile(symptomCategory);
        Map<String, Object> response = fastResponse(
                "GENERAL",
                profile.message(),
                List.of()
        );
        response.put("simulation", null);
        response.put("supportGuidance", MockData.map(
                "type", "PC_AGENT_DIAGNOSTIC_ENTRY",
                "scope", "PRE_DIAGNOSIS",
                "symptomCategory", symptomCategory,
                "title", profile.title(),
                "summary", profile.summary(),
                "possibleCauses", profile.possibleCauses(),
                "beforeDiagnosisChecks", profile.beforeDiagnosisChecks(),
                "agentRecommendation", profile.agentRecommendation(),
                "actions", List.of(
                        Map.of("type", "DOWNLOAD_PC_AGENT", "label", "PC Agent 다운로드"),
                        Map.of("type", "OPEN_SUPPORT_NEW", "label", "AS 접수 화면 보기", "route", "/support/new")
                ),
                "disclaimer", "표시된 원인은 입력한 증상만으로 예상한 일반적인 가능성이며 진단 결과가 아닙니다. 원인 확인과 지원 방식 판단은 PC Agent 진단 AI가 수집 동의된 로그를 확인한 뒤 수행합니다."
        ));
        return response;
    }

    private static String inferSupportSymptomCategory(String message) {
        String normalized = normalizeCommand(message);
        if (containsAnyNormalized(normalized, "부팅이안", "부팅안", "안켜", "전원이안", "전원안들")) {
            return "BOOT_FAILURE";
        }
        if (containsAnyNormalized(normalized, "갑자기꺼", "자꾸꺼", "재부팅", "전원꺼")) {
            return "POWER_RESTART";
        }
        if (containsAnyNormalized(normalized, "검은화면", "블랙스크린", "블루스크린", "화면멈", "게임하다멈", "게임중멈", "프리징", "먹통", "얼어붙")
                || containsAnyNormalized(normalized, "화면", "게임")
                        && containsAnyNormalized(normalized, "멈춰", "멈춤", "멈춘")) {
            return "DISPLAY_FREEZE";
        }
        // "인터넷이 끊겨"의 끊김을 일반 성능 저하로 먼저 잡지 않도록 도메인 신호를 우선한다.
        if (containsAnyNormalized(normalized, "인터넷", "네트워크")) {
            return "NETWORK";
        }
        if (containsAnyNormalized(normalized, "소리가안", "소리안나", "오디오")) {
            return "AUDIO";
        }
        if (containsAnyNormalized(normalized, "프레임드랍", "버벅", "끊겨", "끊김", "갑자기느려", "느려졌", "튕겨", "튕김", "크래시")) {
            return "PERFORMANCE_STUTTER";
        }
        if (containsAnyNormalized(normalized, "과열", "너무뜨거", "온도가너무", "팬소리")) {
            return "THERMAL_NOISE";
        }
        if (containsAnyNormalized(normalized, "디스크100", "저장공간부족", "ssd", "디스크")) {
            return "STORAGE";
        }
        return "GENERAL";
    }

    private static SupportGuidanceProfile supportGuidanceProfile(String symptomCategory) {
        return switch (symptomCategory) {
            case "DISPLAY_FREEZE" -> new SupportGuidanceProfile(
                    "게임·화면 멈춤 증상",
                    "그래픽 드라이버 충돌, GPU 온도·부하 불안정, 메모리 또는 전원 불안정 등이 원인 후보로 예상됩니다.",
                    "게임·화면 멈춤 증상으로 이해했습니다. 그래픽 드라이버 충돌, GPU 온도·부하 불안정, 메모리 또는 전원 불안정 등이 원인 후보로 예상됩니다. PC Agent를 실행하면 증상 직후 로그로 가능성을 좁힐 수 있습니다.",
                    List.of("그래픽 드라이버 충돌", "GPU 온도 또는 부하 불안정", "메모리 또는 전원 공급 불안정", "게임·응용 프로그램 오류"),
                    List.of("문제가 발생한 게임과 시간을 기록해 주세요.", "재현 직후 PC Agent 진단을 실행해 주세요."),
                    "RECOMMENDED"
            );
            case "POWER_RESTART" -> new SupportGuidanceProfile(
                    "갑작스러운 종료·재부팅",
                    "전원 공급 불안정, 과열 보호, 운영체제 또는 드라이버 오류 등이 원인 후보로 예상됩니다.",
                    "갑작스러운 종료 또는 재부팅 증상으로 이해했습니다. 전원 공급 불안정, 과열 보호, 운영체제 또는 드라이버 오류 등이 원인 후보로 예상됩니다. 부품 교체 전 PC Agent 진단 결과를 먼저 확인해 주세요.",
                    List.of("전원 공급 불안정", "CPU·GPU 과열 보호", "운영체제 또는 드라이버 오류"),
                    List.of("증상이 발생한 시각과 실행 중이던 작업을 기록해 주세요.", "안전하게 부팅된다면 재현 직후 PC Agent 진단을 실행해 주세요."),
                    "RECOMMENDED"
            );
            case "BOOT_FAILURE" -> new SupportGuidanceProfile(
                    "부팅·전원 시작 문제",
                    "전원 연결·공급 문제, 메모리 장착 상태, 저장장치 또는 운영체제 부팅 오류 등이 원인 후보로 예상됩니다.",
                    "부팅 또는 전원 시작 문제로 이해했습니다. 전원 연결·공급 문제, 메모리 장착 상태, 저장장치 또는 운영체제 부팅 오류 등이 원인 후보로 예상됩니다. 반복 전원 조작은 피하고 현재 표시 상태를 남겨 주세요.",
                    List.of("전원 연결 또는 전원 공급 문제", "메모리 장착 상태", "저장장치 또는 운영체제 부팅 오류"),
                    List.of("화면에 표시되는 문구나 LED 상태를 사진으로 남겨 주세요.", "운영체제에 진입할 수 있을 때만 PC Agent를 실행해 주세요."),
                    "RECOMMENDED"
            );
            case "PERFORMANCE_STUTTER" -> new SupportGuidanceProfile(
                    "끊김·성능 저하",
                    "CPU·GPU 온도 상승, 메모리 부족, 저장장치 부하, 드라이버 또는 게임 설정 등이 원인 후보로 예상됩니다.",
                    "끊김 또는 성능 저하 증상으로 이해했습니다. CPU·GPU 온도 상승, 메모리 부족, 저장장치 부하, 드라이버 또는 게임 설정 등이 원인 후보로 예상됩니다. PC Agent 진단으로 발생 시점의 상태를 확인해 주세요.",
                    List.of("CPU·GPU 온도 상승 또는 스로틀링", "메모리 부족", "저장장치 또는 백그라운드 작업 부하", "드라이버 또는 게임 설정"),
                    List.of("문제가 발생하는 게임이나 프로그램과 시점을 기록해 주세요.", "증상 직후 PC Agent 진단을 실행해 주세요."),
                    "RECOMMENDED"
            );
            case "THERMAL_NOISE" -> new SupportGuidanceProfile(
                    "온도·팬 소음",
                    "통풍 저하, 쿨러·팬 상태, 높은 백그라운드 부하 등이 원인 후보로 예상됩니다.",
                    "온도 또는 팬 소음 문제로 이해했습니다. 통풍 저하, 쿨러·팬 상태, 높은 백그라운드 부하 등이 원인 후보로 예상됩니다. 임의로 분해하기 전에 PC Agent 진단으로 센서 기록을 확인해 주세요.",
                    List.of("먼지 또는 케이스 통풍 저하", "쿨러·팬 작동 상태", "높은 백그라운드 부하"),
                    List.of("뜨거운 냄새나 비정상 소음이 심하면 사용을 중지해 주세요.", "안전한 상태에서 PC Agent 진단을 실행해 센서 기록을 확인해 주세요."),
                    "RECOMMENDED"
            );
            case "STORAGE" -> new SupportGuidanceProfile(
                    "저장장치·공간 문제",
                    "저장공간 부족, 백그라운드 디스크 작업, 파일 시스템 또는 저장장치 상태 문제가 원인 후보로 예상됩니다.",
                    "저장장치 또는 공간 문제로 이해했습니다. 저장공간 부족, 백그라운드 디스크 작업, 파일 시스템 또는 저장장치 상태 문제가 원인 후보로 예상됩니다. 중요한 파일을 먼저 보호해 주세요.",
                    List.of("저장공간 부족", "백그라운드 디스크 작업", "파일 시스템 또는 저장장치 상태 문제"),
                    List.of("중요한 파일은 가능한 경우 별도 위치에 백업해 주세요.", "PC Agent 진단을 실행해 저장장치 상태를 확인해 주세요."),
                    "RECOMMENDED"
            );
            case "NETWORK" -> new SupportGuidanceProfile(
                    "네트워크 연결 문제",
                    "네트워크 어댑터·드라이버, 공유기·무선 신호, 케이블 또는 회선 문제가 원인 후보로 예상됩니다.",
                    "네트워크 연결 문제로 이해했습니다. 네트워크 어댑터·드라이버, 공유기·무선 신호, 케이블 또는 회선 문제가 원인 후보로 예상됩니다. 다른 기기의 연결 상태도 함께 확인해 주세요.",
                    List.of("네트워크 어댑터 또는 드라이버", "공유기 또는 무선 신호", "케이블 또는 인터넷 회선"),
                    List.of("같은 네트워크의 다른 기기에서도 끊기는지 확인해 주세요.", "PC에서만 반복되면 PC Agent 진단을 실행해 주세요."),
                    "OPTIONAL"
            );
            case "AUDIO" -> new SupportGuidanceProfile(
                    "소리·오디오 문제",
                    "출력 장치 선택, 오디오 드라이버, 케이블·포트 연결 문제가 원인 후보로 예상됩니다.",
                    "소리 또는 오디오 문제로 이해했습니다. 출력 장치 선택, 오디오 드라이버, 케이블·포트 연결 문제가 원인 후보로 예상됩니다. 기본 연결을 확인한 뒤 반복되면 PC Agent 진단을 실행해 주세요.",
                    List.of("출력 장치 선택", "오디오 드라이버", "케이블 또는 포트 연결"),
                    List.of("윈도우 출력 장치가 올바르게 선택됐는지 확인해 주세요.", "계속되면 PC Agent 진단을 실행해 주세요."),
                    "OPTIONAL"
            );
            default -> new SupportGuidanceProfile(
                    "PC 상태 확인",
                    "드라이버·소프트웨어, 온도·부하, 메모리·저장장치, 전원 상태 등이 일반적인 원인 후보입니다.",
                    "PC 이상 증상으로 이해했습니다. 드라이버·소프트웨어, 온도·부하, 메모리·저장장치, 전원 상태 등이 원인 후보로 예상됩니다. PC Agent 진단 AI가 동의된 로그로 가능성을 좁힐 수 있습니다.",
                    List.of("드라이버 또는 소프트웨어 오류", "온도 또는 시스템 부하", "메모리 또는 저장장치 상태", "전원 공급 상태"),
                    List.of("증상과 발생 시점을 기록해 주세요.", "재현 가능하면 직후에 PC Agent 진단을 실행해 주세요."),
                    "RECOMMENDED"
            );
        };
    }

    // ── 범용 역제안 계층 ─────────────────────────────────────────────
    // 원칙: 이해는 LLM(제약 구조화), 사실은 DB(최저가·최소 구성가), 문구별 분기 없음.

    // LLM 프롬프트에 주입할 서버 계산 사실. 전부 결정적 질의 — LLM은 이 숫자를 되묻지 않고 그대로 쓴다.
    private Map<String, Object> buildServerFacts(
            String message,
            BudgetIntent rawBudgetIntent,
            Map<String, Object> body,
            CurrentUserService.CurrentUser user
    ) {
        Map<String, Object> facts = new LinkedHashMap<>();
        if (rawBudgetIntent != null && rawBudgetIntent.hasBudget()) {
            facts.put("budgetWon", rawBudgetIntent.budget());
            facts.put("budgetMode", rawBudgetIntent.mode());
        }
        int minimum = minimumBuildTotal();
        if (minimum > 0) {
            facts.put("minimumBuildTotalWon", minimum);
        }
        List<String> inferredUsage = inferUsageTags(message);
        if (BuildChatFeasibilityService.requiresGpu(inferredUsage)) {
            int usageMinimum = feasibilityService.usageMinimumTotal(inferredUsage);
            if (usageMinimum > 0) {
                facts.put("usageMinimumTotalWon", usageMinimum);
                facts.put("usageTags", inferredUsage);
            }
        }
        BuildChatFeasibilityService.SpecConstraint constraint = mergedPartConstraint(Map.of(), message);
        if (constraint != null && (constraint.hasSpec() || constraint.maxBudgetWon() != null)) {
            Map<String, Object> partFacts = new LinkedHashMap<>();
            partFacts.put("category", constraint.category());
            partFacts.put("minCapacityGb", constraint.minCapacityGb());
            partFacts.put("minWattageW", constraint.minWattageW());
            partFacts.put("maxBudgetWon", constraint.maxBudgetWon());
            compatibleRecommendationOptions(
                    user,
                    constraint.category(),
                    feasibilityService.meetingCheapestFirst(constraint, PART_RECOMMENDATION_CANDIDATE_POOL_SIZE),
                    1).stream().findFirst().ifPresent(option -> partFacts.put(
                    "cheapestMeeting", MockData.map("name", option.name(), "priceWon", option.unitPrice())));
            if (constraint.maxBudgetWon() != null) {
                compatibleRecommendationOptions(
                        user,
                        constraint.category(),
                        feasibilityService.bestUnderBudget(
                                constraint.category(),
                                constraint.maxBudgetWon(),
                                constraint.effectiveQuantity(),
                                PART_RECOMMENDATION_CANDIDATE_POOL_SIZE),
                        1).stream().findFirst().ifPresent(option -> partFacts.put(
                                "bestWithinBudget", MockData.map("name", option.name(), "priceWon", option.unitPrice())));
            }
            facts.put("partConstraintFacts", partFacts);
        }
        List<Map<String, Object>> draftItems = objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"));
        if (!draftItems.isEmpty()) {
            int draftTotal = 0;
            for (Map<String, Object> item : draftItems) {
                Integer price = firstNumber(item.get("lineTotal"), item.get("currentPrice"), item.get("price"));
                draftTotal += price == null ? 0 : Math.max(0, price);
            }
            facts.put("currentDraftSummary", MockData.map("itemCount", draftItems.size(), "totalWon", draftTotal));
        }
        return facts;
    }

    // 다중부품 감액 요청("총액 줄여줘", "800만원 이하로 맞춰줘"): 드래프트 있음 + 예산 목표 +
    // 특정 단일 부품 미지목 + 현재 총액 > 목표. draftEdit는 한 번에 한 카테고리만 다루므로, 감액
    // 우선순위(라인총액 상위)를 텍스트로 안내하고 카테고리 교체 칩을 준다 — 재진입 시 detectPartCategory가
    // 카테고리를 잡아 기존 단일 modify→미리보기 루프로 닫힌다. 방향은 어휘가 아니라 숫자로 판정한다.
    private Optional<Map<String, Object>> multiPartReductionResponse(
            Map<String, Object> body, String message, BudgetIntent rawBudgetIntent) {
        if (rawBudgetIntent == null || !rawBudgetIntent.hasBudget() || detectPartCategory(message) != null) {
            return Optional.empty();
        }
        List<Map<String, Object>> draftItems = objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"));
        if (draftItems.isEmpty()) {
            return Optional.empty();
        }
        int target = rawBudgetIntent.budget();
        int draftTotal = 0;
        for (Map<String, Object> item : draftItems) {
            draftTotal += lineTotalWon(item);
        }
        // 현재 총액이 목표를 넘을 때만 감액 안내로 흘린다(넘지 않으면 이 결정경로를 타지 않고 LLM 경로로).
        if (draftTotal <= target) {
            return Optional.empty();
        }
        List<Map<String, Object>> byLineTotalDesc = new ArrayList<>(draftItems);
        byLineTotalDesc.sort((left, right) -> Integer.compare(lineTotalWon(right), lineTotalWon(left)));
        List<Map<String, Object>> topSpenders = byLineTotalDesc.stream()
                .filter(item -> text(item.get("category")) != null)
                .limit(3)
                .toList();
        if (topSpenders.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder textBuilder = new StringBuilder()
                .append("현재 견적은 ").append(formatBudgetLabel(draftTotal))
                .append("이고 목표 ").append(formatBudgetLabel(target)).append("까지 약 ")
                .append(formatBudgetLabel(draftTotal - target))
                .append(" 감액이 필요합니다. 챗봇은 한 번에 한 부품씩 교체를 도와드려요. 가장 비싼 ");
        for (int index = 0; index < topSpenders.size(); index += 1) {
            Map<String, Object> item = topSpenders.get(index);
            String category = text(item.get("category"));
            if (index > 0) {
                textBuilder.append(", ");
            }
            textBuilder.append(CATEGORY_LABELS.getOrDefault(category, category))
                    .append("(").append(formatBudgetLabel(lineTotalWon(item))).append(")");
        }
        textBuilder.append("부터 줄이면 효과가 큽니다.");
        Map<String, Object> response = fastResponse("GENERAL", textBuilder.toString(), List.of());
        response.put("quickReplies", topSpenders.stream()
                .map(item -> {
                    String category = text(item.get("category"));
                    return CATEGORY_LABELS.getOrDefault(category, category) + " 더 저렴한 걸로 바꿔줘";
                })
                .toList());
        return Optional.of(response);
    }

    private static int lineTotalWon(Map<String, Object> item) {
        Integer price = firstNumber(item.get("lineTotal"), item.get("currentPrice"), item.get("price"));
        return price == null ? 0 : Math.max(0, price);
    }

    // LLM까지 불가한 경우의 우아한 거절 — dead-end 문구 대신 바로 눌러볼 기능 칩을 함께 준다.
    private Map<String, Object> gracefulRefusalResponse(String category) {
        String categoryPrefix = category == null ? "" : categoryLabel(category) + " 요청을 지금 처리하지 못했어요. ";
        Map<String, Object> response = fastResponse(
                "GENERAL",
                categoryPrefix + "예산 견적 추천, 현재 견적 완성, 부품 교체 비교는 바로 도와드릴 수 있습니다. 아래 예시를 눌러 다시 시도해 보세요.",
                List.of("UNSUPPORTED_INTENT")
        );
        List<String> quickReplies = new ArrayList<>();
        if (category != null) {
            quickReplies.add(categoryLabel(category) + " 추천 조건 다시 알려줘");
        }
        quickReplies.addAll(FEATURE_GUIDE_QUICK_REPLIES);
        response.put("quickReplies", distinct(quickReplies));
        return response;
    }

    // 속성 요청(수랭/PCIe 세대/통풍)에 대한 오너 확정 산출물: 드래프트에 같은 카테고리 부품이 있으면
    // "현재 부품 vs 속성 충족 최적 후보 1개"의 기존 1:1 시뮬 스펙비교 카드(simulationPayload)를 재사용한다.
    // 드래프트에 그 카테고리가 없거나 후보가 없으면 손대지 않고 역제안(후보 나열)이 이어받는다.
    // "이해는 LLM이, 사실은 DB가": 속성은 LLM이 구조화했고 후보 조회는 feasibilityService(DB)가 한다.
    private void applyAttributeSimulationCard(
            Map<String, Object> response,
            AiChatEngineResponse engineResponse,
            String message,
            Map<String, Object> body
    ) {
        // 이미 조합·시뮬 카드가 있으면 개입하지 않는다(LLM/미리보기 결과를 덮지 않기 위해).
        if (response.get("simulation") != null || !objectMaps(response.get("builds")).isEmpty()) {
            return;
        }
        // "수랭 쿨러 추천해줘"처럼 명시적으로 후보를 요구한 문장은 목록 추천으로 유지한다.
        // 속성 1:1 비교 카드는 "바꾸면/차이" 같은 가정형 요청에만 적용한다.
        if (containsAnyNormalized(normalizeCommand(message), "추천", "골라", "후보")) {
            return;
        }
        BuildChatFeasibilityService.SpecConstraint constraint =
                mergedPartConstraint(objectMap(engineResponse.parsedContext().get("partConstraint")), message);
        // 속성(냉각방식·PCIe 세대·통풍)이 지정된 요청만 1:1 카드 대상 — 순수 수치/예산은 역제안이 담당.
        if (constraint == null || !constraint.hasAttribute()) {
            return;
        }
        List<Map<String, Object>> draftItems = objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"));
        Map<String, Object> currentItem = findDraftItem(draftItems, constraint.category(), message);
        if (currentItem.isEmpty()) {
            return; // 비교 대상 없음 → 후보 나열 폴백에 맡긴다.
        }
        // 속성을 충족하는 최적(최저가) 후보 1개를 실데이터로 조회.
        Optional<BuildChatFeasibilityService.PartOption> best =
                feasibilityService.meetingCheapestFirst(constraint, 1).stream().findFirst();
        if (best.isEmpty()) {
            return; // 속성 충족 후보 없음 → 역제안 폴백에 맡긴다.
        }
        PartCandidate currentPart = draftPartCandidate(currentItem);
        PartCandidate target;
        try {
            target = partByPublicId(best.get().partId());
        } catch (RuntimeException ignored) {
            return; // 카탈로그 조회 실패 → 역제안 폴백에 맡긴다.
        }
        // 현재 부품이 이미 후보와 동일하면 비교가 무의미 — 후보 나열/설명 폴백에 맡긴다.
        if (target.publicId() != null && target.publicId().equals(currentPart.publicId())) {
            return;
        }
        List<String> warnings = new ArrayList<>();
        List<PartCandidate> previewParts = simulationPreviewParts(draftItems, target);
        List<Map<String, Object>> toolResults = toolResults(previewParts, totalPrice(previewParts), warnings);
        if (hasBlockingToolFailure(toolResults)) {
            warnings.add("현재 견적 기준 호환성 문제가 있을 수 있어 실제 교체 전 케이스/파워/소켓 조건을 확인해야 합니다.");
        }
        BenchmarkSnapshot currentBenchmark = latestBenchmark(currentPart).orElse(null);
        BenchmarkSnapshot targetBenchmark = latestBenchmark(target).orElse(null);
        Map<String, Object> simulation = simulationPayload(
                constraint.category(), currentPart, target, currentBenchmark, targetBenchmark, List.of(), List.of(), warnings);
        response.put("answerType", "GENERAL");
        response.put("message", simulationSummary(constraint.category(), currentPart, target, false));
        response.put("simulation", simulation);
        response.put("warnings", distinct(warnings));
        // 카드가 주인공이므로 역제안·에코가 덧붙일 칩은 비운다.
        response.remove("quickReplies");
    }

    // 단일 부품 요청(예: "램 32기가 20만원", "10만원짜리 램") — 실데이터로 추천을 나열하거나,
    // 불가능하면 부족액·근접 대안을 역제안한다. LLM 제약이 비어도 정규식 폴백으로 대표 패턴을 보강한다.
    private void applyPartConstraintCounterProposal(
            Map<String, Object> response,
            AiChatEngineResponse engineResponse,
            String message,
            Map<String, Object> body,
            CurrentUserService.CurrentUser user
    ) {
        // 속성 1:1 카드가 이미 답을 냈으면 그 결과(카드·메시지)를 덮지 않는다.
        if (response.get("simulation") != null) {
            return;
        }
        if (stringList(objectMap(response.get("clarification")).get("missingSlots")).contains("partSelection")) {
            return;
        }
        // PART_RECOMMEND 외에도, 변경(BUILD_MODIFY)이나 되묻기(ASK_FOLLOW_UP)로 분류됐지만 카드가 못
        // 만들어진 부품 제약 요청("램 32기가 20만원으로 바꿔줘")은 여기서 역제안으로 이어받는다 —
        // 엔진 캔드 문구("후보를 찾지 못했습니다")가 dead-end로 새는 것을 막는다.
        boolean eligible = engineResponse.intent() == AiChatIntent.PART_RECOMMEND
                || ((engineResponse.intent() == AiChatIntent.BUILD_MODIFY || engineResponse.intent() == AiChatIntent.ASK_FOLLOW_UP)
                        && objectMaps(response.get("builds")).isEmpty());
        if (!eligible) {
            return;
        }
        BuildChatFeasibilityService.SpecConstraint constraint =
                mergedPartConstraint(objectMap(engineResponse.parsedContext().get("partConstraint")), message);
        if (constraint == null) {
            return;
        }
        int quantity = constraint.effectiveQuantity();
        String categoryLabel = CATEGORY_LABELS.getOrDefault(constraint.category(), constraint.category());
        String specSummary = specSummary(constraint);
        List<String> warnings = new ArrayList<>(stringList(response.get("warnings")));

        ExplicitPartSelection explicitSelection = explicitPartSelection(constraint.category(), message);
        if (explicitSelection != null) {
            List<BuildChatFeasibilityService.PartOption> modelOptions =
                    feasibilityService.matchingSelection(
                            constraint.category(), explicitSelection.gpuClass(), explicitSelection.modelOrVendorToken(),
                            PART_RECOMMENDATION_CANDIDATE_POOL_SIZE);
            List<BuildChatFeasibilityService.PartOption> rawOptions = modelOptions;
            if (constraint.hasSpec()) {
                Set<String> specMatchedIds = feasibilityService
                        .meetingCheapestFirst(constraint, PART_RECOMMENDATION_CANDIDATE_POOL_SIZE).stream()
                        .map(BuildChatFeasibilityService.PartOption::partId)
                        .collect(java.util.stream.Collectors.toSet());
                rawOptions = modelOptions.stream()
                        .filter(option -> specMatchedIds.contains(option.partId()))
                        .toList();
            }
            CompatibleRecommendationSelection compatibilitySelection = compatibleRecommendationSelection(
                    user,
                    constraint.category(),
                    rawOptions,
                    PART_RECOMMENDATION_LIMIT);
            List<BuildChatFeasibilityService.PartOption> compatibleOptions = compatibilitySelection.options();
            response.put("answerType", "PART");
            if (modelOptions.isEmpty()) {
                response.put("message", "요청하신 " + explicitSelection.label() + " 조건을 만족하는 "
                        + categoryLabel + " 부품을 내부 자산에서 찾지 못했습니다.");
                warnings.add("PART_CONSTRAINT_NOT_FOUND");
                response.put("warnings", distinct(warnings));
                response.put("quickReplies", List.of(categoryLabel + " 조건을 넓혀서 추천해줘"));
                return;
            }
            if (rawOptions.isEmpty()) {
                response.put("message", "요청하신 " + explicitSelection.label() + " 상품은 있지만 함께 지정한 "
                        + specSummary + " 조건을 만족하지 않습니다. 모델 또는 세부 조건을 조정해 주세요.");
                warnings.add("PART_CONSTRAINT_NOT_FOUND");
                response.put("warnings", distinct(warnings));
                response.put("quickReplies", List.of(categoryLabel + " 조건을 넓혀서 추천해줘"));
                return;
            }
            Set<String> rawOptionIds = rawOptions.stream()
                    .map(BuildChatFeasibilityService.PartOption::partId)
                    .collect(java.util.stream.Collectors.toSet());
            if (!rawOptionIds.isEmpty() && compatibilitySelection.alreadySelectedIds().containsAll(rawOptionIds)) {
                response.put("message", "요청하신 " + explicitSelection.label() + " 상품은 이미 현재 견적에 선택되어 있습니다. "
                        + "다른 " + categoryLabel + " 후보가 필요하면 조건을 넓혀 주세요.");
                response.put("warnings", distinct(warnings));
                response.put("quickReplies", List.of(categoryLabel + " 다른 후보 추천해줘"));
                return;
            }
            if (compatibleOptions.isEmpty()) {
                response.put("message", "요청하신 " + explicitSelection.label() + " 상품은 내부 자산에 있지만 "
                        + "현재 견적에 추가하거나 교체하면 호환성 검사를 통과하지 못해 추천에서 제외했습니다.");
                warnings.add("PART_CONSTRAINT_NOT_FOUND");
                response.put("warnings", distinct(warnings));
                response.put("quickReplies", compatibilityRecoveryQuickReplies(constraint.category()));
                response.remove("quickReplyCommands");
                return;
            }
            Integer explicitBudget = constraint.maxBudgetWon();
            List<BuildChatFeasibilityService.PartOption> options = explicitBudget == null
                    ? compatibleOptions
                    : compatibleOptions.stream()
                            .filter(option -> option.unitPrice() * quantity <= explicitBudget)
                            .toList();
            if (options.isEmpty()) {
                BuildChatFeasibilityService.PartOption cheapestCompatible = compatibleOptions.get(0);
                int required = cheapestCompatible.unitPrice() * quantity;
                response.put("message", formatBudgetLabel(explicitBudget) + " 이내 " + explicitSelection.label()
                        + " " + categoryLabel + " 조건은 어렵습니다. 현재 견적과 호환되는 최저가는 "
                        + cheapestCompatible.name() + " " + String.format("%,d원", required) + "이며, 약 "
                        + String.format("%,d원", required - explicitBudget) + " 더 필요합니다.");
                warnings.add("PART_BUDGET_SHORTFALL");
                response.put("warnings", distinct(warnings));
                response.put("quickReplies", List.of(
                        explicitSelection.label() + " 조건을 유지하고 예산을 높여 추천해줘",
                        categoryLabel + " 조건을 넓혀서 추천해줘"));
            } else {
                String budgetPrefix = explicitBudget == null ? "" : formatBudgetLabel(explicitBudget) + " 이내 ";
                response.put("message", explicitSelection.label() + " 조건을 만족하는 " + categoryLabel
                        + " " + budgetPrefix + "추천 TOP" + options.size() + "입니다. " + topListText(options)
                        + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요.");
                setPartRecommendationQuickReplies(response, constraint.category(), options);
            }
            return;
        }

        // 수치 스펙·예산 어느 쪽으로도 구조화되지 않은 제약(색상·소음·규격 등) — 특정 키워드를
        // 해석하지 않고, 해당 카테고리 TOP3를 예산 무제한으로 나열해 다음 행동을 남기는 범용 폴백.
        // 카드·칩이 이미 있으면 개입하지 않는다(LLM이 만든 결과를 덮지 않기 위해).
        if (!constraint.hasSpec() && constraint.maxBudgetWon() == null) {
            if (!objectMaps(response.get("builds")).isEmpty()) {
                return;
            }
            LinkedHashSet<String> draftPartKeys = new LinkedHashSet<>();
            for (Map<String, Object> item : objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"))) {
                draftPartKeys.add(text(item.get("partId")));
                draftPartKeys.add(text(item.get("name")));
            }
            String normalizedMessage = normalizeCommand(message);
            boolean valueFocused = containsAnyNormalized(normalizedMessage, "가성비", "가격대비");
            boolean lowestPriceFocused = containsAnyNormalized(
                    normalizedMessage, "최저가", "가장싼", "제일싼", "저렴한", "가격낮은");
            List<BuildChatFeasibilityService.PartOption> orderedOptions = valueFocused
                    ? feasibilityService.bestValueFirst(
                            constraint.category(), PART_RECOMMENDATION_CANDIDATE_POOL_SIZE)
                    : lowestPriceFocused
                            ? feasibilityService.meetingCheapestFirst(
                                    constraint, PART_RECOMMENDATION_CANDIDATE_POOL_SIZE)
                            : feasibilityService.bestUnderBudget(
                                    constraint.category(), Integer.MAX_VALUE, quantity,
                                    PART_RECOMMENDATION_CANDIDATE_POOL_SIZE);
            List<BuildChatFeasibilityService.PartOption> rawOptions = orderedOptions.stream()
                            .filter(option -> !draftPartKeys.contains(option.partId()) && !draftPartKeys.contains(option.name()))
                            .toList();
            List<BuildChatFeasibilityService.PartOption> options = compatibleRecommendationOptions(
                    user, constraint.category(), rawOptions, PART_RECOMMENDATION_LIMIT);
            if (options.isEmpty()) {
                if (rawOptions.isEmpty()) {
                    return;
                }
                response.put("answerType", "PART");
                response.put("message", "현재 견적과 호환되는 " + categoryLabel
                        + " 추천 후보를 찾지 못했습니다. 장착 중인 부품 조건을 먼저 확인해 주세요.");
                warnings.add("PART_CONSTRAINT_NOT_FOUND");
                response.put("warnings", distinct(warnings));
                response.put("quickReplies", List.of("현재 견적의 호환 문제 설명해줘"));
                return;
            }
            boolean performanceFocused = containsAnyNormalized(
                    normalizedMessage, "고성능", "최상급", "최고급", "하이엔드", "끝판왕");
            boolean objectiveValueMetric = Set.of("CPU", "GPU", "RAM", "STORAGE", "PSU")
                    .contains(constraint.category());
            String listing = valueFocused
                    ? (objectiveValueMetric
                            ? "가성비 요청을 기준으로 가격 대비 확인 가능한 성능·용량·정격 수치가 좋은 "
                            : "가성비 요청을 기준으로 가격 부담이 낮고 자동 검증을 통과한 ")
                            + categoryLabel + " 후보 TOP" + options.size() + "입니다. "
                            + topListText(options) + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요."
                    : lowestPriceFocused
                            ? "가격이 낮은 순으로 현재 견적에서 자동 검증을 통과한 " + categoryLabel
                                    + " 후보 TOP" + options.size() + "입니다. " + topListText(options)
                                    + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요."
                    : performanceFocused
                    ? "고성능 요청을 기준으로 현재 견적에서 자동 검증을 통과한 " + categoryLabel + " 후보 TOP"
                            + options.size() + "입니다. 상위 후보라도 현재 조합에서 장착·전력·호환 검사를 통과하지 못하면 제외했습니다. "
                            + topListText(options) + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요."
                    : "추가 조건이 없어 현재 견적과 호환되는 " + categoryLabel + " 대표 후보 TOP"
                            + options.size() + "를 보여드립니다. " + topListText(options)
                            + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요.";
            response.put("message", listing);
            setPartRecommendationQuickReplies(response, constraint.category(), options);
            return;
        }

        // A. 예산만 명시("10만원짜리 램") — 예산 내 최상 스펙 TOP3를 나열하고 담기 칩을 준다.
        if (!constraint.hasSpec()) {
            int budget = constraint.maxBudgetWon();
            List<BuildChatFeasibilityService.PartOption> options = compatibleRecommendationOptions(
                    user,
                    constraint.category(),
                    feasibilityService.bestUnderBudget(constraint.category(), budget, quantity,
                            PART_RECOMMENDATION_CANDIDATE_POOL_SIZE),
                    PART_RECOMMENDATION_LIMIT);
            if (options.isEmpty()) {
                Optional<BuildChatFeasibilityService.PartOption> cheapestAny = compatibleRecommendationOptions(
                        user,
                        constraint.category(),
                        feasibilityService.meetingCheapestFirst(new BuildChatFeasibilityService.SpecConstraint(
                                constraint.category(), null, null, null, quantity, null),
                                PART_RECOMMENDATION_CANDIDATE_POOL_SIZE),
                        1).stream().findFirst();
                StringBuilder textBuilder = new StringBuilder()
                        .append(formatBudgetLabel(budget)).append(" 이내 ").append(categoryLabel)
                        .append(" 부품을 내부 자산에서 찾지 못했습니다.");
                cheapestAny.ifPresent(any -> textBuilder.append(" 보유 최저가는 ").append(any.name())
                        .append(" ").append(String.format("%,d원", any.unitPrice())).append("입니다."));
                response.put("message", textBuilder.toString());
                warnings.add("PART_CONSTRAINT_NOT_FOUND");
                response.put("warnings", distinct(warnings));
                response.put("quickReplies", List.of(categoryLabel + " 최저가로 추천해줘"));
            } else {
                response.put("message", formatBudgetLabel(budget) + " 이내 " + categoryLabel
                        + " 추천 TOP" + options.size() + "입니다. " + topListText(options)
                        + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요.");
                setPartRecommendationQuickReplies(response, constraint.category(), options);
            }
            return;
        }

        List<BuildChatFeasibilityService.PartOption> rawMatchingOptions =
                feasibilityService.meetingCheapestFirst(constraint, PART_RECOMMENDATION_CANDIDATE_POOL_SIZE);
        Optional<BuildChatFeasibilityService.PartOption> cheapest = compatibleRecommendationOptions(
                user,
                constraint.category(),
                rawMatchingOptions,
                20).stream().findFirst();
        // B. 스펙 자체를 보유하지 않음 — 근접 대안(보유 최고 스펙)을 실데이터로 역제안.
        if (cheapest.isEmpty()) {
            if (!rawMatchingOptions.isEmpty() && user != null && partCompatibleCandidateService != null) {
                response.put("message", "요청 조건을 만족하는 " + categoryLabel
                        + " 상품은 있지만 현재 견적에 추가하거나 교체하면 호환성 검사를 통과하지 못합니다. "
                        + "장착 중인 부품이나 슬롯 상태를 먼저 조정해 주세요.");
                warnings.add("PART_CONSTRAINT_NOT_FOUND");
                response.put("warnings", distinct(warnings));
                response.put("quickReplies", compatibilityRecoveryQuickReplies(constraint.category()));
                response.remove("quickReplyCommands");
                return;
            }
            StringBuilder textBuilder = new StringBuilder()
                    .append("요청하신 조건(").append(specSummary).append(")을 만족하는 ")
                    .append(categoryLabel).append(" 부품을 내부 자산에서 찾지 못했습니다.");
            int alternativeBudget = constraint.maxBudgetWon() != null
                    ? constraint.maxBudgetWon()
                    : Integer.MAX_VALUE;
            compatibleRecommendationOptions(
                    user,
                    constraint.category(),
                    feasibilityService.bestUnderBudget(constraint.category(), alternativeBudget, quantity,
                            PART_RECOMMENDATION_CANDIDATE_POOL_SIZE),
                    1).stream().findFirst().ifPresent(alt -> {
                String altSpec = altSpecSummary(constraint.category(), alt);
                textBuilder.append(" 보유 자산 중에서는 ");
                if (!altSpec.isBlank()) {
                    textBuilder.append(altSpec).append(" ");
                }
                textBuilder.append(alt.name()).append("(").append(String.format("%,d원", alt.unitPrice()))
                        .append(")이 가장 근접한 선택입니다.");
            });
            response.put("message", textBuilder.toString());
            warnings.add("PART_CONSTRAINT_NOT_FOUND");
            response.put("warnings", distinct(warnings));
            response.put("quickReplies", counterProposalQuickReplies(constraint, categoryLabel, specSummary));
            return;
        }
        BuildChatFeasibilityService.PartOption option = cheapest.get();
        int total = option.unitPrice() * quantity;
        Integer budget = constraint.maxBudgetWon();
        // C. 스펙은 있으나 예산 부족 — 부족액 + 예산 내 대안 역제안.
        if (budget != null && total > budget) {
            StringBuilder textBuilder = new StringBuilder()
                    .append(formatBudgetLabel(budget)).append("으로 ")
                    .append(specSummary).append(" ").append(categoryLabel);
            if (quantity > 1) {
                textBuilder.append(" ").append(quantity).append("개");
            }
            textBuilder.append(" 구성은 어렵습니다. 최저가는 ").append(option.name()).append(" ")
                    .append(String.format("%,d원", option.unitPrice()));
            if (quantity > 1) {
                textBuilder.append("(").append(quantity).append("개 ").append(String.format("%,d원", total)).append(")");
            }
            textBuilder.append("이며, 약 ").append(String.format("%,d원", total - budget)).append(" 더 필요합니다.");
            appendBudgetAlternative(textBuilder, constraint, quantity, user);
            response.put("message", textBuilder.toString());
            warnings.add("PART_BUDGET_SHORTFALL");
            response.put("warnings", distinct(warnings));
            response.put("quickReplies", counterProposalQuickReplies(constraint, categoryLabel, specSummary));
            return;
        }
        // D. 충족 가능 — 실데이터 TOP3 나열 + 담기 칩 (대화가 결과 없이 끝나지 않게).
        List<BuildChatFeasibilityService.PartOption> top = compatibleRecommendationOptions(
                user,
                constraint.category(),
                feasibilityService.meetingCheapestFirst(constraint, PART_RECOMMENDATION_CANDIDATE_POOL_SIZE),
                PART_RECOMMENDATION_LIMIT);
        if (top.isEmpty()) {
            response.put("message", "요청 조건을 만족하면서 현재 견적과 호환되는 " + categoryLabel
                    + " 후보를 찾지 못했습니다. 현재 구성의 호환 조건을 먼저 확인해 주세요.");
            warnings.add("PART_CONSTRAINT_NOT_FOUND");
            response.put("warnings", distinct(warnings));
            response.put("quickReplies", List.of("현재 견적의 호환 문제 설명해줘"));
            response.remove("quickReplyCommands");
            return;
        }
        response.put("message", "조건(" + specSummary + ")을 만족하는 " + categoryLabel
                + " 추천 TOP" + top.size() + "입니다. " + topListText(top)
                + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요.");
        setPartRecommendationQuickReplies(response, constraint.category(), top);
    }

    /**
     * 종합 점수 설명에서 이어지는 케이스 개선 요청은 일반 TOP3가 아니라 현재 견적을 후보별로 다시
     * 평가한다. 현재 케이스 또는 장착 여유가 같은 파생 상품을 다시 추천하는 순환을 막고, 실제 종합
     * 점수 상승과 Tool 검증을 모두 통과한 후보만 사용자에게 보여준다.
     */
    private boolean applyCaseScoreImprovementProposal(
            Map<String, Object> response,
            AiChatEngineResponse engineResponse,
            String message,
            Map<String, Object> body
    ) {
        boolean recommendationIntent = engineResponse.intent() == AiChatIntent.PART_RECOMMEND
                || (engineResponse.intent() == AiChatIntent.BUILD_MODIFY && isExplicitRecommendationRequest(message));
        if (!recommendationIntent) {
            return false;
        }
        BuildChatFeasibilityService.SpecConstraint constraint = mergedPartConstraint(
                objectMap(engineResponse.parsedContext().get("partConstraint")),
                message
        );
        return applyCaseScoreImprovementProposal(response, constraint, message, body);
    }

    private Optional<Map<String, Object>> fastCaseScoreImprovementResponse(
            Map<String, Object> body,
            String message
    ) {
        if (!"CASE".equals(detectPartCategory(message)) || !isExplicitRecommendationRequest(message)) {
            return Optional.empty();
        }
        BuildChatFeasibilityService.SpecConstraint constraint = new BuildChatFeasibilityService.SpecConstraint(
                "CASE",
                null,
                null,
                null,
                1,
                null,
                null,
                null,
                mentionsAirflow(message) ? Boolean.TRUE : null
        );
        if (!isCaseFitImprovementRequest(constraint, message)) {
            return Optional.empty();
        }
        Map<String, Object> response = fastResponse("PART", "", List.of());
        return applyCaseScoreImprovementProposal(response, constraint, message, body)
                ? Optional.of(response)
                : Optional.empty();
    }

    private boolean applyCaseScoreImprovementProposal(
            Map<String, Object> response,
            BuildChatFeasibilityService.SpecConstraint constraint,
            String message,
            Map<String, Object> body
    ) {
        if (constraint == null || !"CASE".equals(constraint.category())
                || !isCaseFitImprovementRequest(constraint, message)) {
            return false;
        }
        List<Map<String, Object>> draftItems = objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"));
        if (draftItems.isEmpty()) {
            return false;
        }

        List<ToolBuildPart> currentParts = draftItems.stream()
                .map(item -> toolPart(draftPartCandidate(item), draftQuantity(item)))
                .toList();
        ToolBuildPart currentCase = currentParts.stream()
                .filter(part -> "CASE".equals(part.category()))
                .findFirst()
                .orElse(null);
        if (currentCase == null) {
            return false;
        }

        BuildEvaluationService.BuildEvaluation currentEvaluation = buildEvaluationService.evaluate(
                currentParts,
                null,
                "CASE",
                "size"
        );
        CaseScoreCap currentCaseCap = caseScoreCap(currentEvaluation.compositeScore());
        boolean airflowRequested = Boolean.TRUE.equals(constraint.airflowFocused()) || mentionsAirflow(message);
        boolean currentAirflowStrong = caseAirflowStrong(currentCase.attributes());
        int currentScore = scoreValue(currentEvaluation.compositeScore());

        // 케이스 관련 cap이 없고 현재 케이스도 통풍형이면 "통풍 부족"이라는 전제가 사실이 아니다.
        // 같은 제품을 다시 추천하지 않고, 현 평가에서는 케이스 때문에 점수가 깎이지 않았음을 알린다.
        if (currentCaseCap == null) {
            if (airflowRequested && currentAirflowStrong) {
                response.put("answerType", "PART");
                response.put("message", "현재 케이스는 통풍형으로 확인되며, 현재 종합 점수도 케이스 통풍 때문에 제한된 상태가 아닙니다. "
                        + "점수 개선 목적이라면 다른 주의 항목을 먼저 확인하는 편이 맞습니다.");
                response.put("builds", List.of());
                response.put("simulation", null);
                response.put("quickReplies", List.of("현재 견적에서 무엇부터 개선해야 하는지 설명해줘"));
                response.remove("quickReplyCommands");
                response.remove("clarification");
                return true;
            }
            CaseFitProfile currentFit = caseFitProfile(currentEvaluation.toolResults());
            if (!currentFit.hasComparableHeadroom()) {
                response.put("answerType", "PART");
                response.put("message", "현재 선택 부품과 케이스의 장착 여유를 비교할 수 있는 검증 수치가 부족합니다. "
                        + "근거 없이 더 넓은 케이스를 추천하지 않았습니다.");
                response.put("builds", List.of());
                response.put("simulation", null);
                response.put("quickReplies", List.of("현재 견적의 케이스 장착 수치 설명해줘"));
                response.remove("quickReplyCommands");
                response.remove("clarification");
                return true;
            }

            List<CaseFitCandidate> fitCandidates = new ArrayList<>();
            for (PartCandidate candidate : pricePartCandidates("CASE", 100)) {
                if (Objects.equals(currentCase.publicId(), candidate.publicId())) {
                    continue;
                }
                if (airflowRequested && !caseAirflowStrong(candidate.attributes())) {
                    continue;
                }
                List<ToolBuildPart> previewParts = new ArrayList<>();
                for (ToolBuildPart part : currentParts) {
                    if (!"CASE".equals(part.category())) {
                        previewParts.add(part);
                    }
                }
                previewParts.add(toolPart(candidate, currentCase.effectiveQuantity()));
                BuildEvaluationService.BuildEvaluation candidateEvaluation = buildEvaluationService.evaluate(
                        previewParts,
                        null,
                        "CASE",
                        "size"
                );
                if (hasBlockingToolFailure(candidateEvaluation.toolResults())) {
                    continue;
                }
                CaseFitProfile candidateFit = caseFitProfile(candidateEvaluation.toolResults());
                if (!candidateFit.improvesWithoutRegression(currentFit)) {
                    continue;
                }
                fitCandidates.add(new CaseFitCandidate(candidate, candidateFit));
            }
            fitCandidates.sort(Comparator
                    .comparingInt((CaseFitCandidate candidate) -> candidate.fit().minimumKnownHeadroom()).reversed()
                    .thenComparing(Comparator.comparingInt(
                            (CaseFitCandidate candidate) -> candidate.fit().totalKnownHeadroom()).reversed())
                    .thenComparingInt(candidate -> candidate.part().price() == null
                            ? Integer.MAX_VALUE
                            : candidate.part().price()));
            List<CaseFitCandidate> topFit = fitCandidates.stream().limit(3).toList();

            response.put("answerType", "PART");
            response.put("builds", List.of());
            response.put("simulation", null);
            response.remove("quickReplyCommands");
            response.remove("clarification");
            if (topFit.isEmpty()) {
                response.put("message", "현재 내부 자산 중 선택된 GPU·쿨러·파워의 알려진 장착 여유가 줄지 않으면서 "
                        + "실제로 더 넓어지고 자동 검증을 통과하는 케이스를 찾지 못했습니다.");
                response.put("quickReplies", List.of("현재 견적의 다른 개선점 설명해줘"));
                return true;
            }
            String evidenceLabel = airflowRequested
                    ? "통풍 근거가 있고 현재 케이스보다 확인 가능한 장착 여유가 넓은"
                    : "현재 케이스보다 확인 가능한 장착 여유가 넓은";
            response.put("message", evidenceLabel + " 케이스 TOP" + topFit.size() + "입니다. "
                    + caseFitImprovementListText(topFit)
                    + " 알려진 GPU·CPU 쿨러·파워 장착 수치가 나빠지는 후보는 제외했습니다.");
            setPartRecommendationQuickReplies(response, "CASE", topFit.stream()
                    .map(candidate -> new BuildChatFeasibilityService.PartOption(
                            candidate.part().publicId(),
                            candidate.part().name(),
                            candidate.part().price(),
                            null,
                            null,
                            null
                    ))
                    .toList());
            return true;
        }

        int currentCaseCapScore = currentCaseCap.maxScore();
        List<CaseImprovementCandidate> candidates = new ArrayList<>();
        for (PartCandidate candidate : pricePartCandidates("CASE", 100)) {
            if (Objects.equals(currentCase.publicId(), candidate.publicId())) {
                continue;
            }
            List<ToolBuildPart> previewParts = new ArrayList<>();
            for (ToolBuildPart part : currentParts) {
                if (!"CASE".equals(part.category())) {
                    previewParts.add(part);
                }
            }
            previewParts.add(toolPart(candidate, currentCase.effectiveQuantity()));
            BuildEvaluationService.BuildEvaluation candidateEvaluation = buildEvaluationService.evaluate(
                    previewParts,
                    null,
                    "CASE",
                    "size"
            );
            if (hasBlockingToolFailure(candidateEvaluation.toolResults())) {
                continue;
            }
            int candidateScore = scoreValue(candidateEvaluation.compositeScore());
            int candidateCaseCapScore = Optional.ofNullable(caseScoreCap(candidateEvaluation.compositeScore()))
                    .map(CaseScoreCap::maxScore)
                    .orElse(1000);
            if (candidateCaseCapScore <= currentCaseCapScore || candidateScore <= currentScore) {
                continue;
            }
            candidates.add(new CaseImprovementCandidate(candidate, candidateScore, candidateCaseCapScore));
        }
        candidates.sort(Comparator
                .comparingInt(CaseImprovementCandidate::score).reversed()
                .thenComparing(Comparator.comparingInt(CaseImprovementCandidate::caseCapScore).reversed())
                .thenComparingInt(candidate -> candidate.part().price() == null ? Integer.MAX_VALUE : candidate.part().price()));
        List<CaseImprovementCandidate> top = candidates.stream().limit(3).toList();

        String correction = airflowRequested && currentAirflowStrong && "LOW_CASE_CLEARANCE".equals(currentCaseCap.code())
                ? "현재 케이스는 통풍형으로 확인되어 통풍 부족으로 감점된 상태가 아닙니다. "
                : "";
        response.put("answerType", "PART");
        response.put("builds", List.of());
        response.put("simulation", null);
        response.remove("clarification");
        response.remove("quickReplyCommands");
        if (top.isEmpty()) {
            response.put("message", correction + "현재 점수 제한 원인은 " + currentCaseCap.reason()
                    + " 현재 내부 자산 중 이 제한을 해소하면서 종합 점수를 실제로 높이고 자동 검증까지 통과하는 케이스는 없습니다.");
            response.put("quickReplies", List.of("현재 견적에서 다른 개선점 설명해줘"));
            return true;
        }

        response.put("message", correction + "현재 점수 제한 원인은 " + currentCaseCap.reason()
                + " 현재 종합 점수 " + currentScore + "점보다 실제로 높아지고 자동 검증을 통과한 케이스 TOP"
                + top.size() + "입니다. " + caseImprovementListText(top)
                + " 원하는 제품을 선택하면 교체 미리보기를 보여드립니다.");
        setPartRecommendationQuickReplies(response, "CASE", top.stream()
                .map(candidate -> new BuildChatFeasibilityService.PartOption(
                        candidate.part().publicId(),
                        candidate.part().name(),
                        candidate.part().price(),
                        null,
                        null,
                        null
                ))
                .toList());
        return true;
    }

    private static boolean isCaseFitImprovementRequest(
            BuildChatFeasibilityService.SpecConstraint constraint,
            String message
    ) {
        if (Boolean.TRUE.equals(constraint.airflowFocused())) {
            return true;
        }
        String compact = firstText(message, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return containsAnyText(compact, "장착여유", "여유있", "공간넉넉", "넉넉한케이스", "통풍", "에어플로우", "airflow");
    }

    private static boolean isExplicitRecommendationRequest(String message) {
        String compact = firstText(message, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        boolean asksForCandidates = containsAnyText(compact, "추천", "후보", "골라", "어떤게좋", "뭐가좋");
        boolean directMutation = containsAnyText(compact, "바꿔줘", "교체해줘", "담아줘", "넣어줘", "적용해줘");
        return asksForCandidates && !directMutation;
    }

    private Optional<Map<String, Object>> deterministicPartRecommendationResponse(
            Map<String, Object> body,
            String message,
            CurrentUserService.CurrentUser user
    ) {
        String category = firstText(detectRecommendationTargetCategory(message), detectPartCategory(message));
        String compact = normalizeCommand(message);
        if (category == null
                || !isExplicitRecommendationRequest(message)
                || isWholeBuildRecommendationContext(compact)) {
            return Optional.empty();
        }
        BuildChatFeasibilityService.SpecConstraint constraint = mergedPartConstraint(Map.of(), message);
        if (constraint == null) {
            return Optional.empty();
        }
        ExplicitPartSelection selection = explicitPartSelection(category, message);
        boolean deterministicOrdering = containsAnyNormalized(
                compact,
                "가성비", "가격대비",
                "최저가", "가장싼", "제일싼", "저렴한", "가격낮은",
                "고성능", "최상급", "최고급", "하이엔드", "끝판왕");
        boolean serverParsable = selection != null
                || constraint.minCapacityGb() != null
                || constraint.minVramGb() != null
                || constraint.minWattageW() != null
                || constraint.maxBudgetWon() != null
                || deterministicOrdering
                || isCurrentBuildFitRecommendation(compact);
        if (!serverParsable) {
            return Optional.empty();
        }
        AiChatEngineResponse deterministic = new AiChatEngineResponse(
                "",
                AiChatIntent.PART_RECOMMEND,
                List.of(),
                List.of(),
                List.of(),
                Map.of("partConstraint", Map.of("category", category)),
                List.of(),
                List.of(),
                null
        );
        Map<String, Object> response = fastResponse("PART", "", List.of());
        applyPartConstraintCounterProposal(response, deterministic, message, body, user);
        if (firstText(text(response.get("message")), "").isBlank()) {
            return Optional.empty();
        }
        return Optional.of(response);
    }

    private static boolean isWholeBuildRecommendationContext(String compact) {
        if (containsAnyNormalized(compact, "pc", "피시", "컴퓨터", "본체", "데스크탑", "데스크톱")) {
            return true;
        }
        boolean currentBuildContext = containsAnyNormalized(
                compact,
                "현재견적", "지금견적", "이견적", "내견적",
                "현재구성", "지금구성", "이구성",
                "현재조합", "지금조합", "이조합", "내조합");
        return !currentBuildContext && containsAnyNormalized(compact, "견적", "조합");
    }

    private static boolean isCurrentBuildFitRecommendation(String compact) {
        boolean currentBuildContext = containsAnyNormalized(
                compact,
                "현재견적", "지금견적", "이견적", "내견적",
                "현재구성", "지금구성", "이구성",
                "현재조합", "지금조합", "이조합", "내조합");
        boolean fitRequest = containsAnyNormalized(
                compact,
                "맞는", "맞춰", "호환", "문제없는", "문제없", "충분", "여유", "어울",
                "장착가능", "통과", "전력");
        return currentBuildContext && fitRequest;
    }

    private static boolean mentionsAirflow(String message) {
        String compact = firstText(message, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return containsAnyText(compact, "통풍", "에어플로우", "airflow");
    }

    private static boolean caseAirflowStrong(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(attributes.get("frontMesh"))
                || Boolean.TRUE.equals(attributes.get("airflowFocus"))
                || firstText(text(attributes.get("airflow")), "").toUpperCase(Locale.ROOT).contains("HIGH");
    }

    private static CaseScoreCap caseScoreCap(Map<String, Object> compositeScore) {
        return objectMaps(compositeScore.get("caps")).stream()
                .filter(cap -> {
                    String code = text(cap.get("code"));
                    return "LOW_CASE_CLEARANCE".equals(code) || "LOW_CASE_AIRFLOW".equals(code);
                })
                .map(cap -> new CaseScoreCap(
                        firstNumber(cap.get("maxScore")) == null ? 1000 : firstNumber(cap.get("maxScore")),
                        text(cap.get("code")),
                        firstText(text(cap.get("reason")), "케이스 장착 여유가 현재 종합 점수를 제한합니다.")
                ))
                .min(Comparator.comparingInt(CaseScoreCap::maxScore))
                .orElse(null);
    }

    private static int scoreValue(Map<String, Object> compositeScore) {
        Integer value = firstNumber(compositeScore.get("score"));
        return value == null ? 0 : value;
    }

    private static int draftQuantity(Map<String, Object> item) {
        Integer quantity = numberValue(item.get("quantity"));
        return quantity == null || quantity < 1 ? 1 : quantity;
    }

    private List<BuildChatFeasibilityService.PartOption> compatibleRecommendationOptions(
            CurrentUserService.CurrentUser user,
            String category,
            List<BuildChatFeasibilityService.PartOption> options,
            int limit
    ) {
        return compatibleRecommendationSelection(user, category, options, limit).options();
    }

    private CompatibleRecommendationSelection compatibleRecommendationSelection(
            CurrentUserService.CurrentUser user,
            String category,
            List<BuildChatFeasibilityService.PartOption> options,
            int limit
    ) {
        if (options == null || options.isEmpty()) {
            return CompatibleRecommendationSelection.empty();
        }
        // Legacy unit callers do not carry an authenticated user or the Spring compatibility bean.
        // Production Build Chat always has both and therefore uses the authoritative active draft gate.
        if (user == null || partCompatibleCandidateService == null) {
            return new CompatibleRecommendationSelection(
                    options.stream().limit(Math.max(1, limit)).toList(),
                    List.of(),
                    List.of());
        }
        String mode = DIRECT_MULTI_ITEM_QUICK_REPLY_CATEGORIES.contains(category) ? "ADD" : "REPLACE";
        try {
            PartCompatibleCandidateService.CompatibleCandidateSelection selection =
                    partCompatibleCandidateService.compatibleCandidateSelection(
                    user,
                    category,
                    mode,
                    options.stream().map(BuildChatFeasibilityService.PartOption::partId).toList(),
                    Math.max(1, limit)
            );
            // Mockito-based legacy tests may only stub compatibleCandidateIds; retain that narrow fallback.
            if (selection == null) {
                List<String> compatibleIds = partCompatibleCandidateService.compatibleCandidateIds(
                        user,
                        category,
                        mode,
                        options.stream().map(BuildChatFeasibilityService.PartOption::partId).toList(),
                        Math.max(1, limit));
                selection = new PartCompatibleCandidateService.CompatibleCandidateSelection(
                        compatibleIds == null ? List.of() : compatibleIds,
                        List.of(),
                        List.of());
            }
            Set<String> allowed = Set.copyOf(selection.acceptedIds());
            Set<String> alreadySelected = Set.copyOf(selection.alreadySelectedIds());
            List<BuildChatFeasibilityService.PartOption> accepted = options.stream()
                    .filter(option -> allowed.contains(option.partId()) && !alreadySelected.contains(option.partId()))
                    .limit(Math.max(1, limit))
                    .toList();
            return new CompatibleRecommendationSelection(
                    accepted,
                    selection.alreadySelectedIds(),
                    selection.warningIds());
        } catch (RuntimeException error) {
            // Candidate facts could not be verified. Do not expose unchecked products as compatible.
            log.warn("Build Chat candidate compatibility gate failed: category={}, candidateCount={}",
                    category, options.size(), error);
            return CompatibleRecommendationSelection.empty();
        }
    }

    private static List<String> compatibilityRecoveryQuickReplies(String category) {
        if ("RAM".equals(category)) {
            return List.of("현재 견적의 RAM 호환 문제 설명해줘", "현재 RAM 하나 빼줘");
        }
        if ("STORAGE".equals(category)) {
            return List.of("현재 견적의 SSD 호환 문제 설명해줘", "현재 SSD 하나 빼줘");
        }
        return List.of("현재 견적의 호환 문제 설명해줘");
    }

    private static String caseImprovementListText(List<CaseImprovementCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < candidates.size(); index += 1) {
            CaseImprovementCandidate candidate = candidates.get(index);
            if (index > 0) {
                builder.append(" ");
            }
            builder.append(index + 1).append(") ").append(candidate.part().name())
                    .append(" — ").append(String.format("%,d원", candidate.part().price()))
                    .append(" (예상 종합 ").append(candidate.score()).append("점)");
        }
        return builder.toString();
    }

    private static String caseFitImprovementListText(List<CaseFitCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < candidates.size(); index += 1) {
            CaseFitCandidate candidate = candidates.get(index);
            if (index > 0) {
                builder.append(" ");
            }
            builder.append(index + 1).append(") ").append(candidate.part().name())
                    .append(" — ").append(String.format("%,d원", candidate.part().price()));
        }
        return builder.toString();
    }

    private static CaseFitProfile caseFitProfile(List<Map<String, Object>> toolResults) {
        Map<String, Object> details = toolResults.stream()
                .filter(result -> "size".equals(text(result.get("tool"))))
                .findFirst()
                .map(result -> objectMap(result.get("details")))
                .orElse(Map.of());
        return new CaseFitProfile(
                numberValue(details.get("gpuHeadroomMm")),
                numberValue(details.get("coolerHeadroomMm")),
                numberValue(details.get("psuHeadroomMm"))
        );
    }

    /**
     * 부품 후보 칩은 기존 문장형 quickReplies를 유지한다. 다만 RAM/SSD는 여러 상품을 함께 담을 수
     * 있으므로, 프론트가 안전하게 기존 quote draft API를 직접 호출할 수 있는 별도 메타데이터를 함께
     * 내려준다. 단일 카테고리는 기존 미리보기/명시 적용 정책을 유지한다.
     */
    private static void setPartRecommendationQuickReplies(
            Map<String, Object> response,
            String category,
            List<BuildChatFeasibilityService.PartOption> options
    ) {
        List<String> labels = options.stream().map(option -> option.name() + " 견적에 담아줘").toList();
        response.put("quickReplies", labels);
        if (!DIRECT_MULTI_ITEM_QUICK_REPLY_CATEGORIES.contains(category)) {
            response.remove("quickReplyCommands");
            return;
        }
        response.put("quickReplyCommands", options.stream()
                .map(option -> MockData.map(
                        "label", option.name() + " 견적에 담아줘",
                        "type", "ADD_MULTI_ITEM_TO_DRAFT",
                        "partId", option.partId(),
                        "partName", option.name(),
                        "category", category,
                        "quantityDelta", 1
                ))
                .toList());
    }

    private static String topListText(List<BuildChatFeasibilityService.PartOption> options) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < options.size(); index += 1) {
            BuildChatFeasibilityService.PartOption option = options.get(index);
            if (index > 0) {
                builder.append(" ");
            }
            builder.append(index + 1).append(") ").append(option.name())
                    .append(" — ").append(String.format("%,d원", option.unitPrice()));
        }
        return builder.toString();
    }

    private static ExplicitPartSelection explicitPartSelection(String category, String message) {
        String gpuClass = "GPU".equals(category) ? targetGpuClass(message) : null;
        String modelOrVendor = gpuClass == null ? simulationModelToken(category, message) : null;
        if (gpuClass == null && isGenericPartSpecToken(category, modelOrVendor)) {
            modelOrVendor = null;
        }
        if (gpuClass == null && modelOrVendor == null) {
            return null;
        }
        String label = gpuClass != null
                ? gpuClass.replace('_', ' ')
                : modelOrVendor;
        return new ExplicitPartSelection(gpuClass, modelOrVendor, label);
    }

    static boolean isGenericPartSpecToken(String category, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String compact = normalizeCommand(token)
                // MODEL_TOKEN_PATTERN은 "추천해줘 32GB"처럼 숫자 앞의 한국어까지 함께 잡을 수 있다.
                // 선택 동사와 정렬 수식어를 제거해 순수 스펙을 상품명으로 오인하지 않게 한다.
                .replace("추천해줘", "")
                .replace("추천", "")
                .replace("찾아줘", "")
                .replace("찾아", "")
                .replace("보여줘", "")
                .replace("보여", "")
                .replace("최저가", "")
                .replace("가성비", "")
                .replace("고성능", "")
                .replace("최고급", "")
                .replace("최상급", "")
                .replace("하이엔드", "")
                .replace("최소", "")
                .replace("이상", "")
                .replace("이하", "")
                .replace("이내", "")
                .replace("ram", "")
                .replace("램", "")
                .replace("메모리", "")
                .replace("storage", "")
                .replace("ssd", "")
                .replace("저장장치", "")
                .replace("psu", "")
                .replace("파워", "")
                .replace("전원", "")
                .toUpperCase(Locale.ROOT);
        if (compact.matches("\\d+(?:\\.\\d+)?(?:만|백만|천만|억)?원?")) {
            return true;
        }
        if (compact.matches("\\d+(?:GB|TB|기가(?:바이트)?|테라(?:바이트)?|W|와트|MM|MHZ)")) {
            return true;
        }
        return "RAM".equals(category) && compact.matches("DDR[345]");
    }

    // LLM 제약 + 정규식 폴백 병합: LLM 값이 우선하고, 빈 곳만 메시지 정규식으로 채운다.
    // (라우팅이 아니라 이미 PART_RECOMMEND로 판정된 요청의 데이터 보강 — LLM 비결정성 방어)
    private static final Pattern CAPACITY_TB_PATTERN = Pattern.compile("(\\d+)\\s*(?:tb|테라)", Pattern.CASE_INSENSITIVE);

    private BuildChatFeasibilityService.SpecConstraint mergedPartConstraint(Map<String, Object> llmConstraint, String message) {
        String category = firstText(
                text(llmConstraint.get("category")),
                firstText(detectRecommendationTargetCategory(message), detectPartCategory(message))
        );
        if (category == null) {
            return null;
        }
        Integer capacity = numberValue(llmConstraint.get("minCapacityGb"));
        if (capacity == null && ("RAM".equals(category) || "STORAGE".equals(category))) {
            capacity = parseCapacityGb(message);
            if (capacity == null) {
                Matcher tb = CAPACITY_TB_PATTERN.matcher(message == null ? "" : message.toLowerCase(Locale.ROOT));
                if (tb.find()) {
                    capacity = Integer.parseInt(tb.group(1)) * 1000;
                }
            }
        }
        Integer wattage = numberValue(llmConstraint.get("minWattageW"));
        if (wattage == null && "PSU".equals(category)) {
            wattage = parseWattage(message);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("category", category);
        merged.put("minCapacityGb", capacity);
        merged.put("minVramGb", numberValue(llmConstraint.get("minVramGb")));
        merged.put("minWattageW", wattage);
        merged.put("quantity", numberValue(llmConstraint.get("quantity")));
        merged.put("maxBudgetWon", firstNumber(llmConstraint.get("maxBudgetWon"), parseBudgetWon(message)));
        // 닫힌 속성은 LLM만 구조화한다(서버 키워드 해석 금지) — 값이 있으면 그대로 이어받는다.
        merged.put("coolingType", text(llmConstraint.get("coolingType")));
        merged.put("pcieGeneration", numberValue(llmConstraint.get("pcieGeneration")));
        merged.put("airflowFocused", llmConstraint.get("airflowFocused") instanceof Boolean flag && flag ? Boolean.TRUE : null);
        return BuildChatFeasibilityService.SpecConstraint.fromMap(merged);
    }

    private void appendBudgetAlternative(
            StringBuilder textBuilder,
            BuildChatFeasibilityService.SpecConstraint constraint,
            int quantity,
            CurrentUserService.CurrentUser user
    ) {
        if (constraint.maxBudgetWon() == null) {
            return;
        }
        compatibleRecommendationOptions(
                user,
                constraint.category(),
                feasibilityService.bestUnderBudget(
                        constraint.category(),
                        constraint.maxBudgetWon(),
                        quantity,
                        PART_RECOMMENDATION_CANDIDATE_POOL_SIZE),
                1).stream().findFirst().ifPresent(alt -> {
            String altSpec = altSpecSummary(constraint.category(), alt);
            textBuilder.append(" 예산 안에서는 ");
            if (!altSpec.isBlank()) {
                textBuilder.append(altSpec).append(" ");
            }
            textBuilder.append(alt.name()).append("(").append(String.format("%,d원", alt.unitPrice())).append(")까지 가능합니다.");
        });
    }

    private static String specSummary(BuildChatFeasibilityService.SpecConstraint constraint) {
        List<String> parts = new ArrayList<>();
        if (constraint.minCapacityGb() != null) {
            parts.add(constraint.minCapacityGb() + "GB");
        }
        if (constraint.minVramGb() != null) {
            parts.add("VRAM " + constraint.minVramGb() + "GB");
        }
        if (constraint.minWattageW() != null) {
            parts.add(constraint.minWattageW() + "W");
        }
        // 사용자 언어로 속성 요약(원어 노출 금지) — 수랭/공랭, PCIe 세대, 통풍 강조.
        if (constraint.coolingType() != null) {
            parts.add("LIQUID".equalsIgnoreCase(constraint.coolingType()) ? "수랭" : "공랭");
        }
        if (constraint.pcieGeneration() != null) {
            parts.add("PCIe " + constraint.pcieGeneration() + ".0");
        }
        if (Boolean.TRUE.equals(constraint.airflowFocused())) {
            parts.add("통풍 강조");
        }
        return String.join(" · ", parts);
    }

    private static String altSpecSummary(String category, BuildChatFeasibilityService.PartOption option) {
        return switch (category) {
            case "RAM", "STORAGE" -> option.capacityGb() != null && option.capacityGb() > 0 ? option.capacityGb() + "GB" : "";
            case "GPU" -> option.vramGb() != null && option.vramGb() > 0 ? "VRAM " + option.vramGb() + "GB" : "";
            case "PSU" -> option.wattageW() != null && option.wattageW() > 0 ? option.wattageW() + "W" : "";
            default -> "";
        };
    }

    private static List<String> counterProposalQuickReplies(
            BuildChatFeasibilityService.SpecConstraint constraint,
            String categoryLabel,
            String specSummary
    ) {
        List<String> replies = new ArrayList<>();
        if (!specSummary.isBlank()) {
            replies.add(specSummary + " " + categoryLabel + " 최저가로 추천해줘");
        }
        if (constraint.maxBudgetWon() != null) {
            replies.add(formatBudgetLabel(constraint.maxBudgetWon()) + " 이내 " + categoryLabel + " 추천해줘");
        }
        return replies;
    }

    // 용도 인지 예산 역제안(예: "70만원 AI용") — 용도상 GPU가 필수인데 예산이 최소 구성가 미만이면 실계산 근거로 안내.
    private void applyUsageMinimumCounterProposal(
            Map<String, Object> response,
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent
    ) {
        // 서버는 예산을 파싱했는데(예: bare "만원") LLM이 되묻기(ASK_FOLLOW_UP)로 흐른 경우도 잡는다.
        boolean budgetKnown = rawBudgetIntent != null && rawBudgetIntent.hasBudget();
        boolean eligible = engineResponse.intent() == AiChatIntent.FULL_BUILD_RECOMMEND
                || (engineResponse.intent() == AiChatIntent.ASK_FOLLOW_UP && budgetKnown
                        && objectMaps(response.get("builds")).isEmpty());
        if (!eligible || !budgetKnown || rawBudgetIntent.explicitHardConstraint()) {
            return;
        }
        List<String> usageTags = stringList(engineResponse.parsedContext().get("usageTags"));
        boolean gpuRequired = BuildChatFeasibilityService.requiresGpu(usageTags);
        // GPU 필수 용도는 용도 인지 최소가(GPU 포함), 그 외(사무용 등)도 일반 최소 구성가 미달이면 안내한다
        // — "30만원 사무용"이 빈손 텍스트로 끝나지 않게.
        int minimum = gpuRequired ? feasibilityService.usageMinimumTotal(usageTags) : minimumBuildTotal();
        if (minimum <= 0 || rawBudgetIntent.budget() >= minimum) {
            return;
        }
        String usageLabel = usageLabel(usageTags);
        String notice = gpuRequired
                ? usageLabel + " PC는 그래픽카드가 필요해 내부 자산 기준 최소 약 "
                        + formatBudgetLabel(minimum) + "부터 가능합니다. "
                        + formatBudgetLabel(rawBudgetIntent.budget()) + " 예산으로는 어렵습니다."
                : formatBudgetLabel(rawBudgetIntent.budget()) + " 예산으로는 완전한 구성이 어렵습니다. "
                        + "내부 자산 기준 최소 구성은 약 " + formatBudgetLabel(minimum) + "부터 가능합니다.";
        // 예산 미달 턴에는 밴드 밖 카드를 보여주지 않는다. DB에서 계산한 최소가와 다음 선택지만
        // 남겨 LLM 문장이나 과예산 카드가 서로 다른 숫자를 말하지 않게 한다.
        response.put("answerType", "GENERAL");
        response.put("message", notice);
        response.put("builds", List.of());
        response.remove("simulation");
        List<String> warnings = new ArrayList<>(stringList(response.get("warnings")));
        warnings.add(gpuRequired ? "BUDGET_BELOW_USAGE_MINIMUM" : "BUDGET_BELOW_MINIMUM");
        response.put("warnings", distinct(warnings));
        int suggested = ((minimum + 99_999) / 100_000) * 100_000;
        // GPU 용도는 용도 하향(사무용) 대안이 의미 있지만, 이미 사무용 최소가 미달이면 예산 에코 칩은 무의미하다.
        response.put("quickReplies", gpuRequired
                ? List.of(
                        formatBudgetLabel(suggested) + " " + usageLabel + " PC 추천해줘",
                        formatBudgetLabel(rawBudgetIntent.budget()) + " 사무용 PC 추천해줘")
                : List.of(
                        formatBudgetLabel(suggested) + " 사무용 PC 추천해줘",
                        "가능한 최소 구성으로 추천해줘"));
    }

    // 결정경로용 경량 용도 추정 — 라우팅에 쓰지 않고, 이미 선택된 응답의 최소 구성가 계산을 정확하게
    // 만드는 데만 쓴다(오인 시에도 GPU 포함 방향의 보수적 안내라 해가 없음).
    private static List<String> inferUsageTags(String message) {
        String normalized = normalizeCommand(message);
        List<String> tags = new ArrayList<>();
        if (containsAnyNormalized(normalized, "ai", "cuda", "학습", "머신러닝", "딥러닝", "llm", "인공지능")) {
            tags.add("AI_DEV");
        }
        if (containsAnyNormalized(normalized, "영상", "편집", "프리미어", "렌더", "블렌더", "3d")) {
            tags.add("VIDEO_EDIT");
        }
        if (containsAnyNormalized(normalized, "게임", "게이밍", "배그", "발로란트", "오버워치", "로스트아크", "롤", "디아블로", "fps")) {
            tags.add("GAMING");
        }
        if (containsAnyNormalized(normalized, "개발", "코딩", "도커", "docker", "ide")) {
            tags.add("DEVELOPMENT");
        }
        return tags;
    }

    private static String usageLabel(List<String> tags) {
        if (tags.contains("AI_DEV")) {
            return "AI 학습용";
        }
        if (tags.contains("VIDEO_EDIT")) {
            return "영상편집용";
        }
        if (tags.contains("GAMING")) {
            return "게이밍";
        }
        if (tags.contains("DEVELOPMENT")) {
            return "개발용";
        }
        return "사무용";
    }

    private Optional<Map<String, Object>> exactSingletonPartPreviewResponse(
            Map<String, Object> body,
            String message,
            CurrentUserService.CurrentUser user
    ) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher suffix = EXACT_PART_ADD_SUFFIX.matcher(message.trim());
        if (!suffix.find() || suffix.start() <= 0) {
            return Optional.empty();
        }
        String requestedName = message.trim().substring(0, suffix.start()).trim();
        if (requestedName.length() < 3) {
            return Optional.empty();
        }
        String normalizedRequestedName = normalizePartSelectionName(requestedName);
        List<PartCandidate> matches = jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE status = 'ACTIVE'
                          AND deleted_at IS NULL
                          AND TRIM(REGEXP_REPLACE(
                                REGEXP_REPLACE(UPPER(name), '[^0-9A-Z가-힣]+', ' ', 'g'),
                                '[[:space:]]+', ' ', 'g'
                              )) = ?
                        LIMIT 2
                        """, normalizedRequestedName)
                .stream()
                .map(this::partCandidate)
                .toList();
        if (matches.size() != 1) {
            return Optional.empty();
        }

        PartCandidate replacement = matches.get(0);
        List<Map<String, Object>> draftItems = effectiveDraftItems(body, user);
        PartCandidate existing = draftItems.stream()
                .map(this::draftPartCandidate)
                .filter(item -> replacement.category().equals(item.category()))
                .findFirst()
                .orElse(null);
        Map<String, Object> samePartItem = draftItems.stream()
                .filter(item -> Objects.equals(text(item.get("partId")), replacement.publicId()))
                .findFirst()
                .orElse(null);
        boolean multiItemCategory = MULTI_ITEM_CATEGORIES.contains(replacement.category());
        if (!multiItemCategory && existing != null && Objects.equals(existing.publicId(), replacement.publicId())) {
            Map<String, Object> response = fastResponse(
                    "PART",
                    replacement.name() + "은(는) 이미 현재 견적에 선택되어 있습니다.",
                    List.of()
            );
            response.put("quickReplies", List.of(categoryLabel(replacement.category()) + " 다른 후보 추천해줘"));
            return Optional.of(response);
        }

        String operation;
        Map<String, Object> draftEdit = new LinkedHashMap<>();
        draftEdit.put("category", replacement.category());
        if (multiItemCategory && samePartItem != null) {
            operation = "UPDATE_QUANTITY";
            Integer currentQuantity = numberValue(samePartItem.get("quantity"));
            draftEdit.put("targetQuantity", Math.min(9, (currentQuantity == null ? 1 : currentQuantity) + 1));
        } else {
            operation = existing == null || multiItemCategory ? "ADD" : "REPLACE";
        }
        draftEdit.put("operation", operation);
        AiChatEngineResponse directSelection = new AiChatEngineResponse(
                "선택한 " + categoryLabel(replacement.category()) + " 변경안을 현재 견적과 비교했습니다.",
                AiChatIntent.BUILD_MODIFY,
                List.of(),
                List.of(),
                List.of(new AiChatEngineResponse.PartRecommendation(
                        replacement.publicId(),
                        replacement.category(),
                        replacement.name(),
                        replacement.manufacturer(),
                        replacement.price() == null ? 0 : replacement.price(),
                        replacement.attributes()
                )),
                Map.of("draftEdit", draftEdit, "exactPartSelection", true),
                List.of(),
                List.of(),
                null
        );
        Map<String, Object> response = fastResponse("PART", directSelection.assistantMessage(), List.of());
        applyDraftEditPreview(response, directSelection, withDraftItems(body, draftItems));
        return Optional.of(response);
    }

    private List<Map<String, Object>> effectiveDraftItems(
            Map<String, Object> body,
            CurrentUserService.CurrentUser user
    ) {
        Map<String, Object> currentQuoteDraft = objectMap(body.get("currentQuoteDraft"));
        if (currentQuoteDraft.containsKey("items")) {
            return objectMaps(currentQuoteDraft.get("items"));
        }
        if (user == null || user.internalId() == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS part_id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price AS current_price,
                               p.attributes,
                               qdi.quantity
                        FROM quote_drafts qd
                        JOIN quote_draft_items qdi
                          ON qdi.quote_draft_id = qd.id
                         AND qdi.deleted_at IS NULL
                        JOIN parts p
                          ON p.id = qdi.part_id
                         AND p.deleted_at IS NULL
                        WHERE qd.id = (
                            SELECT latest.id
                            FROM quote_drafts latest
                            WHERE latest.user_id = ?
                              AND latest.status = 'ACTIVE'
                              AND latest.deleted_at IS NULL
                            ORDER BY latest.updated_at DESC, latest.id DESC
                            LIMIT 1
                        )
                        ORDER BY qdi.id
                        """, user.internalId())
                .stream()
                .map(row -> MockData.map(
                        "partId", DbValueMapper.string(row, "part_id"),
                        "category", DbValueMapper.string(row, "category"),
                        "name", DbValueMapper.string(row, "name"),
                        "manufacturer", DbValueMapper.string(row, "manufacturer"),
                        "currentPrice", DbValueMapper.integer(row, "current_price"),
                        "quantity", Math.max(1, DbValueMapper.integer(row, "quantity")),
                        "attributes", DbValueMapper.json(row, "attributes", Map.of())
                ))
                .toList();
    }

    private static Map<String, Object> withDraftItems(
            Map<String, Object> body,
            List<Map<String, Object>> draftItems
    ) {
        Map<String, Object> effectiveBody = new LinkedHashMap<>(body);
        Map<String, Object> currentQuoteDraft = new LinkedHashMap<>(objectMap(body.get("currentQuoteDraft")));
        currentQuoteDraft.put("items", draftItems);
        effectiveBody.put("currentQuoteDraft", currentQuoteDraft);
        return effectiveBody;
    }

    private static String normalizePartSelectionName(String value) {
        return value == null ? "" : value
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^0-9A-Z가-힣]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Handles an unambiguous single-slot replacement without asking the LLM to rediscover price
     * direction. The normal draft preview path still runs the full Tool check before returning a card.
     */
    private Optional<Map<String, Object>> directionalDraftEditFastResponse(Map<String, Object> body, String message) {
        if (partReplacementRanker == null || message == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> draftItems = objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"));
        if (draftItems.isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalizeCommand(message);
        if (!containsAnyNormalized(normalized,
                "바꿔줘", "바꿔주", "바꿔놔", "교체해줘", "교체해주", "낮춰줘", "내려줘", "올려줘")) {
            return Optional.empty();
        }
        String direction = directionalDraftEditDirection(normalized);
        String category = firstText(text(body.get("selectedCategory")), detectPartCategory(message));
        if (direction == null || category == null || !SINGLE_ITEM_CATEGORIES.contains(category)) {
            return Optional.empty();
        }
        Map<String, Object> currentItem = findDraftItem(draftItems, category, message);
        if (currentItem.isEmpty()) {
            return Optional.empty();
        }

        PartReplacementRanker.SelectionResult selection = partReplacementRanker.select(
                category,
                currentItem,
                direction,
                null,
                partRecommendations(category, 100),
                20
        );
        String currentName = firstText(text(currentItem.get("name")), "현재 " + categoryLabel(category));
        List<AiChatEngineResponse.PartRecommendation> selectedParts = selection.parts();
        if (requiresLowerPerformanceTier(normalized) && "GPU".equals(category)) {
            selectedParts = selectedParts.stream()
                    .filter(candidate -> isLowerGpuTier(currentItem, candidate))
                    .toList();
        }
        if (selectedParts.isEmpty()) {
            Map<String, Object> response = fastResponse(
                    "PART",
                    currentName + "보다 " + draftEditDirectionLabel(direction) + " 자동 검증 가능 후보를 찾지 못했습니다.",
                    selection.warnings()
            );
            response.put("quickReplies", List.of(
                    categoryLabel(category) + " 다른 후보 추천해줘",
                    "현재 견적 그대로 둘게"
            ));
            return Optional.of(response);
        }

        Map<String, Object> lastRejected = null;
        for (AiChatEngineResponse.PartRecommendation candidate : selectedParts) {
            Map<String, Object> draftEdit = MockData.map(
                    "operation", "REPLACE",
                    "category", category,
                    "priceDirection", direction
            );
            AiChatEngineResponse engineResponse = new AiChatEngineResponse(
                    currentName + "보다 " + draftEditDirectionLabel(direction) + " "
                            + categoryLabel(category) + " 변경안을 찾았습니다.",
                    AiChatIntent.BUILD_MODIFY,
                    List.of(),
                    List.of(),
                    List.of(candidate),
                    MockData.map("draftEdit", draftEdit, "warnings", selection.warnings()),
                    List.of(),
                    List.of(),
                    null
            );
            Map<String, Object> response = fastResponse("PART", engineResponse.assistantMessage(), selection.warnings());
            applyDraftEditPreview(response, engineResponse, body);
            if (!objectMaps(response.get("builds")).isEmpty()) {
                return Optional.of(response);
            }
            lastRejected = response;
        }
        return Optional.ofNullable(lastRejected);
    }

    private static String directionalDraftEditDirection(String normalized) {
        if (containsAnyNormalized(normalized,
                "더싼", "싼걸로", "저렴", "가격낮", "예산낮", "가성비", "낮춰", "내려")) {
            return "CHEAPER";
        }
        if (containsAnyNormalized(normalized,
                "더좋", "상위", "고급", "고성능", "최상급", "최고급", "하이엔드", "끝판왕",
                "업그레이드", "성능높", "올려", "더여유", "여유있")) {
            return "MORE_EXPENSIVE";
        }
        if (containsAnyNormalized(normalized,
                "비슷한가격", "비슷한가격대", "그가격", "가격대유지", "가격유지")) {
            return "SIMILAR_PRICE";
        }
        return null;
    }

    private static String draftEditDirectionLabel(String direction) {
        return switch (direction) {
            case "CHEAPER" -> "가격이 낮고 성능 급락을 줄인";
            case "MORE_EXPENSIVE" -> "성능 등급이 높은";
            case "SIMILAR_PRICE" -> "가격이 비슷하면서 성능 등급을 유지한";
            default -> "교체 가능한";
        };
    }

    private static boolean requiresLowerPerformanceTier(String normalized) {
        return containsAnyNormalized(normalized,
                "한단계낮", "한단계아래", "등급낮", "성능낮", "성능조금낮", "다운그레이드");
    }

    private static boolean isLowerGpuTier(
            Map<String, Object> currentItem,
            AiChatEngineResponse.PartRecommendation candidate
    ) {
        int currentTier = gpuTierValue(text(objectMap(currentItem.get("attributes")).get("gpuClass")));
        int candidateTier = gpuTierValue(text(objectMap(candidate.attributes()).get("gpuClass")));
        return currentTier > 0 && candidateTier > 0 && candidateTier < currentTier;
    }

    private static int gpuTierValue(String gpuClass) {
        if (gpuClass == null) {
            return 0;
        }
        String normalized = gpuClass.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        Matcher matcher = Pattern.compile("(40[6-9]0|50[6-9]0)(?:_?(TI|SUPER))?").matcher(normalized);
        if (!matcher.find()) {
            return 0;
        }
        int value = Integer.parseInt(matcher.group(1)) * 10;
        if (matcher.group(2) != null) {
            value += "TI".equals(matcher.group(2)) ? 5 : 3;
        }
        return value;
    }

    // 부품 변경 요청 — 즉시 반영하지 않고, 변경을 반영한 전체 구성을 재검증해 미리보기 카드로 제시한다.
    // 카드의 적용 버튼(기존 REPLACE 전체 적용)을 눌러야 실제 견적이 바뀐다.
    private void applyDraftEditPreview(Map<String, Object> response, AiChatEngineResponse engineResponse, Map<String, Object> body) {
        if (engineResponse.intent() != AiChatIntent.BUILD_MODIFY) {
            return;
        }
        // 후보 탐색 문장은 LLM이 BUILD_MODIFY로 흔들려도 변경 대상을 임의 확정하지 않는다.
        // 후보 목록을 먼저 제공하고, 사용자가 특정 상품을 고른 다음 턴에만 미리보기를 만든다.
        if (isExplicitRecommendationRequest(text(body.get("message")))) {
            return;
        }
        List<Map<String, Object>> draftItems = objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"));
        Map<String, Object> draftEdit = objectMap(engineResponse.parsedContext().get("draftEdit"));
        String operation = text(draftEdit.get("operation"));
        String category = text(draftEdit.get("category"));
        if (operation == null || category == null || !List.of("ADD", "REPLACE", "REMOVE", "UPDATE_QUANTITY").contains(operation)) {
            return;
        }
        boolean singletonAlreadyPresent = SINGLE_ITEM_CATEGORIES.contains(category)
                && draftItems.stream().map(this::draftPartCandidate).anyMatch(item -> category.equals(item.category()));
        if (singletonAlreadyPresent && "ADD".equals(operation)) {
            operation = "REPLACE";
        }
        boolean categoryAlreadyPresent = draftItems.stream()
                .map(this::draftPartCandidate)
                .anyMatch(item -> category.equals(item.category()));
        if ("REPLACE".equals(operation) && !categoryAlreadyPresent) {
            operation = "ADD";
        }
        if (draftItems.isEmpty() && List.of("REMOVE", "UPDATE_QUANTITY").contains(operation)) {
            return;
        }
        // "RAM 하나 넣어줘"처럼 상품·스펙·가격·방향이 전혀 없는 변경 요청에서 LLM이
        // 첫 후보를 임의 확정하지 않게 한다. 미리보기를 만들지 않으면 바로 뒤의 부품 추천
        // 후처리가 검증된 TOP 후보와 선택 칩을 제공한다. 정확 상품 칩과 방향성 교체는 앞선
        // deterministic fast path에서 이미 처리되므로 영향을 받지 않는다.
        if (List.of("ADD", "REPLACE").contains(operation)
                && isUnderspecifiedDraftPartSelection(engineResponse, body, category)) {
            response.put("answerType", "PART");
            response.put("message", categoryLabel(category)
                    + " 상품을 바로 정하려면 원하는 용량·가격대 또는 성능 방향이 필요합니다. 아래 기준 중 하나를 골라 주세요.");
            response.put("builds", List.of());
            response.put("quickReplies", underspecifiedPartSelectionQuickReplies(category));
            response.put("clarification", MockData.map(
                    "missingSlots", List.of("partSelection"),
                    "originalMessage", categoryLabel(category) + " 추천해줘"
            ));
            return;
        }
        PartCandidate replacement = engineResponse.partRecommendations() == null || engineResponse.partRecommendations().isEmpty()
                ? null
                : partCandidate(engineResponse.partRecommendations().get(0));
        if (replacement == null && !List.of("REMOVE", "UPDATE_QUANTITY").contains(operation)) {
            return;
        }
        if ("REPLACE".equals(operation) && replacement != null && draftItems.stream()
                .map(this::draftPartCandidate)
                .anyMatch(item -> category.equals(item.category()) && Objects.equals(item.publicId(), replacement.publicId()))) {
            response.put("answerType", "PART");
            response.put("message", replacement.name() + "은(는) 이미 현재 견적에 선택되어 있습니다.");
            response.put("builds", List.of());
            response.put("quickReplies", List.of(categoryLabel(category) + " 다른 후보 추천해줘"));
            return;
        }
        Integer targetQuantity = numberValue(draftEdit.get("targetQuantity"));
        int beforeTotal = 0;
        Integer existingCategoryQuantity = null;
        List<PartCandidate> parts = new ArrayList<>();
        Map<String, Integer> quantities = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> replacedNames = new ArrayList<>();
        for (Map<String, Object> item : draftItems) {
            Integer quantityValue = numberValue(item.get("quantity"));
            int quantity = quantityValue == null || quantityValue < 1 ? 1 : quantityValue;
            PartCandidate candidate = draftPartCandidate(item);
            beforeTotal += Math.max(0, candidate.price() == null ? 0 : candidate.price()) * quantity;
            if (category.equals(candidate.category())) {
                existingCategoryQuantity = quantity;
                if ("REPLACE".equals(operation) || "REMOVE".equals(operation)) {
                    replacedNames.add(candidate.name());
                    continue;
                }
                if ("UPDATE_QUANTITY".equals(operation) && targetQuantity != null && targetQuantity >= 1) {
                    quantity = targetQuantity;
                }
            }
            parts.add(candidate);
            quantities.put(candidate.publicId() == null ? candidate.name() : candidate.publicId(), quantity);
            items.add(partItem(candidate, null, quantity));
        }
        if ("REPLACE".equals(operation) || "ADD".equals(operation)) {
            int quantity = targetQuantity != null && targetQuantity > 0
                    ? Math.max(1, Math.min(9, targetQuantity))
                    : existingCategoryQuantity != null && "REPLACE".equals(operation) && !"RAM".equals(category)
                            ? existingCategoryQuantity
                            : defaultQuantity(replacement);
            parts.add(replacement);
            quantities.put(replacement.publicId() == null ? replacement.name() : replacement.publicId(), quantity);
            items.add(partItem(replacement, "변경 요청으로 선택된 부품", quantity));
        }
        if (items.isEmpty()) {
            return;
        }
        int afterTotal = 0;
        for (PartCandidate part : parts) {
            Integer quantity = quantities.get(part.publicId() == null ? part.name() : part.publicId());
            afterTotal += Math.max(0, part.price() == null ? 0 : part.price()) * (quantity == null ? 1 : quantity);
        }
        List<String> previewWarnings = new ArrayList<>();
        List<Map<String, Object>> previewToolResults = toolResults(parts, quantities, afterTotal, previewWarnings);
        previewWarnings.addAll(toolWarnings(previewToolResults));
        if (hasBlockingToolFailure(previewToolResults)) {
            List<String> responseWarnings = new ArrayList<>(stringList(response.get("warnings")));
            responseWarnings.addAll(previewWarnings);
            String reason = previewWarnings.stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("자동 검증에서 현재 견적과 함께 사용할 수 없는 조합으로 확인됐습니다.");
            String existing = firstText(text(response.get("message")), "");
            response.put("message", (existing.isBlank() ? "" : existing + " ")
                    + "요청한 변경은 자동 검증을 통과하지 못해 적용 미리보기를 만들지 않았습니다. " + reason);
            response.put("builds", List.of());
            response.put("answerType", "PART");
            response.put("warnings", distinct(responseWarnings));
            response.put("quickReplies", List.of(
                    categoryLabel(category) + " 호환되는 다른 후보 추천해줘",
                    "현재 견적 그대로 둘게"
            ));
            return;
        }
        Map<String, Object> preview = MockData.map(
                "id", "ai-draft-edit-preview-" + Math.abs(items.hashCode()),
                "tier", "draft-edit",
                "label", "변경 미리보기",
                "title", "변경 적용 미리보기",
                "summary", "요청하신 변경을 현재 견적에 반영한 구성입니다. 카드의 적용 버튼을 눌러야 실제 견적에 반영됩니다.",
                "recommendedFor", List.of("견적 변경", "자동 검증"),
                "totalPrice", afterTotal,
                "badges", List.of("DRAFT_EDIT_PREVIEW"),
                "budgetWon", afterTotal,
                "budgetLabel", formatBudgetLabel(afterTotal),
                "tierLabel", "변경 미리보기",
                "appliedPartCategories", List.of(category),
                "items", items,
                "toolResults", previewToolResults,
                "warnings", distinct(previewWarnings),
                "confidence", confidence(previewToolResults, previewWarnings),
                "evidenceIds", List.of()
        );
        int delta = afterTotal - beforeTotal;
        String deltaText = delta == 0
                ? "총액 변화는 없습니다."
                : "총액이 " + String.format("%,d원", beforeTotal) + " → " + String.format("%,d원", afterTotal)
                        + " (" + (delta > 0 ? "+" : "-") + String.format("%,d원", Math.abs(delta)) + ")입니다.";
        // 어떤 견적을 기준으로 무엇이 바뀌는지 명시 — 사용자가 자기 드래프트 상태를 모르면
        // 미리보기 총액이 뜬금없어 보인다("총액 1,455만?!" 혼란 방지).
        StringBuilder changeSummary = new StringBuilder()
                .append("현재 견적(").append(draftItems.size()).append("개 부품, ")
                .append(String.format("%,d원", beforeTotal)).append(") 기준입니다. ");
        if ("REPLACE".equals(operation) && !replacedNames.isEmpty() && replacement != null) {
            changeSummary.append("교체: ").append(String.join(", ", replacedNames))
                    .append(" → ").append(replacement.name()).append(". ");
        } else if ("REMOVE".equals(operation) && !replacedNames.isEmpty()) {
            changeSummary.append("제거: ").append(String.join(", ", replacedNames)).append(". ");
        } else if ("ADD".equals(operation) && replacement != null) {
            changeSummary.append("추가: ").append(replacement.name()).append(". ");
        }
        String existing = firstText(text(response.get("message")), "");
        response.put("message", (existing.isBlank() ? "" : existing + " ")
                + changeSummary + "변경을 반영하면 " + deltaText + " 아래 미리보기 카드에서 적용할 수 있습니다.");
        response.put("builds", List.of(preview));
        response.put("answerType", "PART");
    }

    private boolean isUnderspecifiedDraftPartSelection(
            AiChatEngineResponse engineResponse,
            Map<String, Object> body,
            String category
    ) {
        if (Boolean.TRUE.equals(engineResponse.parsedContext().get("exactPartSelection"))) {
            return false;
        }
        String message = text(body.get("message"));
        String normalized = normalizeCommand(message);
        if (containsAnyNormalized(
                normalized, "선택한", "추천한", "방금", "아까", "위제품", "위상품", "첫번째", "두번째", "세번째")) {
            return false;
        }
        if (directionalDraftEditDirection(normalized) != null || explicitPartSelection(category, message) != null) {
            return false;
        }
        BuildChatFeasibilityService.SpecConstraint constraint = mergedPartConstraint(
                objectMap(engineResponse.parsedContext().get("partConstraint")),
                message
        );
        return constraint == null || (!constraint.hasSpec() && constraint.maxBudgetWon() == null);
    }

    private static List<String> underspecifiedPartSelectionQuickReplies(String category) {
        return switch (category) {
            case "RAM" -> List.of("32GB RAM 추천해줘", "64GB RAM 추천해줘", "RAM 최저가로 추천해줘");
            case "STORAGE" -> List.of("1TB SSD 추천해줘", "2TB SSD 추천해줘", "SSD 최저가로 추천해줘");
            default -> {
                String label = categoryLabel(category);
                yield List.of(
                        label + " 가성비로 추천해줘",
                        label + " 고성능으로 추천해줘",
                        label + " 최저가로 추천해줘"
                );
            }
        };
    }

    private Optional<Map<String, Object>> underspecifiedPartMutationClarificationResponse(
            Map<String, Object> body,
            String message
    ) {
        String category = detectPartCategory(message);
        String normalized = normalizeCommand(message);
        boolean mutation = containsAnyNormalized(normalized, "바꿔", "교체", "담아", "넣어", "추가");
        boolean removal = containsAnyNormalized(normalized, "빼", "삭제", "제거");
        boolean contextualSelection = containsAnyNormalized(
                normalized, "선택한", "추천한", "방금", "아까", "위제품", "위상품", "첫번째", "두번째", "세번째");
        boolean quantityChange = containsAnyNormalized(
                normalized, "수량", "한개로", "두개로", "세개로", "1개로", "2개로", "3개로");
        boolean categoryAlreadySelected = category != null && objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"))
                .stream()
                .anyMatch(item -> category.equals(text(item.get("category"))));
        if (category == null || !mutation || removal
                || contextualSelection
                || (quantityChange && categoryAlreadySelected)
                || directionalDraftEditDirection(normalized) != null
                || explicitPartSelection(category, message) != null) {
            return Optional.empty();
        }
        BuildChatFeasibilityService.SpecConstraint constraint = mergedPartConstraint(Map.of(), message);
        if (constraint != null && (constraint.hasSpec() || constraint.maxBudgetWon() != null)) {
            return Optional.empty();
        }
        String label = categoryLabel(category);
        Map<String, Object> response = fastResponse(
                "PART",
                label + " 상품이 특정되지 않았습니다. 임의의 상품을 바로 담지 않고, 원하는 용량·가격대 또는 성능 방향을 먼저 확인할게요.",
                List.of());
        response.put("quickReplies", underspecifiedPartSelectionQuickReplies(category));
        response.put("clarification", MockData.map(
                "missingSlots", List.of("partSelection"),
                "originalMessage", label + " 추천해줘"
        ));
        return Optional.of(response);
    }

    private static boolean isSelfContainedClarificationReply(String message) {
        if (detectPartCategory(message) == null) {
            return false;
        }
        String normalized = normalizeCommand(message);
        return containsAnyNormalized(
                normalized,
                "추천", "바꿔", "교체", "담아", "넣어", "추가",
                "비교", "성능", "fps", "상세", "보여", "열어");
    }

    // 그래프(드래프트) 기반 견적 완성: 담긴 부품은 고정하고 빈 카테고리만 채운다. LLM 미경유.
    private Optional<Map<String, Object>> draftCompletionFastResponse(Map<String, Object> request, String message, BudgetIntent rawBudgetIntent) {
        List<Map<String, Object>> draftItems = objectMaps(objectMap(request.get("currentQuoteDraft")).get("items"));
        String normalized = normalizeCommand(message);
        if (draftItems.isEmpty() || !containsAnyNormalized(normalized, "채워", "완성", "나머지", "마저")) {
            return Optional.empty();
        }
        if (rawBudgetIntent != null && rawBudgetIntent.explicitHardConstraint()) {
            return Optional.empty();
        }

        List<PartCandidate> fixedParts = new ArrayList<>();
        Map<String, Integer> fixedQuantities = new LinkedHashMap<>();
        java.util.Set<String> fixedCategories = new java.util.LinkedHashSet<>();
        int fixedTotal = 0;
        for (Map<String, Object> item : draftItems) {
            PartCandidate candidate = draftPartCandidate(item);
            Integer quantityValue = numberValue(item.get("quantity"));
            int quantity = quantityValue == null || quantityValue < 1 ? 1 : quantityValue;
            fixedParts.add(candidate);
            fixedQuantities.put(candidate.publicId() == null ? candidate.name() : candidate.publicId(), quantity);
            if (candidate.category() != null) {
                fixedCategories.add(candidate.category());
            }
            fixedTotal += Math.max(0, candidate.price() == null ? 0 : candidate.price()) * quantity;
        }

        List<String> missingCategories = fallbackCategories(true, true).stream()
                .filter(category -> !fixedCategories.contains(category))
                .toList();
        List<String> warnings = new ArrayList<>();
        Integer budget = rawBudgetIntent != null && rawBudgetIntent.hasBudget() ? rawBudgetIntent.budget() : null;

        // 채울 카테고리가 없으면 이 결정경로는 할 일이 없다 — "나머지로 비용 줄여줘" 같은 실제 의도를
        // 만석 오안내로 가로채지 말고 LLM 경로로 위임한다(:215 UNSUPPORTED→LLM 강등과 같은 철학).
        if (missingCategories.isEmpty()) {
            return Optional.empty();
        }

        int pickBudget = budget == null ? Integer.MAX_VALUE : budget - fixedTotal;
        if (budget != null && pickBudget <= 0) {
            return Optional.of(fastResponse(
                    "GENERAL",
                    "현재 담긴 부품 합계(" + formatBudgetLabel(fixedTotal) + ")가 이미 요청 예산을 넘어 나머지를 채울 수 없습니다. 예산을 높이거나 그래프에서 부품을 조정해 주세요.",
                    List.of("DRAFT_COMPLETION_BUDGET_TOO_LOW")
            ));
        }

        Map<String, List<PartCandidate>> pools = new LinkedHashMap<>();
        for (String category : missingCategories) {
            List<PartCandidate> pool = nearBudgetPartCandidates(category, fallbackCandidateLimit(category, true), pickBudget);
            if (pool.isEmpty()) {
                return Optional.empty();
            }
            pools.put(category, pool);
        }

        LinkedHashMap<String, Map<String, Object>> byComposition = new LinkedHashMap<>();
        for (int strategy = 0; strategy < 3 && byComposition.size() < 3; strategy += 1) {
            List<PartCandidate> picked = pickCompletionParts(pools, strategy, pickBudget);
            if (picked == null) {
                continue;
            }
            List<PartCandidate> previewParts = new ArrayList<>(fixedParts);
            previewParts.addAll(picked);
            List<String> localWarnings = new ArrayList<>();
            int totalPrice = fixedTotal + picked.stream().mapToInt(BuildChatService::completionCandidatePrice).sum();
            List<Map<String, Object>> toolResults = toolResults(previewParts, fixedQuantities, budget == null ? totalPrice : budget, localWarnings);
            if (hasBlockingToolFailure(toolResults)) {
                continue;
            }
            localWarnings.addAll(toolWarnings(toolResults));
            Map<String, Object> build = completionBuildMap(TIERS.get(strategy), fixedParts, fixedQuantities, picked, budget, toolResults, localWarnings);
            byComposition.putIfAbsent(buildItemsKey(build), build);
        }
        if (byComposition.isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, Object>> builds = new ArrayList<>(byComposition.values());
        relabelCompletionBuilds(builds);
        return Optional.of(fastResponse(
                "BUDGET",
                "현재 견적에 담긴 부품은 유지하고 나머지 카테고리를 내부 자산과 자동 검증 기준으로 채웠습니다.",
                builds,
                warnings
        ));
    }

    // 전략 0=가성비(저가), 1=균형(중간), 2=고성능(예산 안 최대) — 남은 예산을 넘지 않게 선택한다
    private List<PartCandidate> pickCompletionParts(Map<String, List<PartCandidate>> pools, int strategy, int pickBudget) {
        List<PartCandidate> picked = new ArrayList<>();
        long runningTotal = 0;
        List<String> categories = new ArrayList<>(pools.keySet());
        for (int index = 0; index < categories.size(); index += 1) {
            List<PartCandidate> pool = pools.get(categories.get(index));
            long minRemaining = 0;
            for (int rest = index + 1; rest < categories.size(); rest += 1) {
                List<PartCandidate> restPool = pools.get(categories.get(rest));
                minRemaining += completionCandidatePrice(restPool.get(0));
            }
            int preferredIndex = switch (strategy) {
                case 0 -> 0;
                case 1 -> pool.size() / 2;
                default -> pool.size() - 1;
            };
            PartCandidate chosen = null;
            for (int candidateIndex = preferredIndex; candidateIndex >= 0; candidateIndex -= 1) {
                PartCandidate candidate = pool.get(candidateIndex);
                long candidatePrice = completionCandidatePrice(candidate);
                if (runningTotal + candidatePrice + minRemaining <= pickBudget) {
                    chosen = candidate;
                    break;
                }
            }
            if (chosen == null) {
                return null;
            }
            picked.add(chosen);
            runningTotal += completionCandidatePrice(chosen);
        }
        return picked;
    }

    private static int completionCandidatePrice(PartCandidate candidate) {
        return Math.max(0, candidate.price() == null ? 0 : candidate.price()) * defaultQuantity(candidate);
    }

    private Map<String, Object> completionBuildMap(
            Tier tier,
            List<PartCandidate> fixedParts,
            Map<String, Integer> fixedQuantities,
            List<PartCandidate> pickedParts,
            Integer budget,
            List<Map<String, Object>> toolResults,
            List<String> warnings
    ) {
        List<Map<String, Object>> items = new ArrayList<>();
        int totalPrice = 0;
        List<String> appliedCategories = new ArrayList<>();
        for (PartCandidate part : fixedParts) {
            int quantity = fixedQuantities.getOrDefault(part.publicId() == null ? part.name() : part.publicId(), 1);
            items.add(partItem(part, "현재 견적에서 유지", quantity));
            totalPrice += Math.max(0, part.price() == null ? 0 : part.price()) * quantity;
            if (part.category() != null && !appliedCategories.contains(part.category())) {
                appliedCategories.add(part.category());
            }
        }
        for (PartCandidate part : pickedParts) {
            int quantity = defaultQuantity(part);
            items.add(partItem(part, "빈 카테고리 자동 채움", quantity));
            totalPrice += Math.max(0, part.price() == null ? 0 : part.price()) * quantity;
        }
        return MockData.map(
                "id", "ai-draft-completion-" + tier.id() + "-" + Math.abs(items.hashCode()),
                "tier", tier.id(),
                "label", tier.label(),
                "title", tier.title() + " 견적 완성 조합",
                "summary", "현재 견적의 부품은 그대로 두고 빈 카테고리만 내부 자산으로 채웠습니다.",
                "recommendedFor", List.of("견적 완성", "내부 자산", "자동 검증"),
                "totalPrice", totalPrice,
                "badges", List.of(tier.title(), "DRAFT_COMPLETION"),
                "budgetWon", budget == null ? totalPrice : budget,
                "budgetLabel", budget == null ? "예산 미지정" : formatBudgetLabel(budget),
                "tierLabel", tier.title(),
                "appliedPartCategories", appliedCategories,
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(warnings),
                "confidence", confidence(toolResults, warnings),
                "evidenceIds", List.of()
        );
    }

    private static void relabelCompletionBuilds(List<Map<String, Object>> builds) {
        builds.sort(java.util.Comparator.comparingInt(build -> {
            Integer total = numberValue(build.get("totalPrice"));
            return total == null ? 0 : total;
        }));
        for (int index = 0; index < builds.size(); index += 1) {
            Tier tier = TIERS.get(Math.min(index, TIERS.size() - 1));
            Map<String, Object> build = builds.get(index);
            build.put("tier", tier.id());
            build.put("label", tier.label());
            build.put("title", tier.title() + " 견적 완성 조합");
            build.put("tierLabel", tier.title());
        }
    }

    private Optional<Map<String, Object>> deterministicFastResponse(Map<String, Object> request, String message, BudgetIntent rawBudgetIntent) {
        Integer budget = rawBudgetIntent != null && rawBudgetIntent.hasBudget() ? rawBudgetIntent.budget() : null;
        // 견적 초안 유무는 map 비었는지가 아니라 items 존재로 판정(빈 items 배열을 "초안 있음"으로 오인하지 않도록 표준화)
        if (budget != null && objectMaps(objectMap(request.get("currentQuoteDraft")).get("items")).isEmpty()) {
            // 용도 인지 최소가: "70만원 AI용"처럼 GPU가 필수인 용도는 GPU 포함 최소 구성가로 계산한다.
            // (라우팅이 아니라 이미 선택된 응답의 숫자를 정확하게 만드는 보정 — 키워드 오인 시에도 방향은 보수적)
            List<String> inferredUsage = inferUsageTags(message);
            boolean gpuRequired = BuildChatFeasibilityService.requiresGpu(inferredUsage);
            int minimumTotal = gpuRequired ? feasibilityService.usageMinimumTotal(inferredUsage) : minimumBuildTotal();
            boolean belowFeasibleRange = minimumTotal > 0
                    && ("MAX".equals(rawBudgetIntent.mode()) && budget < minimumTotal
                            || "TARGET".equals(rawBudgetIntent.mode())
                                    && Math.ceil(budget * TARGET_BUDGET_BAND_UPPER) < minimumTotal);
            if (belowFeasibleRange) {
                // 불가능한 예산에서는 계약 범위를 어긴 카드를 억지로 노출하지 않는다. DB 최소가와
                // 부족액을 제시하고, 사용자가 누를 수 있는 다음 예산 제안으로 대화를 잇는다.
                List<String> warnings = new ArrayList<>();
                warnings.add(gpuRequired ? "BUDGET_BELOW_USAGE_MINIMUM" : "BUDGET_BELOW_MINIMUM");
                String guidance = gpuRequired
                        ? usageLabel(inferredUsage) + " PC는 그래픽카드가 필요해 내부 자산 기준 최소 약 "
                                + formatBudgetLabel(minimumTotal) + "부터 가능합니다. 요청 예산("
                                + formatBudgetLabel(budget) + ")과는 약 "
                                + formatBudgetLabel(Math.max(0, minimumTotal - budget)) + " 차이가 납니다."
                        : "요청 예산(" + formatBudgetLabel(budget) + ")으로는 내부 자산 기준 완전한 구성이 어렵습니다. "
                                + "가능한 최소 구성은 약 " + formatBudgetLabel(minimumTotal) + "부터이며, 약 "
                                + formatBudgetLabel(Math.max(0, minimumTotal - budget)) + " 부족합니다.";
                Map<String, Object> response = fastResponse("GENERAL", guidance, warnings);
                int suggested = ((minimumTotal + 99_999) / 100_000) * 100_000;
                response.put("quickReplies", gpuRequired
                        ? List.of(
                                formatBudgetLabel(suggested) + " " + usageLabel(inferredUsage) + " PC 추천해줘",
                                formatBudgetLabel(budget) + " 사무용 PC 추천해줘")
                        : List.of(
                                formatBudgetLabel(suggested) + " 가능한 최소 구성으로 추천해줘",
                                "예산 범위를 넓혀서 추천해줘"));
                return Optional.of(response);
            }
        }
        // 무예산 용도-only 요청("게임용 컴퓨터 추천")은 딱딱한 폴백 3장 즉답을 만들지 않고 LLM 되묻기
        // 경로로 흘린다 — 명시 예산이 있을 때만 결정적 즉답(예산 폴백 조합)을 구성한다.
        if (rawBudgetIntent != null && rawBudgetIntent.hasBudget()
                && isStandaloneBuildRecommend(message, request, rawBudgetIntent)) {
            AiChatEngineResponse engineResponse = new AiChatEngineResponse(
                    "내부 자산과 자동 검증 기준으로 바로 추천 조합을 구성했습니다.",
                    AiChatIntent.FULL_BUILD_RECOMMEND,
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    null
            );
            List<String> warnings = new ArrayList<>();
            BuildChatGuardStats stats = new BuildChatGuardStats();
            List<Map<String, Object>> builds = rawBudgetIntent != null && rawBudgetIntent.hasBudget()
                    ? budgetFallbackBuilds(engineResponse, rawBudgetIntent, warnings, stats)
                    : openBudgetFallbackBuilds(warnings);
            if (!builds.isEmpty()) {
                return Optional.of(fastResponse(
                        "BUDGET",
                        "내부 자산과 자동 검증 기준으로 추천 조합 " + builds.size() + "개를 바로 구성했습니다.",
                        builds,
                        warnings
                ));
            }
        }
        return Optional.empty();
    }

    private List<Map<String, Object>> openBudgetFallbackBuilds(List<String> warnings) {
        List<List<PartCandidate>> groups = new ArrayList<>();
        for (String category : fallbackCategories(true, true)) {
            List<PartCandidate> candidates = partRecommendations(category, 8).stream()
                    .map(this::partCandidate)
                    .toList();
            if (candidates.isEmpty()) {
                return List.of();
            }
            groups.add(candidates);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        int maxCandidates = groups.stream().mapToInt(List::size).max().orElse(0);
        for (int offset = 0; offset < maxCandidates && result.size() < 3; offset += 1) {
            List<PartCandidate> parts = new ArrayList<>();
            for (List<PartCandidate> group : groups) {
                parts.add(group.get(Math.min(offset, group.size() - 1)));
            }
            String key = parts.stream().map(PartCandidate::publicId).toList().toString();
            if (!seenKeys.add(key)) {
                continue;
            }
            List<String> localWarnings = new ArrayList<>();
            List<Map<String, Object>> toolResults = toolResults(parts, totalPrice(parts), localWarnings);
            if (hasBlockingToolFailure(toolResults)) {
                continue;
            }
            localWarnings.addAll(toolWarnings(toolResults));
            Tier tier = TIERS.get(Math.min(result.size(), TIERS.size() - 1));
            result.add(openBudgetFallbackBuildMap(tier, parts, toolResults, localWarnings));
        }
        if (!result.isEmpty()) {
            warnings.add("빠른 응답을 위해 내부 자산과 자동 검증 기준으로 추천 조합을 즉시 구성했습니다.");
        }
        return result;
    }

    private Map<String, Object> openBudgetFallbackBuildMap(
            Tier tier,
            List<PartCandidate> parts,
            List<Map<String, Object>> toolResults,
            List<String> warnings
    ) {
        int totalPrice = totalPrice(parts);
        List<Map<String, Object>> items = parts.stream()
                .map(part -> partItem(part, "내부 자산 빠른 추천"))
                .toList();
        Map<String, Object> build = MockData.map(
                "id", "ai-engine-fast-open-budget-" + tier.id() + "-" + Math.abs(items.hashCode()),
                "tier", tier.id(),
                "label", tier.label(),
                "title", tier.title() + " 빠른 추천 조합",
                "summary", "현재 판매 중인 내부 자산과 자동 검증을 기준으로 빠르게 구성했습니다.",
                "recommendedFor", List.of("빠른 추천", "내부 자산", "자동 검증"),
                "totalPrice", totalPrice,
                "badges", List.of(tier.title(), "OPEN_BUDGET", "FAST"),
                "budgetWon", totalPrice,
                "budgetLabel", "예산 미지정",
                "tierLabel", tier.title(),
                "appliedPartCategories", List.of(),
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(warnings),
                "confidence", confidence(toolResults, warnings),
                "evidenceIds", List.of()
        );
        applyCompositeTier(
                build,
                parts.stream().map(part -> toolPart(part, defaultQuantity(part))).toList(),
                toolResults,
                totalPrice,
                true
        );
        return build;
    }

    private List<Map<String, Object>> engineBuilds(
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent,
            List<String> warnings,
            BuildChatGuardStats guardStats
    ) {
        List<AiChatEngineResponse.BuildRecommendation> recommendations = engineResponse.recommendations();
        if (recommendations == null || recommendations.isEmpty()) {
            return budgetFallbackBuilds(engineResponse, rawBudgetIntent, warnings, guardStats);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < recommendations.size(); index += 1) {
            Map<String, Object> build = engineBuildMap(recommendations.get(index), index, engineResponse, rawBudgetIntent, warnings);
            if (hasBlockingToolFailure(objectMaps(build.get("toolResults")))) {
                guardStats.blockingFailDropped += 1;
                warnings.add("자동 검증에서 장착/호환/전력 불가로 판정된 추천 조합을 제외했습니다.");
                continue;
            }
            if (!withinBudgetGuard(build, engineResponse.parsedContext(), rawBudgetIntent)) {
                guardStats.budgetGuardDropped += 1;
                warnings.add("명시 예산 범위를 벗어난 추천 조합을 제외했습니다.");
                continue;
            }
            result.add(build);
        }
        if (!result.isEmpty()) {
            return result;
        }
        return budgetFallbackBuilds(engineResponse, rawBudgetIntent, warnings, guardStats);
    }

    private List<Map<String, Object>> budgetFallbackBuilds(
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent,
            List<String> warnings,
            BuildChatGuardStats guardStats
    ) {
        Map<String, Object> parsedContext = engineResponse.parsedContext() == null ? Map.of() : engineResponse.parsedContext();
        if (rawBudgetIntent == null || !rawBudgetIntent.hasBudget() || hasEffectiveHardConstraint(parsedContext, rawBudgetIntent)) {
            return List.of();
        }

        int budgetWon = rawBudgetIntent.budget();
        if ("MIN".equals(rawBudgetIntent.mode())) {
            List<Map<String, Object>> minimumBuilds = nearBudgetLadderBuilds(
                    budgetWon, "MIN", engineResponse.evidenceIds(), warnings, guardStats);
            if (minimumBuilds.isEmpty()) {
                warnings.add("요청 예산 하한 이상의 자동 검증 통과 조합을 내부 자산에서 찾지 못했습니다.");
            } else {
                warnings.add("요청 예산 하한을 지키도록 내부 자산 기준 보조 견적을 재구성했습니다.");
            }
            return minimumBuilds;
        }

        // TARGET: 계약 밴드(±12.5%) 안 조합만 / MAX: 예산을 넘지 않으면서 최대한 근접한 조합을 사다리로 찾는다
        List<Map<String, Object>> ladder = nearBudgetLadderBuilds(budgetWon, rawBudgetIntent.mode(), engineResponse.evidenceIds(), warnings, guardStats);
        if (!ladder.isEmpty()) {
            warnings.add("명시 예산 범위에 맞춰 내부 자산 기준 보조 견적을 재구성했습니다.");
        }
        return ladder;
    }

    // 3개 추천의 다양성을 위한 타깃 예산 비율(MAX/완화 모드): 가성비 / 균형 / 예산 근접.
    // 계약상 MAX(이하) 모드는 "예산 이하 우선"만 요구하므로 가격대를 넓게 편다.
    // 느슨한 가격 밴드 탐색은 가지치기가 무력해져 수십 초가 걸리므로,
    // 타깃 예산을 낮춰 "타깃의 87.5%~100%" 타이트한 탐색을 3번 수행한다.
    private static final double[] NEAR_BUDGET_TIER_TARGETS = {0.55, 0.75, 1.0};
    // TARGET(명시 예산) 모드 타깃 비율: 계약(docs/API_CONTRACT.md)이 총액을 예산의 87.5%~112.5%로
    // 규정하므로, 다양성은 가격 분산이 아니라 밴드 안 구성 차이로 만든다.
    private static final double[] TARGET_BAND_TIER_TARGETS = {0.90, 0.96, 1.0};
    // MIN(이상/최소/부터)은 하한만 강제한다. 고가 부품 간 가격 간격이 큰 경우에도 하한을
    // 넘는 조합을 찾을 수 있도록 상향 탐색하되, 하한 미달 조합은 절대 반환하지 않는다.
    private static final double[] MIN_BUDGET_TIER_TARGETS = {1.0, 1.25, 1.5, 2.0};
    // 명시 예산 target 모드 계약 밴드(±12.5%) — LLM 경로 최종 필터(withinBudgetGuard),
    // 결정적 사다리, 티어 스냅샷 서빙 게이트가 같은 값을 공유한다.
    static final double TARGET_BUDGET_BAND_LOWER = 0.875;
    static final double TARGET_BUDGET_BAND_UPPER = 1.125;

    private static boolean withinTargetBudgetBand(Integer totalPrice, int budgetWon) {
        return totalPrice != null
                && totalPrice >= Math.floor(budgetWon * TARGET_BUDGET_BAND_LOWER)
                && totalPrice <= Math.ceil(budgetWon * TARGET_BUDGET_BAND_UPPER);
    }

    private List<Map<String, Object>> nearBudgetLadderBuilds(
            int budgetWon,
            String budgetMode,
            List<String> evidenceIds,
            List<String> warnings,
            BuildChatGuardStats guardStats
    ) {
        // TARGET 모드는 계약 밴드(±12.5%) 밖 카드를 반환하지 않는다 — 밴드 밖 3장보다 밴드 안 2장이 낫다.
        boolean targetBand = "TARGET".equals(budgetMode);
        boolean minimumFloor = "MIN".equals(budgetMode);
        double[] tierTargets = targetBand
                ? TARGET_BAND_TIER_TARGETS
                : minimumFloor ? MIN_BUDGET_TIER_TARGETS : NEAR_BUDGET_TIER_TARGETS;
        // 타깃 예산별로 1개씩 뽑아 구성 다양성을 확보한다
        LinkedHashMap<String, Map<String, Object>> byComposition = new LinkedHashMap<>();
        for (double targetRatio : tierTargets) {
            int targetBudget = (int) Math.floor(budgetWon * targetRatio);
            List<Map<String, Object>> targetResult = singleTargetLadderBuilds(targetBudget, budgetMode, budgetWon, evidenceIds);
            if (!targetResult.isEmpty()) {
                Map<String, Object> build = targetResult.get(0);
                if (!satisfiesBudgetMode(build, budgetWon, budgetMode)
                        || hasBlockingToolFailure(objectMaps(build.get("toolResults")))) {
                    continue;
                }
                byComposition.putIfAbsent(buildItemsKey(build), build);
            }
        }

        // 부족분 보충: TARGET도 사용자 예산 안에서만 찾는다. 계약상 +12.5%까지 허용되더라도
        // BuildGraph price Tool은 명시 예산 초과를 FAIL로 판정하므로, 응답/재검증 불일치를 만들지 않는다.
        if (byComposition.size() < 3) {
            int supplementTarget = targetBand
                    ? budgetWon
                    : minimumFloor
                            ? (int) Math.min(Integer.MAX_VALUE, Math.floor(budgetWon * 2.5))
                            : budgetWon;
            for (Map<String, Object> build : singleTargetLadderBuilds(supplementTarget, budgetMode, budgetWon, evidenceIds)) {
                if (byComposition.size() >= 3) {
                    break;
                }
                if (!satisfiesBudgetMode(build, budgetWon, budgetMode)
                        || hasBlockingToolFailure(objectMaps(build.get("toolResults")))) {
                    continue;
                }
                byComposition.putIfAbsent(buildItemsKey(build), build);
            }
        }

        if (byComposition.isEmpty()) {
            if (minimumFloor) {
                return List.of();
            }
            // 사다리가 전부 빈손이어도 예산이 최소 구성가 이상이면, 이미 검증된 최소 구성 경로로
            // '가능한 최소 구성' 카드 1개는 제공해 빈 화면을 막는다.
            int minimumTotal = minimumBuildTotal();
            if (minimumTotal > 0 && budgetWon >= minimumTotal) {
                Optional<GreedyBuild> minimumBuild = greedyTargetBuild(
                        (int) Math.round(minimumTotal * 1.25), (int) Math.round(minimumTotal * 1.25), false);
                if (minimumBuild.isPresent()) {
                    GreedyBuild greedy = minimumBuild.get();
                    Map<String, Object> build = budgetFallbackBuildMap(
                            TIERS.get(0), greedy.parts(), new BudgetIntent(minimumTotal, "MIN", false, false), 1,
                            greedy.toolResults(), greedy.warnings(), evidenceIds);
                    build.put("title", "가능한 최소 구성");
                    build.put("summary", "현재 판매 중인 내부 자산으로 구성 가능한 가장 저렴한 조합입니다. 그래픽카드는 제외되어 있습니다.");
                    if (!satisfiesBudgetMode(build, budgetWon, budgetMode)
                            || hasBlockingToolFailure(objectMaps(build.get("toolResults")))) {
                        return List.of();
                    }
                    warnings.add("예산에 근접한 조합을 내부 자산으로 구성하지 못해, 구성 가능한 범위에서 예산 이하 최대 조합을 추천합니다.");
                    if (guardStats != null) {
                        guardStats.routeFallbackUsed = true;
                    }
                    return List.of(build);
                }
            }
            return List.of();
        }
        List<Map<String, Object>> builds = new ArrayList<>(byComposition.values());
        // 카드 순서는 가격 비교가 쉽도록 유지하되 티어/라벨은 각 조합의 1000점 평가로 이미 결정됐다.
        builds.sort(java.util.Comparator.comparingInt(build -> {
            Integer total = numberValue(build.get("totalPrice"));
            return total == null ? 0 : total;
        }));
        int bestTotal = builds.stream()
                .map(build -> numberValue(build.get("totalPrice")))
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        if (!minimumFloor && bestTotal < Math.floor(budgetWon * TARGET_BUDGET_BAND_LOWER)) {
            warnings.add("예산에 근접한 조합을 내부 자산으로 구성하지 못해, 구성 가능한 범위에서 예산 이하 최대 조합을 추천합니다.");
        }
        if (guardStats != null) {
            guardStats.routeFallbackUsed = true;
        }
        return builds.stream().limit(3).toList();
    }

    // 카테고리별 예산 비중 — 그리디 초기 배분 기준
    private static final Map<String, Double> BUDGET_SHARE_WITH_GPU = Map.of(
            "CPU", 0.20, "MOTHERBOARD", 0.11, "RAM", 0.08, "GPU", 0.33,
            "STORAGE", 0.08, "PSU", 0.07, "CASE", 0.06, "COOLER", 0.07);
    private static final Map<String, Double> BUDGET_SHARE_NO_GPU = Map.of(
            "CPU", 0.32, "MOTHERBOARD", 0.18, "RAM", 0.14, "STORAGE", 0.14, "PSU", 0.11, "CASE", 0.11);
    private static final List<String> GREEDY_PICK_ORDER =
            List.of("CPU", "MOTHERBOARD", "RAM", "COOLER", "GPU", "PSU", "CASE", "STORAGE");
    private static final List<String> GREEDY_UPGRADE_ORDER = List.of("GPU", "CPU", "STORAGE", "RAM");

    /**
     * 타깃 예산에 근접한 조합 1개를 그리디로 구성한다.
     * 조합 DFS는 소켓 비호환 조합마다 Tool 검증(SQL)을 반복해 수십 초가 걸렸다 —
     * 여기서는 비중 배분 + 호환 프리필터로 후보를 만들고 Tool 검증은 1~4회만 수행한다.
     */
    private List<Map<String, Object>> singleTargetLadderBuilds(
            int targetBudget,
            String budgetMode,
            int displayBudgetWon,
            List<String> evidenceIds
    ) {
        if (targetBudget <= 0) {
            return List.of();
        }
        boolean includeGpu = targetBudget >= 1_000_000;
        Optional<GreedyBuild> build = greedyTargetBuild(targetBudget, targetBudget, includeGpu);
        if (build.isEmpty()) {
            return List.of();
        }
        GreedyBuild greedy = build.get();
        // 카드에 노출하는 예산/모드: TARGET은 사용자 명시 예산 그대로, 그 외(MAX/완화)는 기존처럼
        // 사다리 타깃 예산을 MAX로 표기한다.
        boolean targetBand = "TARGET".equals(budgetMode);
        List<String> displayWarnings = new ArrayList<>(greedy.warnings());
        List<Map<String, Object>> displayToolResults = targetBand
                ? toolResults(greedy.parts(), displayBudgetWon, displayWarnings)
                : greedy.toolResults();
        Map<String, Object> map = budgetFallbackBuildMap(
                TIERS.get(1),
                greedy.parts(),
                targetBand
                        ? new BudgetIntent(displayBudgetWon, "TARGET", false, false)
                        : new BudgetIntent(targetBudget, "MAX", false, false),
                2,
                displayToolResults,
                displayWarnings,
                evidenceIds
        );
        if (!satisfiesBudgetMode(map, displayBudgetWon, budgetMode)
                || hasBlockingToolFailure(objectMaps(map.get("toolResults")))) {
            return List.of();
        }
        return List.of(map);
    }

    private record GreedyBuild(List<PartCandidate> parts, List<Map<String, Object>> toolResults, List<String> warnings) {
    }

    private Optional<GreedyBuild> greedyTargetBuild(int targetBudget, int poolUpperBound, boolean includeGpu) {
        Map<String, Double> shares = includeGpu ? BUDGET_SHARE_WITH_GPU : BUDGET_SHARE_NO_GPU;
        LinkedHashMap<String, List<PartCandidate>> pools = new LinkedHashMap<>();
        for (String category : fallbackCategories(includeGpu, includeGpu)) {
            List<PartCandidate> pool = nearBudgetPartCandidates(category, 8, poolUpperBound);
            if (pool.isEmpty()) {
                return Optional.empty();
            }
            pools.put(category, pool);
        }

        LinkedHashMap<String, PartCandidate> chosen = new LinkedHashMap<>();
        for (String category : GREEDY_PICK_ORDER) {
            List<PartCandidate> pool = pools.get(category);
            if (pool == null) {
                continue;
            }
            List<PartCandidate> filtered = compatibleCandidates(pool, category, chosen);
            if (filtered.isEmpty()) {
                filtered = pool;
            }
            int shareBudget = (int) Math.floor(targetBudget * shares.getOrDefault(category, 0.05));
            chosen.put(category, pickNearestPrice(filtered, shareBudget));
        }

        // 예산 초과 시 가장 큰 절감이 가능한 카테고리부터 한 단계씩 하향
        for (int guard = 0; guard < 32 && greedyTotal(chosen) > targetBudget; guard += 1) {
            if (!shiftOneStep(pools, chosen, targetBudget, false)) {
                return Optional.empty();
            }
        }
        if (greedyTotal(chosen) > targetBudget) {
            return Optional.empty();
        }
        // 남은 예산은 GPU→CPU→저장장치→램 순으로 상향해 타깃에 근접시킨다
        for (int guard = 0; guard < 32; guard += 1) {
            if (!shiftOneStep(pools, chosen, targetBudget, true)) {
                break;
            }
        }

        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> toolResults = toolResults(new ArrayList<>(chosen.values()), targetBudget, warnings);
        for (int attempt = 0; attempt < 3 && hasBlockingToolFailure(toolResults); attempt += 1) {
            if (!repairBlockingFailure(pools, chosen, toolResults, targetBudget)) {
                return Optional.empty();
            }
            warnings = new ArrayList<>();
            toolResults = toolResults(new ArrayList<>(chosen.values()), targetBudget, warnings);
        }
        if (hasBlockingToolFailure(toolResults)) {
            return Optional.empty();
        }
        warnings.addAll(toolWarnings(toolResults));
        return Optional.of(new GreedyBuild(new ArrayList<>(chosen.values()), toolResults, warnings));
    }

    // upgrade=false: 예산 초과 해소(최대 절감 스왑), upgrade=true: 예산 내 최대 상향(우선순위 카테고리)
    private boolean shiftOneStep(
            Map<String, List<PartCandidate>> pools,
            LinkedHashMap<String, PartCandidate> chosen,
            int targetBudget,
            boolean upgrade
    ) {
        List<String> categories = upgrade ? GREEDY_UPGRADE_ORDER : new ArrayList<>(chosen.keySet());
        String bestCategory = null;
        PartCandidate bestCandidate = null;
        long bestDelta = 0;
        for (String category : categories) {
            PartCandidate current = chosen.get(category);
            List<PartCandidate> pool = pools.get(category);
            if (current == null || pool == null) {
                continue;
            }
            int currentIndex = pool.indexOf(current);
            int nextIndex = upgrade ? currentIndex + 1 : currentIndex - 1;
            while (nextIndex >= 0 && nextIndex < pool.size()) {
                PartCandidate candidate = pool.get(nextIndex);
                long delta = completionCandidatePrice(candidate) - completionCandidatePrice(current);
                LinkedHashMap<String, PartCandidate> trial = new LinkedHashMap<>(chosen);
                trial.put(category, candidate);
                boolean fitsBudget = !upgrade || greedyTotal(trial) <= targetBudget;
                if (fitsBudget && pairwiseCompatible(trial)) {
                    if (upgrade) {
                        // 상향은 우선순위 첫 후보를 즉시 적용
                        chosen.put(category, candidate);
                        return true;
                    }
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestCategory = category;
                        bestCandidate = candidate;
                    }
                    break;
                }
                nextIndex = upgrade ? nextIndex + 1 : nextIndex - 1;
            }
        }
        if (!upgrade && bestCategory != null) {
            chosen.put(bestCategory, bestCandidate);
            return true;
        }
        return false;
    }

    // Tool FAIL 종류별 표적 교체: 전력→PSU 상향, 규격→케이스 상향, 호환→보드/쿨러/램 재선택
    private boolean repairBlockingFailure(
            Map<String, List<PartCandidate>> pools,
            LinkedHashMap<String, PartCandidate> chosen,
            List<Map<String, Object>> toolResults,
            int targetBudget
    ) {
        for (Map<String, Object> result : toolResults) {
            if (!"FAIL".equals(text(result.get("status")))) {
                continue;
            }
            String tool = text(result.get("tool"));
            List<String> targets = switch (firstText(tool, "")) {
                case "power" -> List.of("PSU", "GPU");
                case "size" -> List.of("CASE", "GPU", "COOLER");
                default -> List.of("MOTHERBOARD", "COOLER", "RAM");
            };
            for (String category : targets) {
                PartCandidate current = chosen.get(category);
                List<PartCandidate> pool = pools.get(category);
                if (current == null || pool == null) {
                    continue;
                }
                LinkedHashMap<String, PartCandidate> without = new LinkedHashMap<>(chosen);
                without.remove(category);
                for (PartCandidate candidate : compatibleCandidates(pool, category, without)) {
                    if (candidate == current) {
                        continue;
                    }
                    LinkedHashMap<String, PartCandidate> trial = new LinkedHashMap<>(chosen);
                    trial.put(category, candidate);
                    if (greedyTotal(trial) <= targetBudget && pairwiseCompatible(trial)) {
                        chosen.put(category, candidate);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long greedyTotal(Map<String, PartCandidate> chosen) {
        return chosen.values().stream().mapToLong(BuildChatService::completionCandidatePrice).sum();
    }

    private static PartCandidate pickNearestPrice(List<PartCandidate> pool, int shareBudget) {
        PartCandidate best = pool.get(0);
        long bestDistance = Long.MAX_VALUE;
        for (PartCandidate candidate : pool) {
            long distance = Math.abs(completionCandidatePrice(candidate) - (long) shareBudget);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    // ToolCheckService의 blocking 규칙(소켓/메모리/쿨러/전력/규격)을 값싼 속성 비교로 선반영한다
    private static List<PartCandidate> compatibleCandidates(
            List<PartCandidate> pool,
            String category,
            Map<String, PartCandidate> chosen
    ) {
        return pool.stream().filter(candidate -> {
            LinkedHashMap<String, PartCandidate> trial = new LinkedHashMap<>(chosen);
            trial.put(category, candidate);
            return pairwiseCompatible(trial);
        }).toList();
    }

    private static boolean pairwiseCompatible(Map<String, PartCandidate> chosen) {
        PartCandidate cpu = chosen.get("CPU");
        PartCandidate motherboard = chosen.get("MOTHERBOARD");
        PartCandidate ram = chosen.get("RAM");
        PartCandidate cooler = chosen.get("COOLER");
        PartCandidate gpu = chosen.get("GPU");
        PartCandidate psu = chosen.get("PSU");
        PartCandidate pcCase = chosen.get("CASE");

        String cpuSocket = cpu == null ? null : firstAttributeText(cpu, "socket");
        String boardSocket = motherboard == null ? null : firstAttributeText(motherboard, "socket");
        if (cpuSocket != null && boardSocket != null && !cpuSocket.equalsIgnoreCase(boardSocket)) {
            return false;
        }
        String ramType = ram == null ? null : firstAttributeText(ram, "memoryType");
        String boardMemory = motherboard == null ? null : firstAttributeText(motherboard, "memoryType");
        if (ramType != null && boardMemory != null && !ramType.equalsIgnoreCase(boardMemory)) {
            return false;
        }
        if (cooler != null && cpuSocket != null && cooler.attributes().get("socketSupport") instanceof List<?> supports
                && !supports.isEmpty()
                && supports.stream().noneMatch(item -> cpuSocket.equalsIgnoreCase(String.valueOf(item)))) {
            return false;
        }
        Integer psuCapacity = psu == null ? null : firstAttributeNumber(psu, "capacityW");
        Integer gpuRequired = gpu == null ? null : firstAttributeNumber(gpu, "requiredSystemPowerW");
        if (psuCapacity != null && gpuRequired != null && psuCapacity < gpuRequired) {
            return false;
        }
        Integer gpuLength = gpu == null ? null : firstAttributeNumber(gpu, "lengthMm");
        Integer maxGpuLength = pcCase == null ? null : firstAttributeNumber(pcCase, "maxGpuLengthMm");
        if (gpuLength != null && maxGpuLength != null && maxGpuLength > 0 && gpuLength > maxGpuLength) {
            return false;
        }
        Integer coolerHeight = cooler == null ? null : firstAttributeNumber(cooler, "heightMm", "coolerHeightMm");
        Integer maxCoolerHeight = pcCase == null ? null : firstAttributeNumber(pcCase, "maxCpuCoolerHeightMm");
        if (coolerHeight != null && maxCoolerHeight != null && maxCoolerHeight > 0 && coolerHeight > maxCoolerHeight) {
            return false;
        }
        return true;
    }

    private static String buildItemsKey(Map<String, Object> build) {
        return objectMaps(build.get("items")).stream()
                .map(item -> item.get("partId") + ":" + item.get("quantity"))
                .sorted()
                .toList()
                .toString();
    }

    private void applyCompositeTier(
            Map<String, Object> build,
            List<ToolBuildPart> parts,
            List<Map<String, Object>> toolResults,
            int requestedBudget,
            boolean rewriteTitle
    ) {
        BuildEvaluationService.BuildEvaluation evaluation = buildEvaluationService.evaluateSnapshot(
                parts,
                toolResults,
                requestedBudget,
                null,
                null
        );
        Map<String, Object> compositeScore = evaluation.compositeScore();
        int score = numberValue(compositeScore.get("score"));
        String scoreLabel = firstText(text(compositeScore.get("label")), "검토 필요");
        Tier tier = score >= 850 ? TIERS.get(2) : score >= 750 ? TIERS.get(1) : TIERS.get(0);
        String cardLabel = score >= 850 ? "고성능" : score >= 750 ? "균형" : scoreLabel;

        build.put("tier", tier.id());
        build.put("label", cardLabel);
        build.put("tierLabel", scoreLabel);
        if (rewriteTitle) {
            build.put("title", scoreLabel + " 예산 맞춤 조합");
        }
        List<String> badges = new ArrayList<>(stringList(build.get("badges")));
        badges.removeIf(value -> "가성비형".equals(value) || "균형형".equals(value) || "고성능형".equals(value));
        badges.add(0, scoreLabel);
        build.put("badges", distinct(badges));
    }

    private Map<String, Object> budgetFallbackBuildMap(
            Tier tier,
            List<PartCandidate> parts,
            BudgetIntent rawBudgetIntent,
            int ramQuantity,
            List<Map<String, Object>> toolResults,
            List<String> warnings,
            List<String> evidenceIds
    ) {
        int totalPrice = totalPrice(parts, ramQuantity);
        List<Map<String, Object>> items = parts.stream()
                .map(part -> partItem(part, "명시 예산 기준 내부 자산 보조 추천", quantityForBudgetFallback(part, ramQuantity)))
                .toList();
        Map<String, Object> build = MockData.map(
                "id", "ai-engine-budget-fallback-" + tier.id() + "-" + Math.abs(items.hashCode()),
                "tier", tier.id(),
                "label", tier.label(),
                "title", tier.title() + " 예산 맞춤 조합",
                "summary", "명시 예산 범위를 우선해 현재 판매 중인 내부 자산과 자동 검증 기준으로 재구성했습니다.",
                "recommendedFor", List.of("명시 예산", "내부 자산", "자동 검증"),
                "totalPrice", totalPrice,
                "badges", List.of(tier.title(), rawBudgetIntent.mode(), "BUDGET_GUARD"),
                "budgetWon", rawBudgetIntent.budget(),
                "budgetLabel", formatBudgetLabel(rawBudgetIntent.budget()),
                "tierLabel", tier.title(),
                "appliedPartCategories", List.of(),
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(warnings),
                "confidence", confidence(toolResults, warnings),
                "evidenceIds", evidenceIds == null ? List.of() : evidenceIds
        );
        applyCompositeTier(
                build,
                parts.stream()
                        .map(part -> toolPart(part, quantityForBudgetFallback(part, ramQuantity)))
                        .toList(),
                toolResults,
                rawBudgetIntent.budget(),
                true
        );
        return build;
    }

    private Map<String, Object> engineBuildMap(
            AiChatEngineResponse.BuildRecommendation recommendation,
            int index,
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent,
            List<String> warnings
    ) {
        Tier tier = TIERS.get(Math.max(0, Math.min(index, TIERS.size() - 1)));
        List<PartCandidate> parts = recommendation.items().stream()
                .map(this::partCandidate)
                .toList();
        Map<String, Object> parsedContext = engineResponse.parsedContext() == null ? Map.of() : engineResponse.parsedContext();
        int totalPrice = totalPrice(parts, parsedContext);
        Integer userBudget = effectiveBudget(parsedContext, rawBudgetIntent);
        boolean hardConstraintOverBudget = userBudget != null
                && totalPrice > userBudget
                && hasEffectiveHardConstraint(parsedContext, rawBudgetIntent);
        int toolBudget = toolBudgetForBuild(totalPrice, userBudget, rawBudgetIntent, hardConstraintOverBudget);
        List<Map<String, Object>> toolResults = toolResults(parts, toolBudget, warnings);
        List<String> buildWarnings = new ArrayList<>(toolWarnings(toolResults));
        if (hardConstraintOverBudget) {
            buildWarnings.add("HARD_CONSTRAINT_OVER_BUDGET");
            buildWarnings.add("명시한 부품 조건을 지키기 위해 예산을 초과했습니다.");
        }
        List<Map<String, Object>> items = parts.stream()
                .map(part -> partItem(part, "AI 엔진 내부 자산 추천", quantityForRecommendation(part, parsedContext)))
                .toList();
        Map<String, Object> build = MockData.map(
                "id", "ai-engine-" + (index + 1) + "-" + slug(recommendation.name()),
                "tier", tier.id(),
                "label", tier.label(),
                "title", recommendation.name(),
                "summary", recommendation.summary(),
                "recommendedFor", recommendation.recommendedFor(),
                "totalPrice", totalPrice,
                "badges", badges(tier.title(), parsedContext),
                "budgetWon", toolBudget,
                "budgetLabel", userBudget == null ? "예산 미지정" : formatBudgetLabel(userBudget),
                "tierLabel", tier.title(),
                "appliedPartCategories", List.of(),
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(buildWarnings),
                "confidence", firstText(recommendation.confidence(), confidence(toolResults, buildWarnings)),
                "evidenceIds", engineResponse.evidenceIds()
        );
        applyCompositeTier(
                build,
                parts.stream()
                        .map(part -> toolPart(part, quantityForRecommendation(part, parsedContext)))
                        .toList(),
                toolResults,
                toolBudget,
                false
        );
        return build;
    }

    private PartCandidate partCandidateFromDraftItem(Map<String, Object> item) {
        String partId = text(item.get("partId"));
        String category = text(item.get("category"));
        if (partId == null || category == null) {
            return null;
        }
        return new PartCandidate(
                null,
                partId,
                category,
                firstText(text(item.get("name")), categoryLabel(category)),
                text(item.get("manufacturer")),
                firstNumber(item.get("currentPrice"), item.get("price"), item.get("unitPriceAtAdd"), item.get("lineTotal")) == null
                        ? 0
                        : firstNumber(item.get("currentPrice"), item.get("price"), item.get("unitPriceAtAdd"), item.get("lineTotal")),
                objectMap(item.get("attributes"))
        );
    }

    private static boolean hasBlockingToolFailure(List<Map<String, Object>> toolResults) {
        return toolResults.stream()
                .anyMatch(result -> "FAIL".equals(text(result.get("status"))));
    }

    private Map<String, Object> findDraftItem(List<Map<String, Object>> draftItems, String category, String message) {
        if (category != null) {
            return draftItems.stream()
                    .filter(item -> category.equals(text(item.get("category"))))
                    .findFirst()
                    .orElse(Map.of());
        }
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return draftItems.stream()
                .filter(item -> normalized.contains(String.valueOf(item.get("name")).toLowerCase(Locale.ROOT))
                        || normalized.contains(String.valueOf(item.get("manufacturer")).toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(Map.of());
    }

    private static boolean containsAnyNormalized(String normalized, String... keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(normalizeCommand(keyword))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeCommand(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private void logBuildChatPath(
            String pathType,
            long startedNanos,
            Long userId,
            String requestedAiProfile,
            boolean cacheHit,
            BuildChatGuardStats guardStats
    ) {
        logBuildChatPath(pathType, startedNanos, userId, requestedAiProfile, cacheHit, guardStats, null);
    }

    private void logBuildChatPath(
            String pathType,
            long startedNanos,
            Long userId,
            String requestedAiProfile,
            boolean cacheHit,
            BuildChatGuardStats guardStats,
            String stageSummary
    ) {
        long latencyMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        BuildChatGuardStats stats = guardStats == null ? BuildChatGuardStats.empty() : guardStats;
        log.info(
                "Build Chat pathType={} latencyMs={} userId={} requestedAiProfile={} cacheHit={} budgetGuardDropped={} blockingFailDropped={} routeFallbackUsed={} stages=[{}]",
                pathType,
                latencyMs,
                userId,
                requestedAiProfile,
                cacheHit,
                stats.budgetGuardDropped,
                stats.blockingFailDropped,
                stats.routeFallbackUsed,
                stageSummary == null ? "" : stageSummary
        );
    }

    static Integer parseCapacityGb(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        // TB 표기는 1TB=1000GB로 환산해 용량 해상에 태운다("4TB"→4000GB).
        Matcher tb = CAPACITY_TB_PATTERN.matcher(normalized);
        if (tb.find()) {
            return Integer.parseInt(tb.group(1)) * 1000;
        }
        Matcher matcher = CAPACITY_GB_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static Integer parseWattage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Matcher matcher = WATT_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private List<Map<String, Object>> toolResults(List<PartCandidate> parts, int budgetWon, List<String> warnings) {
        return toolResults(parts, Map.of(), budgetWon, warnings);
    }

    // 드래프트 기반 경로는 수량이 실재하므로 검증에도 반영한다(예: RAM 스틱 수·전력 합산).
    // quantities는 publicId(없으면 name) → 수량 맵이다. 추천 부품에 명시 수량이 없으면 응답 카드와
    // 동일한 기본 수량을 사용해 Tool 통과 카드가 적용 후 FAIL로 뒤집히지 않게 한다.
    private List<Map<String, Object>> toolResults(List<PartCandidate> parts, Map<String, Integer> quantities, int budgetWon, List<String> warnings) {
        try {
            List<ToolBuildPart> toolParts = parts.stream()
                    .map(part -> toolPart(part, quantities.getOrDefault(
                            part.publicId() == null ? part.name() : part.publicId(),
                            defaultQuantity(part))))
                    .toList();
            return ToolApplicabilityPolicy.applicableToolResults(
                    toolCheckService.checkBuild(toolParts, budgetWon),
                    toolParts
            );
        } catch (RuntimeException error) {
            log.warn("Tool check failed while preparing build chat recommendation", error);
            warnings.add("자동 검증을 완료하지 못해 검증 결과 없이 추천을 표시합니다.");
            return List.of();
        }
    }

    private List<String> toolWarnings(List<Map<String, Object>> toolResults) {
        return toolResults.stream()
                .filter(result -> !"PASS".equals(text(result.get("status"))))
                .map(result -> text(result.get("summary")))
                .filter(Objects::nonNull)
                .toList();
    }

    private String confidence(List<Map<String, Object>> toolResults, List<String> warnings) {
        if (!warnings.isEmpty() && toolResults.isEmpty()) {
            return "LOW";
        }
        boolean hasFail = toolResults.stream().anyMatch(result -> "FAIL".equals(text(result.get("status"))));
        if (hasFail) {
            return "LOW";
        }
        boolean hasWarn = toolResults.stream().anyMatch(result -> "WARN".equals(text(result.get("status"))));
        return hasWarn ? "MEDIUM" : "HIGH";
    }

    private List<String> buildWarnings(List<Map<String, Object>> builds) {
        return distinct(builds.stream()
                .flatMap(build -> stringList(build.get("warnings")).stream())
                .toList());
    }

    private PartCandidate partByPublicId(String publicId) {
        if (publicId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currentBuilds의 partId가 비어 있습니다.");
        }
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE public_id = ?::uuid
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .map(this::partCandidate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 추천 부품을 찾을 수 없습니다."));
    }

    private List<AiChatEngineResponse.PartRecommendation> partRecommendations(String category, int limit) {
        if (category == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.attributes,
                               b.score AS benchmark_score,
                               b.summary AS benchmark_summary
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT score, summary
                          FROM benchmark_summaries bs
                          WHERE bs.part_id = p.id
                            AND bs.deleted_at IS NULL
                          ORDER BY bs.score DESC NULLS LAST, bs.created_at DESC
                          LIMIT 1
                        ) b ON true
                        WHERE p.category = ?
                          AND p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                          AND p.price IS NOT NULL
                        ORDER BY b.score DESC NULLS LAST, p.price ASC, p.name ASC
                        LIMIT ?
                        """, category, Math.max(1, limit))
                .stream()
                .map(this::partRecommendation)
                .toList();
    }

    private AiChatEngineResponse.PartRecommendation partRecommendation(Map<String, Object> row) {
        Map<String, Object> attributes = new LinkedHashMap<>(objectMap(DbValueMapper.json(row, "attributes", Map.of())));
        Integer benchmarkScore = DbValueMapper.integer(row, "benchmark_score");
        String benchmarkSummary = DbValueMapper.string(row, "benchmark_summary");
        if (benchmarkScore != null) {
            attributes.put("_benchmarkScore", benchmarkScore);
            attributes.put("benchmarkScore", benchmarkScore);
        }
        if (benchmarkSummary != null) {
            attributes.put("_benchmarkSummary", benchmarkSummary);
        }
        return new AiChatEngineResponse.PartRecommendation(
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                DbValueMapper.integer(row, "price"),
                attributes
        );
    }

    // 예산 근접 탐색용 후보 풀: 저가 N개 + (상한 이하) 고가 N개 유니언을 가격 오름차순으로.
    // 조합 탐색기의 가지치기가 오름차순 정렬을 가정하므로 정렬을 유지해야 한다.
    private List<PartCandidate> nearBudgetPartCandidates(String category, int limit, int upperBound) {
        List<PartCandidate> cheap = pricePartCandidates(category, limit);
        List<PartCandidate> expensive = topPricePartCandidates(category, limit, upperBound);
        LinkedHashMap<String, PartCandidate> merged = new LinkedHashMap<>();
        for (PartCandidate candidate : cheap) {
            merged.put(candidate.publicId(), candidate);
        }
        for (PartCandidate candidate : expensive) {
            merged.putIfAbsent(candidate.publicId(), candidate);
        }
        return merged.values().stream()
                .sorted(java.util.Comparator.comparingInt(PartCandidate::price))
                .toList();
    }

    // GPU/쿨러 제외 기본 구성의 최저 조합 가격 — 예산 미달 고지용
    private int minimumBuildTotal() {
        int total = 0;
        for (String category : fallbackCategories(false, false)) {
            List<PartCandidate> cheapest = pricePartCandidates(category, 1);
            if (cheapest.isEmpty()) {
                return 0;
            }
            total += Math.max(0, cheapest.get(0).price() == null ? 0 : cheapest.get(0).price());
        }
        return total;
    }

    private List<PartCandidate> topPricePartCandidates(String category, int limit, int upperBound) {
        if (category == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE category = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                          AND price IS NOT NULL
                          AND price <= ?
                        ORDER BY price DESC, name ASC
                        LIMIT ?
                        """, category, upperBound, Math.max(1, limit))
                .stream()
                .map(this::partCandidate)
                .toList();
    }

    private List<PartCandidate> pricePartCandidates(String category, int limit) {
        if (category == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE category = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                          AND price IS NOT NULL
                        ORDER BY price ASC, name ASC
                        LIMIT ?
                        """, category, Math.max(1, limit))
                .stream()
                .map(this::partCandidate)
                .toList();
    }

    private static List<String> fallbackCategories(boolean includeGpu, boolean includeCooler) {
        List<String> categories = new ArrayList<>(List.of("CPU", "MOTHERBOARD", "RAM", "STORAGE", "PSU", "CASE"));
        if (includeGpu) {
            categories.add(3, "GPU");
        }
        if (includeCooler) {
            categories.add("COOLER");
        }
        return categories;
    }

    private static int fallbackCandidateLimit(String category, boolean includeGpu) {
        return switch (category) {
            case "MOTHERBOARD", "GPU" -> includeGpu ? 8 : 6;
            case "CPU", "RAM", "STORAGE", "PSU", "CASE", "COOLER" -> 5;
            default -> 3;
        };
    }

    private PartCandidate partCandidate(Map<String, Object> row) {
        return new PartCandidate(
                longValue(row.get("internal_id")),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                DbValueMapper.integer(row, "price"),
                objectMap(DbValueMapper.json(row, "attributes", Map.of()))
        );
    }

    private PartCandidate partCandidate(AiChatEngineResponse.PartRecommendation part) {
        return new PartCandidate(
                null,
                part.partId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes() == null ? Map.of() : part.attributes()
        );
    }

    private Map<String, Object> partItem(PartCandidate part, String fallbackNote) {
        return partItem(part, fallbackNote, defaultQuantity(part));
    }

    private Map<String, Object> partItem(PartCandidate part, String fallbackNote, int quantity) {
        return MockData.map(
                "partId", part.publicId(),
                "category", part.category(),
                "name", part.name(),
                "manufacturer", part.manufacturer(),
                "quantity", quantity,
                "price", part.price(),
                "note", firstText(text(part.attributes().get("shortSpec")), fallbackNote)
        );
    }

    private static ToolBuildPart toolPart(PartCandidate part, Integer quantity) {
        return new ToolBuildPart(
                part.internalId(),
                part.publicId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes(),
                quantity == null || quantity < 1 ? 1 : quantity
        );
    }

    private static String answerType(AiChatIntent intent) {
        return switch (intent) {
            case FULL_BUILD_RECOMMEND -> "BUDGET";
            case PART_RECOMMEND, BUILD_MODIFY -> "PART";
            case PRICE_ALERT_HELP, SUPPORT_GUIDANCE, EXPLAIN, ASK_FOLLOW_UP -> "GENERAL";
        };
    }

    /**
     * Full-build recommendations target two installed memory modules, not two retail packages.
     * A 2-DIMM kit therefore has quantity 1 while a single-DIMM product has quantity 2.
     */
    private static int defaultQuantity(PartCandidate part) {
        if (part == null || !"RAM".equals(part.category())) {
            return 1;
        }
        return ramPackageQuantity(part, 2);
    }

    private static int ramPackageQuantity(PartCandidate part, int desiredModules) {
        Integer configuredModuleCount = part == null ? null : numberValue(part.attributes().get("moduleCount"));
        int modulesPerPackage = configuredModuleCount == null || configuredModuleCount < 1 ? 1 : configuredModuleCount;
        int normalizedDesiredModules = Math.max(1, Math.min(8, desiredModules));
        return Math.max(1, Math.min(4, (normalizedDesiredModules + modulesPerPackage - 1) / modulesPerPackage));
    }

    private static int quantityForRecommendation(PartCandidate part, Map<String, Object> parsedContext) {
        Integer targetQuantity = parsedContext == null ? null : numberValue(parsedContext.get("targetQuantity"));
        if (targetQuantity != null && targetQuantity > 0) {
            return Math.max(1, Math.min(9, targetQuantity));
        }
        return defaultQuantity(part);
    }

    private static int quantityForBudgetFallback(PartCandidate part, int ramQuantity) {
        return "RAM".equals(part.category()) ? ramPackageQuantity(part, ramQuantity) : defaultQuantity(part);
    }

    private static boolean hasHardConstraint(Map<String, Object> parsedContext) {
        return "MUST_INCLUDE".equals(text(parsedContext.get("hardConstraintPolicy")))
                || !stringList(parsedContext.get("requiredGpuClasses")).isEmpty()
                || !stringList(parsedContext.get("requiredPartKeywords")).isEmpty();
    }

    private static boolean hasEffectiveHardConstraint(Map<String, Object> parsedContext, BudgetIntent rawBudgetIntent) {
        // LLM이 명시 GPU 모델을 소프트 선호(hardConstraintPolicy=NONE)로 판단했으면, 원문 정규식이 잡은
        // explicitHardConstraint나 requiredGpuClasses/파생 키워드만으로 하드제약을 강제하지 않는다 —
        // 예산완화 폴백이 살아나 대체 조합/역제안이 가능해진다. LLM이 하드(MUST_INCLUDE)로 판단한 경우는 그대로 유지된다.
        if ((rawBudgetIntent != null && rawBudgetIntent.negatedPartConstraint())
                || isLlmSoftenedExplicitModel(parsedContext)) {
            return false;
        }
        return hasHardConstraint(parsedContext) || (rawBudgetIntent != null && rawBudgetIntent.explicitHardConstraint());
    }

    // 명시 GPU 모델(requiredGpuClasses)이 있는데 LLM이 하드제약을 NONE으로 명시적으로 낮춘 상태.
    // DefaultAiChatEngine이 LLM의 명시 NONE만 그대로 보존하므로(미지정 시엔 MUST_INCLUDE로 승격),
    // 이 조합은 "LLM이 소프트로 판단함"을 뜻한다.
    private static boolean isLlmSoftenedExplicitModel(Map<String, Object> parsedContext) {
        return parsedContext != null
                && !stringList(parsedContext.get("requiredGpuClasses")).isEmpty()
                && "NONE".equals(text(parsedContext.get("hardConstraintPolicy")));
    }

    private static Integer effectiveBudget(Map<String, Object> parsedContext, BudgetIntent rawBudgetIntent) {
        if (rawBudgetIntent != null && rawBudgetIntent.hasBudget()) {
            return rawBudgetIntent.budget();
        }
        return numberValue(parsedContext.get("budget"));
    }

    private static int toolBudgetForBuild(
            int totalPrice,
            Integer userBudget,
            BudgetIntent rawBudgetIntent,
            boolean hardConstraintOverBudget
    ) {
        if (userBudget == null || userBudget <= 0 || hardConstraintOverBudget) {
            return totalPrice;
        }
        if (rawBudgetIntent != null && rawBudgetIntent.hasBudget() && "MAX".equals(rawBudgetIntent.mode())) {
            return userBudget;
        }
        return totalPrice;
    }

    private static boolean withinBudgetGuard(Map<String, Object> build, Map<String, Object> parsedContext, BudgetIntent rawBudgetIntent) {
        Map<String, Object> context = parsedContext == null ? Map.of() : parsedContext;
        Integer budget = effectiveBudget(context, rawBudgetIntent);
        if (budget == null || budget <= 0 || hasEffectiveHardConstraint(context, rawBudgetIntent)) {
            return true;
        }
        Integer totalPrice = numberValue(build.get("totalPrice"));
        if (totalPrice == null) {
            return true;
        }
        String budgetMode = rawBudgetIntent != null && rawBudgetIntent.hasBudget() ? rawBudgetIntent.mode() : budgetMode(context);
        if ("MIN".equals(budgetMode)) {
            return totalPrice >= budget;
        }
        if ("MAX".equals(budgetMode)) {
            return totalPrice <= budget;
        }
        if ("TARGET".equals(budgetMode) || "USER_BUDGET".equals(text(context.get("budgetPolicy")))) {
            return withinTargetBudgetBand(totalPrice, budget);
        }
        return true;
    }

    private static boolean hasRawHardPartConstraint(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String compact = normalizeCommand(message);
        // 부정 문맥("RTX 5090 말고 가성비로")에서는 모델 키워드가 잡혀도 하드제약으로 승격하지 않는다 —
        // 예산 완화 폴백을 막지 않아 대체 조합을 만들 수 있어야 한다. 예산 금액 파싱(serverFact)은
        // budgetIntent에서 그대로 유지되고, 여기서는 '하드제약 여부' 판정만 부정 문맥에서 내린다.
        if (hasRawNegatedPartConstraint(message)) {
            return false;
        }
        String category = detectPartCategory(message);
        boolean explicitInclusion = category != null
                && containsAnyNormalized(compact, "포함", "들어간", "들어가는", "장착", "로구성", "로맞춰");
        boolean manufacturerBoundPart = category != null
                && containsAnyNormalized(compact,
                        "asus", "msi", "gigabyte", "기가바이트", "asrock", "애즈락",
                        "amd", "라이젠", "intel", "인텔", "lianli", "리안리", "corsair", "커세어",
                        "samsung", "삼성", "wd", "western digital", "시게이트", "seagate");
        boolean quantitativePartConstraint = category != null
                && (CAPACITY_GB_PATTERN.matcher(normalized).find()
                        || WATT_PATTERN.matcher(normalized).find()
                        || normalized.matches(".*\\d+\\s*(?:tb|테라(?:바이트)?).*"));
        boolean explicitMemoryConstraint = containsAnyNormalized(compact, "ddr5", "ddr4")
                && CAPACITY_GB_PATTERN.matcher(normalized).find();
        return EXPLICIT_GPU_MODEL.matcher(normalized).find()
                || EXPLICIT_CPU_MODEL.matcher(normalized).find()
                || explicitInclusion
                || manufacturerBoundPart
                || quantitativePartConstraint
                || explicitMemoryConstraint
                || containsAnyNormalized(compact, "정품멀티팩", "리안리216", "lianli216");
    }

    private static boolean hasRawNegatedPartConstraint(String message) {
        String compact = normalizeCommand(message);
        return containsAnyNormalized(compact, "말고", "빼고", "빼", "대신", "제외", "아닌", "말구", "없는", "없이");
    }

    private static String budgetMode(Map<String, Object> parsedContext) {
        String mode = text(parsedContext.get("budgetMode"));
        if (mode == null) {
            return "UNSPECIFIED";
        }
        String upper = mode.toUpperCase(Locale.ROOT);
        return List.of("TARGET", "MAX", "MIN", "OPEN", "UNSPECIFIED").contains(upper) ? upper : "UNSPECIFIED";
    }

    private static List<String> badges(String tierTitle, Map<String, Object> parsedContext) {
        List<String> badges = new ArrayList<>();
        badges.add(tierTitle);
        String budgetPolicy = text(parsedContext.get("budgetPolicy"));
        if (budgetPolicy != null) {
            badges.add(budgetPolicy);
        }
        stringList(parsedContext.get("requiredGpuClasses")).forEach(badges::add);
        return distinct(badges);
    }

    private static String formatBudgetLabel(int budgetWon) {
        return budgetWon % 10_000 == 0 ? (budgetWon / 10_000) + "만원" : String.format("%,d원", budgetWon);
    }

    private static int totalPrice(List<PartCandidate> parts) {
        return parts.stream()
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * defaultQuantity(part))
                .sum();
    }

    private static int totalPrice(List<PartCandidate> parts, int ramQuantity) {
        return parts.stream()
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * quantityForBudgetFallback(part, ramQuantity))
                .sum();
    }

    private static int totalPrice(List<PartCandidate> parts, Map<String, Object> parsedContext) {
        return parts.stream()
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * quantityForRecommendation(part, parsedContext))
                .sum();
    }

    private static String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category);
    }

    private static String slug(String value) {
        String normalized = text(value);
        if (normalized == null) {
            return "build";
        }
        String slug = normalized.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z가-힣]+", "-");
        return slug.replaceAll("(^-+|-+$)", "");
    }

    private static List<String> distinct(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private static String requireText(Object value, String message) {
        String text = text(value);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return text;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        return Integer.parseInt(text.replace(",", ""));
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        return Double.parseDouble(text.replace(",", ""));
    }

    private static boolean isUuid(String value) {
        return value != null && value.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    private static Integer firstNumber(Object... values) {
        for (Object value : values) {
            Integer number = numberValue(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(BuildChatService::text)
                    .filter(Objects::nonNull)
                    .toList();
        }
        return List.of();
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(objectMap(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private record Tier(String id, String label, String title) {
    }

    private record CategoryKeywords(String category, List<String> keywords) {
    }

    record BudgetIntent(Integer budget, String mode, boolean explicitHardConstraint, boolean negatedPartConstraint) {
        static BudgetIntent empty() {
            return new BudgetIntent(null, "UNSPECIFIED", false, false);
        }

        boolean hasBudget() {
            return budget != null && budget > 0;
        }
    }

    private record ExplicitPartSelection(String gpuClass, String modelOrVendorToken, String label) {
    }

    private record CompatibleRecommendationSelection(
            List<BuildChatFeasibilityService.PartOption> options,
            List<String> alreadySelectedIds,
            List<String> warningIds
    ) {
        private static CompatibleRecommendationSelection empty() {
            return new CompatibleRecommendationSelection(List.of(), List.of(), List.of());
        }
    }

    private record AssessmentFocus(String category, String tool) {
    }

    private record SupportGuidanceProfile(
            String title,
            String summary,
            String message,
            List<String> possibleCauses,
            List<String> beforeDiagnosisChecks,
            String agentRecommendation
    ) {
    }

    private static final class BuildChatGuardStats {
        int budgetGuardDropped;
        int blockingFailDropped;
        boolean routeFallbackUsed;

        static BuildChatGuardStats empty() {
            return new BuildChatGuardStats();
        }

        static BuildChatGuardStats routeFallback() {
            BuildChatGuardStats stats = new BuildChatGuardStats();
            stats.routeFallbackUsed = true;
            return stats;
        }
    }

    private record BenchmarkSnapshot(Double score, String summary) {
    }

    private record CaseScoreCap(int maxScore, String code, String reason) {
    }

    private record CaseImprovementCandidate(PartCandidate part, int score, int caseCapScore) {
    }

    private record CaseFitCandidate(PartCandidate part, CaseFitProfile fit) {
    }

    private record CaseFitProfile(Integer gpuHeadroomMm, Integer coolerHeadroomMm, Integer psuHeadroomMm) {
        private List<Integer> knownHeadrooms() {
            return java.util.stream.Stream.of(gpuHeadroomMm, coolerHeadroomMm, psuHeadroomMm)
                    .filter(Objects::nonNull)
                    .toList();
        }

        private boolean hasComparableHeadroom() {
            return !knownHeadrooms().isEmpty();
        }

        private boolean improvesWithoutRegression(CaseFitProfile current) {
            if (current == null || !current.hasComparableHeadroom()) {
                return false;
            }
            boolean compared = false;
            boolean improved = false;
            Integer[] currentValues = {current.gpuHeadroomMm, current.coolerHeadroomMm, current.psuHeadroomMm};
            Integer[] candidateValues = {gpuHeadroomMm, coolerHeadroomMm, psuHeadroomMm};
            for (int index = 0; index < currentValues.length; index += 1) {
                Integer currentValue = currentValues[index];
                if (currentValue == null) {
                    continue;
                }
                Integer candidateValue = candidateValues[index];
                if (candidateValue == null || candidateValue < currentValue) {
                    return false;
                }
                compared = true;
                improved |= candidateValue > currentValue;
            }
            return compared && improved;
        }

        private int minimumKnownHeadroom() {
            return knownHeadrooms().stream().mapToInt(Integer::intValue).min().orElse(Integer.MIN_VALUE);
        }

        private int totalKnownHeadroom() {
            return knownHeadrooms().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private record PartCandidate(
            Long internalId,
            String publicId,
            String category,
            String name,
            String manufacturer,
            Integer price,
            Map<String, Object> attributes
    ) {
    }
}
