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
import com.buildgraph.prototype.part.ToolBuildPart;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.recommendation.CandidateReranker;
import com.buildgraph.prototype.recommendation.NoopCandidateReranker;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
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
            BuildChatSemanticCacheService semanticCacheService
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
        // 서버는 상태를 저장하지 않고 프론트가 originalMessage를 에코하는 무상태 왕복이며, 되묻기는 최대 1회다.
        String clarificationOriginal = text(objectMap(rawBody.get("clarificationContext")).get("originalMessage"));
        boolean clarificationFollowUp = clarificationOriginal != null && !clarificationOriginal.isBlank();
        Map<String, Object> body;
        String message;
        if (clarificationFollowUp) {
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
                if (response.get("simulation") == null && !clarificationFollowUp && response.get("clarification") == null) {
                    response.put("clarification", MockData.map("missingSlots", List.of(), "originalMessage", message));
                }
                // 에코도 카드도 없는 순수 dead-end에는 다음 행동 칩을 보강한다(형태 판정 — 에코가 붙었으면 개입 안 함).
                ensureNextAction(response, intentDecision.intent(), rawBudgetIntent);
                logBuildChatPath("FAST_SIMULATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
                return response;
            }
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
        long stageStartNanos = System.nanoTime();
        var cachedResponse = buildChatCacheService.lookup(body, requestedAiProfile, userId);
        long redisMs = elapsedMs(stageStartNanos);
        if (cachedResponse.isPresent()) {
            Map<String, Object> response = cachedResponse.get();
            logBuildChatPath("CACHE_HIT", startedNanos, userId, requestedAiProfile, true, BuildChatGuardStats.empty(),
                    "redisMs=" + redisMs);
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
            semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
            logBuildChatPath("FAST_DETERMINISTIC", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback(),
                    "redisMs=" + redisMs + " deterministicMs=" + deterministicMs);
            return response;
        }
        stageStartNanos = System.nanoTime();
        var semanticCachedResponse = semanticCacheService.lookup(body, requestedAiProfile, intentDecision);
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
            semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
            logBuildChatPath("FAST_MULTI_PART_REDUCTION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback(),
                    "redisMs=" + redisMs + " deterministicMs=" + deterministicMs + " semanticMs=" + semanticMs);
            return response;
        }
        stageStartNanos = System.nanoTime();
        // 사실 우선: 서버가 방금 계산한 사실(파싱 예산·최소 구성가·부품 조건 최저가·드래프트 요약)을
        // LLM 프롬프트에 주입한다 — LLM이 이미 아는 사실을 되묻거나("예산이 빠졌어요")
        // 사실과 다른 캔드 답("찾지 못했다")을 쓰는 것을 원천 차단한다.
        Map<String, Object> engineBody = new LinkedHashMap<>(body);
        engineBody.put("serverFacts", buildServerFacts(message, rawBudgetIntent, body));
        AiChatEngineResponse engineResponse;
        try {
            engineResponse = aiChatEngine.respondLlmRequired(new AiChatEngineRequest(
                    message,
                    "HOME",
                    firstText(text(body.get("selectedCategory")), detectPartCategory(message)),
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
            Map<String, Object> refusal = gracefulRefusalResponse();
            logBuildChatPath("FAST_UNSUPPORTED_FALLBACK", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return refusal;
        }
        long engineMs = elapsedMs(stageStartNanos);
        BuildChatGuardStats guardStats = new BuildChatGuardStats();
        Map<String, Object> response = responseMap(engineResponse, rawBudgetIntent, guardStats);
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
                && !usageOnlyFollowUp) {
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
        applyDraftEditPreview(response, engineResponse, body);
        // 속성 요청(수랭/PCIe 세대/통풍) + 드래프트에 같은 카테고리 존재 → 1:1 스펙비교 카드로 답한다.
        // 카드가 못 나오면(비교 대상 없음·후보 없음) 아래 역제안이 후보 나열로 이어받는다.
        applyAttributeSimulationCard(response, engineResponse, message, body);
        applyPartConstraintCounterProposal(response, engineResponse, message, body);
        applyUsageMinimumCounterProposal(response, engineResponse, rawBudgetIntent);
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
        ensureNextAction(response, intentDecision.intent(), rawBudgetIntent);
        candidateReranker.recordShadowScores(body, response, userId, requestedAiProfile);
        log.debug("Build Chat response generated: userId={}, requestedAiProfile={}, cacheStore=true", userId, requestedAiProfile);
        buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
        semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
        logBuildChatPath("LLM_FULL", startedNanos, userId, requestedAiProfile, false, guardStats,
                "redisMs=" + redisMs + " deterministicMs=" + deterministicMs + " semanticMs=" + semanticMs + " engineMs=" + engineMs);
        return response;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
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
            Integer totalPrice = numberValue(build.get("totalPrice"));
            if (totalPrice == null) {
                return false;
            }
            if ("MAX".equals(mode) && totalPrice > budgetWon) {
                return false;
            }
            if ("TARGET".equals(mode) && !withinTargetBudgetBand(totalPrice, budgetWon)) {
                return false;
            }
        }
        return true;
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
        if (containsAnyNormalized(normalized, "이하", "안으로", "안쪽", "넘지않", "넘지말", "내로", "아래", "까지")) {
            mode = "MAX";
        } else if (containsAnyNormalized(normalized, "이상", "최소", "부터", "넘게")) {
            mode = "MIN";
        } else {
            mode = "TARGET";
        }
        return new BudgetIntent(budget, mode, hasRawHardPartConstraint(message));
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
        List<CategoryKeywords> checks = List.of(
                new CategoryKeywords("MOTHERBOARD", List.of("메인보드", "마더보드", "보드", "motherboard")),
                new CategoryKeywords("COOLER", List.of("쿨러", "cooler", "수랭", "공랭")),
                new CategoryKeywords("STORAGE", List.of("ssd", "스토리지", "저장장치", "저장 공간", "nvme")),
                new CategoryKeywords("PSU", List.of("파워", "psu", "전원공급", "전원 공급")),
                new CategoryKeywords("CASE", List.of("케이스", "case")),
                new CategoryKeywords("GPU", List.of("gpu", "지피유", "그래픽카드", "그래픽 카드", "그래픽", "글카", "vga", "rtx", "cuda", "nvidia", "엔비디아", "geforce", "지포스")),
                new CategoryKeywords("CPU", List.of("cpu", "씨퓨", "씨피유", "프로세서", "라이젠", "ryzen", "intel", "인텔")),
                new CategoryKeywords("RAM", List.of("ram", "램", "메모리", "memory"))
        );
        return checks.stream()
                .filter(check -> check.keywords().stream().anyMatch(normalized::contains))
                .map(CategoryKeywords::category)
                .findFirst()
                .orElse(null);
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
        Optional<PartCandidate> targetPart = simulationTargetPart(category, message);
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
        String question = resolutionHint
                ? "어떤 해상도 기준으로 맞출까요? 해상도에 따라 필요한 그래픽카드 급이 크게 달라져요. 예산까지 알려주시면 바로 추천해드릴게요."
                : "용도와 예산을 알려주시면 정확하게 맞춰드릴 수 있어요. 아래에서 골라도 되고, 직접 입력해도 돼요.";
        List<String> quickReplies = resolutionHint
                ? List.of("FHD 게이밍 150만원", "QHD 게이밍 250만원", "4K 게이밍 400만원")
                : List.of("사무용 100만원", "게이밍 200만원", "게이밍 300만원", "영상편집 400만원");
        Map<String, Object> response = fastResponse("GENERAL", question, List.of("LOW_INFORMATION"));
        response.put("quickReplies", quickReplies);
        response.put("clarification", MockData.map(
                "missingSlots", List.of("budget", "useCase"),
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
        return MockData.map(
                "answerType", answerType,
                "message", message,
                "builds", builds == null ? List.of() : builds,
                "warnings", distinct(warnings),
                "evidenceIds", List.of(),
                "agentSessionId", null
        );
    }

    // ── 범용 역제안 계층 ─────────────────────────────────────────────
    // 원칙: 이해는 LLM(제약 구조화), 사실은 DB(최저가·최소 구성가), 문구별 분기 없음.

    // LLM 프롬프트에 주입할 서버 계산 사실. 전부 결정적 질의 — LLM은 이 숫자를 되묻지 않고 그대로 쓴다.
    private Map<String, Object> buildServerFacts(String message, BudgetIntent rawBudgetIntent, Map<String, Object> body) {
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
            feasibilityService.cheapestMeeting(constraint).ifPresent(option -> partFacts.put(
                    "cheapestMeeting", MockData.map("name", option.name(), "priceWon", option.unitPrice())));
            if (constraint.maxBudgetWon() != null) {
                feasibilityService.bestUnderBudget(constraint.category(), constraint.maxBudgetWon(), constraint.effectiveQuantity())
                        .ifPresent(option -> partFacts.put(
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
    private Map<String, Object> gracefulRefusalResponse() {
        Map<String, Object> response = fastResponse(
                "GENERAL",
                "이 요청은 제가 도와드리기 어려워요. 예산 견적 추천, 현재 견적 완성, 부품 교체 비교는 바로 도와드릴 수 있습니다. 아래 예시를 눌러 시작해 보세요.",
                List.of("UNSUPPORTED_INTENT")
        );
        response.put("quickReplies", FEATURE_GUIDE_QUICK_REPLIES);
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
            Map<String, Object> body
    ) {
        // 속성 1:1 카드가 이미 답을 냈으면 그 결과(카드·메시지)를 덮지 않는다.
        if (response.get("simulation") != null) {
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

        // 수치 스펙·예산 어느 쪽으로도 구조화되지 않은 제약(색상·소음·규격 등) — 특정 키워드를
        // 해석하지 않고, 해당 카테고리 TOP3를 예산 무제한으로 나열해 다음 행동을 남기는 범용 폴백.
        // 카드·칩이 이미 있으면 개입하지 않는다(LLM이 만든 결과를 덮지 않기 위해).
        if (!constraint.hasSpec() && constraint.maxBudgetWon() == null) {
            if (!objectMaps(response.get("builds")).isEmpty() || !stringList(response.get("quickReplies")).isEmpty()) {
                return;
            }
            LinkedHashSet<String> draftPartKeys = new LinkedHashSet<>();
            for (Map<String, Object> item : objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"))) {
                draftPartKeys.add(text(item.get("partId")));
                draftPartKeys.add(text(item.get("name")));
            }
            List<BuildChatFeasibilityService.PartOption> options =
                    feasibilityService.bestUnderBudget(constraint.category(), Integer.MAX_VALUE, quantity, 6).stream()
                            .filter(option -> !draftPartKeys.contains(option.partId()) && !draftPartKeys.contains(option.name()))
                            .limit(3)
                            .toList();
            if (options.isEmpty()) {
                return;
            }
            String existing = firstText(text(response.get("message")), "");
            String listing = categoryLabel + " 추천 TOP" + options.size() + "입니다. " + topListText(options)
                    + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요.";
            response.put("message", existing.isBlank() ? listing : existing + " " + listing);
            response.put("quickReplies", options.stream().map(o -> o.name() + " 견적에 담아줘").toList());
            return;
        }

        // A. 예산만 명시("10만원짜리 램") — 예산 내 최상 스펙 TOP3를 나열하고 담기 칩을 준다.
        if (!constraint.hasSpec()) {
            int budget = constraint.maxBudgetWon();
            List<BuildChatFeasibilityService.PartOption> options =
                    feasibilityService.bestUnderBudget(constraint.category(), budget, quantity, 3);
            if (options.isEmpty()) {
                Optional<BuildChatFeasibilityService.PartOption> cheapestAny =
                        feasibilityService.cheapestMeeting(new BuildChatFeasibilityService.SpecConstraint(
                                constraint.category(), null, null, null, quantity, null));
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
                response.put("quickReplies", options.stream().map(o -> o.name() + " 견적에 담아줘").toList());
            }
            return;
        }

        Optional<BuildChatFeasibilityService.PartOption> cheapest = feasibilityService.cheapestMeeting(constraint);
        // B. 스펙 자체를 보유하지 않음 — 근접 대안(보유 최고 스펙)을 실데이터로 역제안.
        if (cheapest.isEmpty()) {
            StringBuilder textBuilder = new StringBuilder()
                    .append("요청하신 조건(").append(specSummary).append(")을 만족하는 ")
                    .append(categoryLabel).append(" 부품을 내부 자산에서 찾지 못했습니다.");
            int alternativeBudget = constraint.maxBudgetWon() != null
                    ? constraint.maxBudgetWon()
                    : Integer.MAX_VALUE;
            feasibilityService.bestUnderBudget(constraint.category(), alternativeBudget, quantity).ifPresent(alt -> {
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
            appendBudgetAlternative(textBuilder, constraint, quantity);
            response.put("message", textBuilder.toString());
            warnings.add("PART_BUDGET_SHORTFALL");
            response.put("warnings", distinct(warnings));
            response.put("quickReplies", counterProposalQuickReplies(constraint, categoryLabel, specSummary));
            return;
        }
        // D. 충족 가능 — 실데이터 TOP3 나열 + 담기 칩 (대화가 결과 없이 끝나지 않게).
        List<BuildChatFeasibilityService.PartOption> top = feasibilityService.meetingCheapestFirst(constraint, 3);
        response.put("message", "조건(" + specSummary + ")을 만족하는 " + categoryLabel
                + " 추천 TOP" + top.size() + "입니다. " + topListText(top)
                + " 담고 싶은 부품이 있으면 아래 버튼을 누르거나 말씀해 주세요.");
        response.put("quickReplies", top.stream().map(o -> o.name() + " 견적에 담아줘").toList());
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

    // LLM 제약 + 정규식 폴백 병합: LLM 값이 우선하고, 빈 곳만 메시지 정규식으로 채운다.
    // (라우팅이 아니라 이미 PART_RECOMMEND로 판정된 요청의 데이터 보강 — LLM 비결정성 방어)
    private static final Pattern CAPACITY_TB_PATTERN = Pattern.compile("(\\d+)\\s*(?:tb|테라)", Pattern.CASE_INSENSITIVE);

    private BuildChatFeasibilityService.SpecConstraint mergedPartConstraint(Map<String, Object> llmConstraint, String message) {
        String category = firstText(text(llmConstraint.get("category")), detectPartCategory(message));
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

    private void appendBudgetAlternative(StringBuilder textBuilder, BuildChatFeasibilityService.SpecConstraint constraint, int quantity) {
        if (constraint.maxBudgetWon() == null) {
            return;
        }
        feasibilityService.bestUnderBudget(constraint.category(), constraint.maxBudgetWon(), quantity).ifPresent(alt -> {
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
        if (!eligible || !budgetKnown) {
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
        String existing = firstText(text(response.get("message")), "");
        response.put("message", existing.isBlank() ? notice : notice + " " + existing);
        List<String> warnings = new ArrayList<>(stringList(response.get("warnings")));
        warnings.add("BUDGET_BELOW_USAGE_MINIMUM");
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

    // 부품 변경 요청 — 즉시 반영하지 않고, 변경을 반영한 전체 구성을 재검증해 미리보기 카드로 제시한다.
    // 카드의 적용 버튼(기존 REPLACE 전체 적용)을 눌러야 실제 견적이 바뀐다.
    private void applyDraftEditPreview(Map<String, Object> response, AiChatEngineResponse engineResponse, Map<String, Object> body) {
        if (engineResponse.intent() != AiChatIntent.BUILD_MODIFY) {
            return;
        }
        List<Map<String, Object>> draftItems = objectMaps(objectMap(body.get("currentQuoteDraft")).get("items"));
        if (draftItems.isEmpty()) {
            return;
        }
        Map<String, Object> draftEdit = objectMap(engineResponse.parsedContext().get("draftEdit"));
        String operation = text(draftEdit.get("operation"));
        String category = text(draftEdit.get("category"));
        if (operation == null || category == null || !List.of("ADD", "REPLACE", "REMOVE", "UPDATE_QUANTITY").contains(operation)) {
            return;
        }
        PartCandidate replacement = engineResponse.partRecommendations() == null || engineResponse.partRecommendations().isEmpty()
                ? null
                : partCandidate(engineResponse.partRecommendations().get(0));
        if (replacement == null && !List.of("REMOVE", "UPDATE_QUANTITY").contains(operation)) {
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
            int quantity = existingCategoryQuantity != null && "REPLACE".equals(operation)
                    ? existingCategoryQuantity
                    : defaultQuantity(category);
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
        return Math.max(0, candidate.price() == null ? 0 : candidate.price()) * defaultQuantity(candidate.category());
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
            int quantity = defaultQuantity(part.category());
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
            if (minimumTotal > 0 && budget < minimumTotal) {
                // 요청 예산으로는 구성이 어려우므로 "가능한 최소 구성" 카드를 실제로 만들어 함께 제공한다
                List<String> warnings = new ArrayList<>();
                warnings.add(gpuRequired ? "BUDGET_BELOW_USAGE_MINIMUM" : "BUDGET_BELOW_MINIMUM");
                Optional<GreedyBuild> minimumBuild = greedyTargetBuild(
                        (int) Math.round(minimumTotal * 1.25), (int) Math.round(minimumTotal * 1.25), gpuRequired);
                String guidance = gpuRequired
                        ? usageLabel(inferredUsage) + " PC는 그래픽카드가 필요해 내부 자산 기준 최소 약 "
                                + formatBudgetLabel(minimumTotal) + "부터 가능합니다. 요청 예산("
                                + formatBudgetLabel(budget) + ")과는 약 "
                                + formatBudgetLabel(Math.max(0, minimumTotal - budget)) + " 차이가 납니다."
                        : "요청 예산(" + formatBudgetLabel(budget) + ")으로는 내부 자산 기준 완전한 구성이 어렵습니다. "
                                + "가능한 최소 구성은 약 " + formatBudgetLabel(minimumTotal) + "부터이며, 약 "
                                + formatBudgetLabel(Math.max(0, minimumTotal - budget)) + "을(를) 더 준비하시면 됩니다.";
                Map<String, Object> response;
                if (minimumBuild.isPresent()) {
                    GreedyBuild greedy = minimumBuild.get();
                    warnings.addAll(greedy.warnings());
                    Map<String, Object> build = budgetFallbackBuildMap(
                            TIERS.get(0), greedy.parts(), new BudgetIntent(minimumTotal, "MIN", false), 1,
                            greedy.toolResults(), greedy.warnings(), List.of());
                    build.put("title", "가능한 최소 구성");
                    build.put("summary", gpuRequired
                            ? "현재 판매 중인 내부 자산으로 구성 가능한 가장 저렴한 조합입니다. 용도에 필요한 그래픽카드를 포함했습니다."
                            : "현재 판매 중인 내부 자산으로 구성 가능한 가장 저렴한 조합입니다. 그래픽카드는 제외되어 있습니다.");
                    response = fastResponse("BUDGET", guidance, List.of(build), warnings);
                } else {
                    response = fastResponse("GENERAL", guidance, warnings);
                }
                if (gpuRequired) {
                    int suggested = ((minimumTotal + 99_999) / 100_000) * 100_000;
                    response.put("quickReplies", List.of(
                            formatBudgetLabel(suggested) + " " + usageLabel(inferredUsage) + " PC 추천해줘",
                            formatBudgetLabel(budget) + " 사무용 PC 추천해줘"
                    ));
                }
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
                        "내부 자산과 자동 검증 기준으로 추천 조합 3개를 바로 구성했습니다.",
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
        return MockData.map(
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
            // "이상" 요청: 예산 하한을 지키는 조합을 우선 찾고, 없으면 이하 최대 구성으로 완화한다
            Optional<GreedyBuild> aboveBudget = greedyTargetBuild(
                    (int) Math.min(Integer.MAX_VALUE, Math.round(budgetWon * 1.15)),
                    (int) Math.min(Integer.MAX_VALUE, Math.round(budgetWon * 1.5)),
                    true
            );
            if (aboveBudget.isPresent()
                    && aboveBudget.get().parts().stream().mapToLong(BuildChatService::completionCandidatePrice).sum() >= budgetWon) {
                GreedyBuild greedy = aboveBudget.get();
                Map<String, Object> build = budgetFallbackBuildMap(
                        TIERS.get(2), greedy.parts(), rawBudgetIntent, 2, greedy.toolResults(), greedy.warnings(), engineResponse.evidenceIds());
                warnings.add("명시 예산 범위에 맞춰 내부 자산 기준 보조 견적을 재구성했습니다.");
                if (guardStats != null) {
                    guardStats.routeFallbackUsed = true;
                }
                return List.of(build);
            }
            // MIN 완화는 "예산 이하에서 최대 구성" 의미이므로 TARGET 밴드가 아니라 MAX 사다리를 유지한다
            List<Map<String, Object>> relaxed = nearBudgetLadderBuilds(budgetWon, "MAX", engineResponse.evidenceIds(), warnings, guardStats);
            if (!relaxed.isEmpty()) {
                warnings.add("요청 예산 하한 이상의 조합을 내부 자산으로 구성하지 못해, 예산 이하에서 구성 가능한 최대 조합을 추천합니다.");
            }
            return relaxed;
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
    private static final double[] TARGET_BAND_TIER_TARGETS = {0.90, 1.0, 1.08};
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
        double[] tierTargets = targetBand ? TARGET_BAND_TIER_TARGETS : NEAR_BUDGET_TIER_TARGETS;
        // 타깃 예산별로 1개씩 뽑아 구성 다양성을 확보한다
        LinkedHashMap<String, Map<String, Object>> byComposition = new LinkedHashMap<>();
        for (double targetRatio : tierTargets) {
            int targetBudget = (int) Math.floor(budgetWon * targetRatio);
            List<Map<String, Object>> targetResult = singleTargetLadderBuilds(targetBudget, budgetMode, budgetWon, evidenceIds);
            if (!targetResult.isEmpty()) {
                Map<String, Object> build = targetResult.get(0);
                if (targetBand && !withinTargetBudgetBand(numberValue(build.get("totalPrice")), budgetWon)) {
                    continue;
                }
                byComposition.putIfAbsent(buildItemsKey(build), build);
            }
        }

        // 부족분 보충: MAX는 예산 근접 탐색, TARGET은 밴드 하한 미달을 상향 보정하도록
        // 밴드 상한(112.5%)까지 열어 한 번 더 탐색한다
        if (byComposition.size() < 3) {
            int supplementTarget = targetBand
                    ? (int) Math.floor(budgetWon * TARGET_BUDGET_BAND_UPPER)
                    : budgetWon;
            for (Map<String, Object> build : singleTargetLadderBuilds(supplementTarget, budgetMode, budgetWon, evidenceIds)) {
                if (byComposition.size() >= 3) {
                    break;
                }
                if (targetBand && !withinTargetBudgetBand(numberValue(build.get("totalPrice")), budgetWon)) {
                    continue;
                }
                byComposition.putIfAbsent(buildItemsKey(build), build);
            }
        }

        if (byComposition.isEmpty()) {
            // 사다리가 전부 빈손이어도 예산이 최소 구성가 이상이면, 이미 검증된 최소 구성 경로로
            // '가능한 최소 구성' 카드 1개는 제공해 빈 화면을 막는다.
            int minimumTotal = minimumBuildTotal();
            if (minimumTotal > 0 && budgetWon >= minimumTotal) {
                Optional<GreedyBuild> minimumBuild = greedyTargetBuild(
                        (int) Math.round(minimumTotal * 1.25), (int) Math.round(minimumTotal * 1.25), false);
                if (minimumBuild.isPresent()) {
                    GreedyBuild greedy = minimumBuild.get();
                    Map<String, Object> build = budgetFallbackBuildMap(
                            TIERS.get(0), greedy.parts(), new BudgetIntent(minimumTotal, "MIN", false), 1,
                            greedy.toolResults(), greedy.warnings(), evidenceIds);
                    build.put("title", "가능한 최소 구성");
                    build.put("summary", "현재 판매 중인 내부 자산으로 구성 가능한 가장 저렴한 조합입니다. 그래픽카드는 제외되어 있습니다.");
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
        // 총액 오름차순으로 정렬해 가성비→균형→고성능 순서와 티어 라벨을 일치시킨다
        builds.sort(java.util.Comparator.comparingInt(build -> {
            Integer total = numberValue(build.get("totalPrice"));
            return total == null ? 0 : total;
        }));
        relabelTierBuilds(builds);
        int bestTotal = builds.stream()
                .map(build -> numberValue(build.get("totalPrice")))
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        if (bestTotal < Math.floor(budgetWon * TARGET_BUDGET_BAND_LOWER)) {
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
        if (build.isEmpty() && includeGpu) {
            build = greedyTargetBuild(targetBudget, targetBudget, false);
        }
        if (build.isEmpty()) {
            return List.of();
        }
        GreedyBuild greedy = build.get();
        // 카드에 노출하는 예산/모드: TARGET은 사용자 명시 예산 그대로, 그 외(MAX/완화)는 기존처럼
        // 사다리 타깃 예산을 MAX로 표기한다.
        boolean targetBand = "TARGET".equals(budgetMode);
        Map<String, Object> map = budgetFallbackBuildMap(
                TIERS.get(1),
                greedy.parts(),
                targetBand
                        ? new BudgetIntent(displayBudgetWon, "TARGET", false)
                        : new BudgetIntent(targetBudget, "MAX", false),
                2,
                greedy.toolResults(),
                greedy.warnings(),
                evidenceIds
        );
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

    private static void relabelTierBuilds(List<Map<String, Object>> builds) {
        for (int index = 0; index < builds.size(); index += 1) {
            Tier tier = TIERS.get(Math.min(index, TIERS.size() - 1));
            Map<String, Object> build = builds.get(index);
            build.put("tier", tier.id());
            build.put("label", tier.label());
            build.put("title", tier.title() + " 예산 맞춤 조합");
            build.put("tierLabel", tier.title());
        }
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
        return MockData.map(
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
        return MockData.map(
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

    private boolean hasBlockingToolFailure(List<Map<String, Object>> toolResults) {
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
    // quantities는 publicId(없으면 name) → 수량 맵이며, 없는 항목은 1로 평가한다(기존 동작 유지).
    private List<Map<String, Object>> toolResults(List<PartCandidate> parts, Map<String, Integer> quantities, int budgetWon, List<String> warnings) {
        try {
            return toolCheckService.checkBuild(
                    parts.stream()
                            .map(part -> toolPart(part, quantities.get(part.publicId() == null ? part.name() : part.publicId())))
                            .toList(),
                    budgetWon
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
        return partItem(part, fallbackNote, defaultQuantity(part.category()));
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
            case PRICE_ALERT_HELP, EXPLAIN, ASK_FOLLOW_UP -> "GENERAL";
        };
    }

    private static int defaultQuantity(String category) {
        return "RAM".equals(category) ? 2 : 1;
    }

    private static int quantityForRecommendation(PartCandidate part, Map<String, Object> parsedContext) {
        Integer targetQuantity = parsedContext == null ? null : numberValue(parsedContext.get("targetQuantity"));
        if (targetQuantity != null && targetQuantity > 0) {
            return Math.max(1, Math.min(9, targetQuantity));
        }
        return defaultQuantity(part.category());
    }

    private static int quantityForBudgetFallback(PartCandidate part, int ramQuantity) {
        return "RAM".equals(part.category()) ? Math.max(1, Math.min(4, ramQuantity)) : defaultQuantity(part.category());
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
        if (isLlmSoftenedExplicitModel(parsedContext)) {
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
        if (containsAnyNormalized(compact, "말고", "빼고", "빼", "대신", "제외", "아닌", "말구")) {
            return false;
        }
        return EXPLICIT_GPU_MODEL.matcher(normalized).find()
                || EXPLICIT_CPU_MODEL.matcher(normalized).find()
                || containsAnyNormalized(compact, "정품멀티팩", "리안리216", "lianli216");
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
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * defaultQuantity(part.category()))
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

    record BudgetIntent(Integer budget, String mode, boolean explicitHardConstraint) {
        static BudgetIntent empty() {
            return new BudgetIntent(null, "UNSPECIFIED", false);
        }

        boolean hasBudget() {
            return budget != null && budget > 0;
        }
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
