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
    private static final Pattern BUDGET_WON = Pattern.compile("(\\d{6,})\\s*원?");
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
    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;
    private final AiChatEngine aiChatEngine;
    private final BuildChatCacheService buildChatCacheService;
    private final PartReplacementRanker partReplacementRanker;
    private final CandidateReranker candidateReranker;
    private final PartRouteResolver partRouteResolver;
    private final BuildChatIntentRouter intentRouter;
    private final BuildChatSemanticCacheService semanticCacheService;

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
        Map<String, Object> body = request == null ? Map.of() : request;
        String message = requireText(body.get("message"), "message는 필수입니다.");
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
            Map<String, Object> response = performanceSimulationResponse(body, message)
                    .orElseGet(() -> simulationClarificationResponse(body, message));
            logBuildChatPath("FAST_SIMULATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.ASK_CLARIFICATION) {
            Map<String, Object> response = fastResponse(
                    "GENERAL",
                    "추천 기준을 조금 더 정하면 더 정확합니다. 예산, 해상도, 주 사용 게임이나 작업을 알려주세요.",
                    intentDecision.ambiguityReasons()
            );
            logBuildChatPath("FAST_CLARIFICATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.UNSUPPORTED) {
            Map<String, Object> response = fastResponse(
                    "GENERAL",
                    "이 어시스턴트는 예산 견적 추천, 현재 견적 완성, 부품 교체 성능 비교를 도와드립니다. "
                            + "부품 탐색이나 견적 담기/빼기는 셀프 견적 그래프에서 직접 할 수 있어요. "
                            + "예: \"200만원 게이밍 PC 추천\", \"CPU를 9700X로 바꾸면?\"",
                    List.of("UNSUPPORTED_INTENT")
            );
            logBuildChatPath("FAST_UNSUPPORTED", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        long stageStartNanos = System.nanoTime();
        var cachedResponse = buildChatCacheService.lookup(body, requestedAiProfile, userId);
        long redisMs = elapsedMs(stageStartNanos);
        if (cachedResponse.isPresent()) {
            Map<String, Object> response = cachedResponse.get();
            logBuildChatPath("CACHE_HIT", startedNanos, userId, requestedAiProfile, true, BuildChatGuardStats.empty(),
                    "redisMs=" + redisMs);
            return response;
        }
        if (tierSnapshotStore != null
                && rawBudgetIntent.hasBudget()
                && !rawBudgetIntent.explicitHardConstraint()
                && objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()) {
            stageStartNanos = System.nanoTime();
            Optional<BuildChatTierSnapshotStore.TierSnapshot> tierSnapshot =
                    tierSnapshotStore.bestFor(rawBudgetIntent.budget(), rawBudgetIntent.mode(), tierSnapshotTolerancePct);
            long tierMs = elapsedMs(stageStartNanos);
            if (tierSnapshot.isPresent()) {
                BuildChatTierSnapshotStore.TierSnapshot snapshot = tierSnapshot.get();
                Map<String, Object> response = fastResponse(
                        "BUDGET",
                        "내부 자산과 Tool 검증 기준으로 미리 계산한 추천 조합을 바로 가져왔습니다.",
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
        Optional<Map<String, Object>> completionResponse = draftCompletionFastResponse(body, message, rawBudgetIntent);
        long completionMs = elapsedMs(stageStartNanos);
        if (completionResponse.isPresent()) {
            Map<String, Object> response = completionResponse.get();
            buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
            logBuildChatPath("FAST_DRAFT_COMPLETION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback(),
                    "redisMs=" + redisMs + " completionMs=" + completionMs);
            return response;
        }
        stageStartNanos = System.nanoTime();
        Optional<Map<String, Object>> deterministicResponse = deterministicFastResponse(body, message, rawBudgetIntent);
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
        stageStartNanos = System.nanoTime();
        AiChatEngineResponse engineResponse = aiChatEngine.respondLlmRequired(new AiChatEngineRequest(
                message,
                "HOME",
                firstText(text(body.get("selectedCategory")), detectPartCategory(message)),
                text(body.get("buildId")),
                text(body.get("draftId")),
                body,
                userId
        ), requestedAiProfile);
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
        if (intentDecision.intent() == BuildChatIntent.BUILD_RECOMMEND
                && objectMaps(response.get("builds")).isEmpty()) {
            List<String> fallbackWarnings = new ArrayList<>();
            List<Map<String, Object>> fallbackBuilds = rawBudgetIntent.hasBudget()
                    ? nearBudgetLadderBuilds(rawBudgetIntent.budget(), List.of(), fallbackWarnings, new BuildChatGuardStats())
                    : openBudgetFallbackBuilds(fallbackWarnings);
            if (!fallbackBuilds.isEmpty()) {
                response.put("answerType", "BUDGET");
                response.put("builds", fallbackBuilds);
                List<String> mergedWarnings = new ArrayList<>(stringList(response.get("warnings")));
                mergedWarnings.addAll(fallbackWarnings);
                response.put("warnings", distinct(mergedWarnings));
            }
        }
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

    public record TierBuilds(List<Map<String, Object>> builds, List<String> warnings) {
    }

    // 예산 티어 스냅샷용 계산: "티어 이하 & 최대 근접" 사다리 탐색 (총액 ≤ 티어 보장)
    public TierBuilds computeBudgetTierBuilds(int budgetWon) {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> builds = nearBudgetLadderBuilds(budgetWon, List.of(), warnings, new BuildChatGuardStats());
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
        Matcher cheonManWonMatcher = BUDGET_CHEONMANWON.matcher(normalized);
        // "천만에요" 같은 관용구 오탐을 막기 위해 선행 숫자, 백 단위, "원" 중 하나는 있어야 예산으로 본다
        if (cheonManWonMatcher.find()
                && (cheonManWonMatcher.group(1) != null || cheonManWonMatcher.group(2) != null || cheonManWonMatcher.group(3) != null)) {
            double thousands = cheonManWonMatcher.group(1) == null ? 1 : Double.parseDouble(cheonManWonMatcher.group(1));
            double hundreds = cheonManWonMatcher.group(2) == null ? 0 : Double.parseDouble(cheonManWonMatcher.group(2));
            return (int) Math.round(thousands * 10_000_000 + hundreds * 1_000_000);
        }
        Matcher baekManWonMatcher = BUDGET_BAEKMANWON.matcher(normalized);
        if (baekManWonMatcher.find()) {
            return (int) Math.round(Double.parseDouble(baekManWonMatcher.group(1)) * 1_000_000);
        }
        Matcher manWonMatcher = BUDGET_MANWON.matcher(normalized);
        if (manWonMatcher.find()) {
            return (int) Math.round(Double.parseDouble(manWonMatcher.group(1)) * 10_000);
        }
        Matcher wonMatcher = BUDGET_WON.matcher(normalized);
        if (wonMatcher.find()) {
            return Integer.parseInt(wonMatcher.group(1));
        }
        return null;
    }

    // "삼백만원", "일천삼백만원", "2천만원" 같은 한글/혼합 숫자 표기를 아라비아 숫자로 정규화한다
    private static String normalizeKoreanNumerals(String value) {
        String result = value;
        String digits = "일이삼사오육칠팔구";
        for (int index = 0; index < digits.length(); index += 1) {
            result = result.replace(String.valueOf(digits.charAt(index)), String.valueOf(index + 1));
        }
        return result;
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
        if (!objectMap(request.get("currentQuoteDraft")).isEmpty() || (rawBudgetIntent != null && rawBudgetIntent.explicitHardConstraint())) {
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
        if (!isPerformanceSimulationIntent(message)) {
            return Optional.empty();
        }
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
            return Optional.of(fastResponse(
                    "GENERAL",
                    categoryLabel(category) + " 시뮬레이션을 하려면 바꿀 제품을 조금 더 구체적으로 알려주세요.",
                    List.of("SIMULATION_TARGET_NOT_FOUND")
            ));
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

    private static boolean isPerformanceSimulationIntent(String message) {
        String normalized = normalizeCommand(message);
        boolean changeHypothesis = containsAnyNormalized(normalized, "바꾸면", "교체하면", "넣으면", "달면", "변경하면", "업그레이드하면", "으로가면", "로가면");
        boolean explicitImpactQuestion = containsAnyNormalized(normalized, "프레임", "fps", "성능", "벤치", "얼마나", "어떻게되", "어떻게", "어떨", "차이", "향상");
        boolean shortWhatIfQuestion = changeHypothesis && (normalized.endsWith("?") || normalized.endsWith("면") || containsAnyNormalized(normalized, "좋을까", "나을까"));
        return changeHypothesis && (explicitImpactQuestion || shortWhatIfQuestion);
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

    private static String simulationModelToken(String category, String message) {
        String text = message == null ? "" : message;
        if ("GPU".equals(category)) {
            String gpuClass = targetGpuClass(message);
            return gpuClass == null ? null : gpuClass.replace("RTX_", "RTX ").replace("_", " ");
        }
        if ("CPU".equals(category)) {
            Matcher matcher = EXPLICIT_CPU_MODEL.matcher(text);
            return matcher.find() ? matcher.group() : null;
        }
        Matcher model = Pattern.compile("(?i)([a-z가-힣]*\\s*\\d{2,5}[a-z0-9가-힣-]*)").matcher(text);
        if (model.find()) {
            return model.group(1).trim();
        }
        String brand = brandToken(text);
        return brand == null ? null : brand;
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

        if (missingCategories.isEmpty()) {
            List<Map<String, Object>> toolResults = toolResults(fixedParts, budget == null ? fixedTotal : budget, warnings);
            warnings.addAll(toolWarnings(toolResults));
            Map<String, Object> build = completionBuildMap(TIERS.get(1), fixedParts, fixedQuantities, List.of(), budget, toolResults, warnings);
            return Optional.of(fastResponse("BUDGET", "이미 모든 카테고리가 채워져 있어 현재 구성 그대로 검증했습니다.", List.of(build), warnings));
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
            List<Map<String, Object>> toolResults = toolResults(previewParts, budget == null ? totalPrice : budget, localWarnings);
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
                "현재 견적에 담긴 부품은 유지하고 나머지 카테고리를 내부 자산과 Tool 검증 기준으로 채웠습니다.",
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
                "recommendedFor", List.of("견적 완성", "내부 자산", "Tool 검증"),
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
        if (budget != null && objectMap(request.get("currentQuoteDraft")).isEmpty()) {
            int minimumTotal = minimumBuildTotal();
            if (minimumTotal > 0 && budget < minimumTotal) {
                // 요청 예산으로는 구성이 어려우므로 "가능한 최소 구성" 카드를 실제로 만들어 함께 제공한다
                List<String> warnings = new ArrayList<>();
                warnings.add("BUDGET_BELOW_MINIMUM");
                Optional<GreedyBuild> minimumBuild = greedyTargetBuild(
                        (int) Math.round(minimumTotal * 1.25), (int) Math.round(minimumTotal * 1.25), false);
                String guidance = "요청 예산(" + formatBudgetLabel(budget) + ")으로는 내부 자산 기준 완전한 구성이 어렵습니다. "
                        + "가능한 최소 구성은 약 " + formatBudgetLabel(minimumTotal) + "부터이며, 약 "
                        + formatBudgetLabel(Math.max(0, minimumTotal - budget)) + "을(를) 더 준비하시면 됩니다.";
                if (minimumBuild.isPresent()) {
                    GreedyBuild greedy = minimumBuild.get();
                    warnings.addAll(greedy.warnings());
                    Map<String, Object> build = budgetFallbackBuildMap(
                            TIERS.get(0), greedy.parts(), new BudgetIntent(minimumTotal, "MIN", false), 1,
                            greedy.toolResults(), greedy.warnings(), List.of());
                    build.put("title", "가능한 최소 구성");
                    build.put("summary", "내부 ACTIVE 자산으로 구성 가능한 가장 저렴한 조합입니다. 그래픽카드는 제외되어 있습니다.");
                    return Optional.of(fastResponse("BUDGET", guidance, List.of(build), warnings));
                }
                return Optional.of(fastResponse("GENERAL", guidance, warnings));
            }
        }
        if (isStandaloneBuildRecommend(message, request, rawBudgetIntent)) {
            AiChatEngineResponse engineResponse = new AiChatEngineResponse(
                    "내부 자산과 Tool 검증 기준으로 바로 추천 조합을 구성했습니다.",
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
                        "내부 자산과 Tool 검증 기준으로 추천 조합 3개를 바로 구성했습니다.",
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
            warnings.add("빠른 응답을 위해 내부 자산과 Tool 검증 기준으로 추천 조합을 즉시 구성했습니다.");
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
                "summary", "내부 ACTIVE 자산과 Tool 검증을 기준으로 빠르게 구성했습니다.",
                "recommendedFor", List.of("빠른 추천", "내부 자산", "Tool 검증"),
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
                warnings.add("Tool 검증에서 장착/호환/전력 불가로 판정된 추천 조합을 제외했습니다.");
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
            List<Map<String, Object>> relaxed = nearBudgetLadderBuilds(budgetWon, engineResponse.evidenceIds(), warnings, guardStats);
            if (!relaxed.isEmpty()) {
                warnings.add("요청 예산 하한 이상의 조합을 내부 자산으로 구성하지 못해, 예산 이하에서 구성 가능한 최대 조합을 추천합니다.");
            }
            return relaxed;
        }

        // TARGET/MAX: 예산을 넘지 않으면서 최대한 근접한 조합을 사다리 방식으로 찾는다
        List<Map<String, Object>> ladder = nearBudgetLadderBuilds(budgetWon, engineResponse.evidenceIds(), warnings, guardStats);
        if (!ladder.isEmpty()) {
            warnings.add("명시 예산 범위에 맞춰 내부 자산 기준 보조 견적을 재구성했습니다.");
        }
        return ladder;
    }

    // 3개 추천의 다양성을 위한 타깃 예산 비율: 가성비 / 균형 / 예산 근접.
    // 느슨한 가격 밴드 탐색은 가지치기가 무력해져 수십 초가 걸리므로,
    // 타깃 예산을 낮춰 "타깃의 87.5%~100%" 타이트한 탐색을 3번 수행한다.
    private static final double[] NEAR_BUDGET_TIER_TARGETS = {0.55, 0.75, 1.0};

    private List<Map<String, Object>> nearBudgetLadderBuilds(
            int budgetWon,
            List<String> evidenceIds,
            List<String> warnings,
            BuildChatGuardStats guardStats
    ) {
        // 타깃 예산별로 1개씩 뽑아 가성비/균형/근접 다양성을 확보한다
        LinkedHashMap<String, Map<String, Object>> byComposition = new LinkedHashMap<>();
        for (double targetRatio : NEAR_BUDGET_TIER_TARGETS) {
            int targetBudget = (int) Math.floor(budgetWon * targetRatio);
            List<Map<String, Object>> targetResult = singleTargetLadderBuilds(targetBudget, evidenceIds);
            if (!targetResult.isEmpty()) {
                Map<String, Object> build = targetResult.get(0);
                byComposition.putIfAbsent(buildItemsKey(build), build);
            }
        }

        // 부족분은 예산 근접 탐색 결과로 보충한다
        if (byComposition.size() < 3) {
            for (Map<String, Object> build : singleTargetLadderBuilds(budgetWon, evidenceIds)) {
                if (byComposition.size() >= 3) {
                    break;
                }
                byComposition.putIfAbsent(buildItemsKey(build), build);
            }
        }

        if (byComposition.isEmpty()) {
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
        if (bestTotal < Math.floor(budgetWon * 0.875)) {
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
    private List<Map<String, Object>> singleTargetLadderBuilds(int targetBudget, List<String> evidenceIds) {
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
        Map<String, Object> map = budgetFallbackBuildMap(
                TIERS.get(1),
                greedy.parts(),
                new BudgetIntent(targetBudget, "MAX", false),
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
                "summary", "명시 예산 범위를 우선해 내부 ACTIVE 자산과 Tool 검증 기준으로 재구성했습니다.",
                "recommendedFor", List.of("명시 예산", "내부 자산", "Tool 검증"),
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

    private static Integer parseCapacityGb(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Matcher matcher = CAPACITY_GB_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static Integer parseWattage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Matcher matcher = WATT_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private List<Map<String, Object>> toolResults(List<PartCandidate> parts, int budgetWon, List<String> warnings) {
        try {
            return toolCheckService.checkBuild(parts.stream().map(BuildChatService::toolPart).toList(), budgetWon);
        } catch (RuntimeException error) {
            warnings.add("Tool 검증을 완료하지 못했습니다: " + error.getMessage());
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

    private static ToolBuildPart toolPart(PartCandidate part) {
        return new ToolBuildPart(
                part.internalId(),
                part.publicId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes()
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
        return hasHardConstraint(parsedContext) || (rawBudgetIntent != null && rawBudgetIntent.explicitHardConstraint());
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
            return totalPrice >= Math.floor(budget * 0.875) && totalPrice <= Math.ceil(budget * 1.125);
        }
        return true;
    }

    private static boolean hasRawHardPartConstraint(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String compact = normalizeCommand(message);
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
