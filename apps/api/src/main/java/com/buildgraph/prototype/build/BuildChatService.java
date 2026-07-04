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
    private static final Pattern BUDGET_BAEKMANWON = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*백\\s*만원");
    private static final Pattern BUDGET_WON = Pattern.compile("(\\d{6,})\\s*원?");
    private static final Pattern EXPLICIT_GPU_MODEL = Pattern.compile("(?i)(?:rtx|geforce|지포스)?\\s*(40[6-9]0|50[6-9]0)(?:\\s*(ti|super))?");
    private static final Pattern EXPLICIT_CPU_MODEL = Pattern.compile("(?i)\\b\\d{4,5}x3d\\b|\\b\\d{4,5}x\\b|\\bi[3579]-?\\d{4,5}\\b");
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(\\d+)\\s*(?:개|장|ea|pcs|개로)");
    private static final Pattern CAPACITY_GB_PATTERN = Pattern.compile("(\\d+)\\s*(?:gb|기가|기가바이트)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WATT_PATTERN = Pattern.compile("(\\d{3,4})\\s*w", Pattern.CASE_INSENSITIVE);
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
        RouteIntent routeIntent = intentDecision.intent() == BuildChatIntent.NAVIGATE_CATEGORY && intentDecision.targetCategory() != null
                ? new RouteIntent(
                "/self-quote?category=" + intentDecision.targetCategory(),
                categoryLabel(intentDecision.targetCategory()) + " 부품 보기",
                categoryLabel(intentDecision.targetCategory()) + " 부품 화면으로 이동했습니다."
        )
                : intentDecision.intent() == BuildChatIntent.NAVIGATE_STATIC
                ? routeIntent(message)
                : null;
        if (routeIntent != null) {
            Map<String, Object> response = routeResponse(routeIntent);
            logBuildChatPath("FAST_ROUTE", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.FILTER_PART_SEARCH) {
            String route = partRouteResolver.resolveCategoryFilterRoute(intentDecision.partQuery(), intentDecision.targetCategory());
            if (route != null) {
                Map<String, Object> response = routeResponse(new RouteIntent(
                        route,
                        categoryLabel(intentDecision.targetCategory()) + " 후보 보기",
                        categoryLabel(intentDecision.targetCategory()) + " 후보 화면으로 이동했습니다."
                ));
                logBuildChatPath("FAST_FILTER_ROUTE", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback());
                return response;
            }
        }
        Optional<PartRouteResolver.ResolvedRoute> partRoute = intentDecision.intent() == BuildChatIntent.NAVIGATE_PART_DETAIL
                ? partRouteResolver.resolveFastRoute(message, text(body.get("selectedCategory")))
                : Optional.empty();
        if (partRoute.isPresent()) {
            PartRouteResolver.ResolvedRoute resolved = partRoute.get();
            Map<String, Object> response = routeResponse(new RouteIntent(resolved.route(), resolved.label(), resolved.message()));
            String pathType = resolved.route().startsWith("/parts/") ? "FAST_PART_ROUTE" : "FAST_FILTER_ROUTE";
            logBuildChatPath(pathType, startedNanos, userId, requestedAiProfile, false,
                    "FAST_FILTER_ROUTE".equals(pathType) ? BuildChatGuardStats.routeFallback() : BuildChatGuardStats.empty());
            return response;
        }
        Optional<Map<String, Object>> simulationResponse = intentDecision.intent() == BuildChatIntent.SIMULATE_REPLACEMENT
                ? performanceSimulationResponse(body, message)
                : Optional.empty();
        if (simulationResponse.isPresent()) {
            Map<String, Object> response = simulationResponse.get();
            logBuildChatPath("FAST_SIMULATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.ASK_CLARIFICATION) {
            Map<String, Object> response = fastResponse(
                    "GENERAL",
                    "추천 기준을 조금 더 정하면 더 정확합니다. 예산, 해상도, 주 사용 게임이나 작업을 알려주세요.",
                    null,
                    List.of(),
                    intentDecision.ambiguityReasons()
            );
            logBuildChatPath("FAST_CLARIFICATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        Optional<Map<String, Object>> fastDraftResponse = intentDecision.isMutation()
                ? fastDraftActionResponse(body, message)
                : Optional.empty();
        if (fastDraftResponse.isPresent()) {
            Map<String, Object> response = fastDraftResponse.get();
            logBuildChatPath("FAST_DRAFT_ACTION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        var cachedResponse = buildChatCacheService.lookup(body, requestedAiProfile, userId);
        if (cachedResponse.isPresent()) {
            Map<String, Object> response = cachedResponse.get();
            logBuildChatPath("CACHE_HIT", startedNanos, userId, requestedAiProfile, true, BuildChatGuardStats.empty());
            return response;
        }
        Optional<Map<String, Object>> deterministicResponse = deterministicFastResponse(body, message, rawBudgetIntent);
        if (deterministicResponse.isPresent()) {
            Map<String, Object> response = deterministicResponse.get();
            buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
            semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
            logBuildChatPath("FAST_DETERMINISTIC", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback());
            return response;
        }
        var semanticCachedResponse = semanticCacheService.lookup(body, requestedAiProfile, intentDecision);
        if (semanticCachedResponse.isPresent()) {
            Map<String, Object> response = semanticCachedResponse.get();
            logBuildChatPath("SEMANTIC_CACHE_HIT", startedNanos, userId, requestedAiProfile, true, BuildChatGuardStats.empty());
            return response;
        }
        AiChatEngineResponse engineResponse = aiChatEngine.respondLlmRequired(new AiChatEngineRequest(
                message,
                "HOME",
                firstText(text(body.get("selectedCategory")), detectPartCategory(message)),
                text(body.get("buildId")),
                text(body.get("draftId")),
                body,
                userId
        ), requestedAiProfile);
        BuildChatGuardStats guardStats = new BuildChatGuardStats();
        Map<String, Object> response = responseMap(engineResponse, body, rawBudgetIntent, guardStats);
        candidateReranker.recordShadowScores(body, response, userId, requestedAiProfile);
        log.debug("Build Chat response generated: userId={}, requestedAiProfile={}, cacheStore=true", userId, requestedAiProfile);
        buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
        semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
        logBuildChatPath("LLM_FULL", startedNanos, userId, requestedAiProfile, false, guardStats);
        return response;
    }

    static Integer parseBudgetWon(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.replace(",", "").toLowerCase(Locale.ROOT);
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

    private static boolean isPriceAlertIntent(String message) {
        String normalized = normalizeCommand(message);
        return parseBudgetWon(message) != null
                && containsAnyNormalized(normalized, "알림", "알려줘", "알려")
                && !containsAnyNormalized(normalized, "추천", "교체", "바꿔", "넣어", "담아");
    }

    private static boolean isStandalonePartRecommend(String message, Map<String, Object> request, String category) {
        if (category == null || !objectMap(request.get("currentQuoteDraft")).isEmpty()) {
            return false;
        }
        String normalized = normalizeCommand(message);
        boolean recommendLike = containsAnyNormalized(normalized, "추천", "골라", "뭐가좋", "좋은");
        boolean mutationLike = containsAnyNormalized(normalized,
                "맞춰", "바꿔", "교체", "담아", "넣어", "빼", "삭제", "상세", "이동", "열어");
        boolean buildLike = containsAnyNormalized(normalized, "pc", "컴퓨터", "견적", "본체");
        boolean buildPreferenceLike = containsAnyNormalized(normalized, "위주", "말고", "cuda", "로컬ai", "실험용", "그래픽카드로추천");
        return recommendLike && !mutationLike && !buildLike && !buildPreferenceLike;
    }

    private static boolean isStandaloneBuildRecommend(String message, Map<String, Object> request, BudgetIntent rawBudgetIntent) {
        if (!objectMap(request.get("currentQuoteDraft")).isEmpty() || (rawBudgetIntent != null && rawBudgetIntent.explicitHardConstraint())) {
            return false;
        }
        String normalized = normalizeCommand(message);
        boolean preferenceBuildLike = containsAnyNormalized(normalized, "위주", "말고", "cuda", "로컬ai", "실험용", "그래픽카드로추천");
        boolean buildLike = containsAnyNormalized(normalized,
                "pc", "컴퓨터", "견적", "본체", "구성",
                "목표", "게임", "게이밍", "qhd", "fhd", "4k", "hz", "배그", "발로란트", "오버워치", "사이버펑크", "로스트아크")
                || preferenceBuildLike;
        boolean recommendLike = containsAnyNormalized(normalized, "추천", "맞춰", "구성", "용pc", "pc");
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
                "게임", "게이밍", "qhd", "4k", "144", "배그", "발로란트", "오버워치", "사이버펑크",
                "영상", "편집", "프리미어", "블렌더", "개발", "ai", "cuda", "로컬ai", "실험용",
                "엔비디아", "라데온", "nvidia", "고성능", "최고급", "끝판왕", "저소음", "조용",
                "작은", "컴팩트", "저장", "로딩", "사무", "학습", "흰색", "화이트", "업그레이드",
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
                new CategoryKeywords("GPU", List.of("gpu", "그래픽카드", "그래픽 카드", "그래픽", "vga", "rtx", "cuda", "nvidia", "엔비디아", "geforce", "지포스")),
                new CategoryKeywords("CPU", List.of("cpu", "프로세서", "라이젠", "ryzen", "intel", "인텔")),
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
            Map<String, Object> request,
            BudgetIntent rawBudgetIntent,
            BuildChatGuardStats guardStats
    ) {
        List<String> warnings = new ArrayList<>();
        List<AiChatEngineResponse.PartRecommendation> safePartRecommendations = failSafePartRecommendations(engineResponse.partRecommendations(), request, warnings, guardStats);
        List<Map<String, Object>> builds = switch (engineResponse.intent()) {
            case FULL_BUILD_RECOMMEND -> engineBuilds(engineResponse, rawBudgetIntent, warnings, guardStats);
            case PART_RECOMMEND, BUILD_MODIFY -> changedCurrentBuilds(engineResponse, request, safePartRecommendations, warnings);
            default -> engineBuilds(engineResponse, rawBudgetIntent, warnings, guardStats);
        };
        Map<String, Object> partRecommendation = partRecommendation(safePartRecommendations, engineResponse.parsedContext());
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.addAll(publicEngineActions(engineResponse.actions()));
        actions.addAll(draftActions(engineResponse, request, safePartRecommendations));
        warnings.addAll(buildWarnings(builds));
        warnings.addAll(stringList(engineResponse.parsedContext().get("warnings")));
        return MockData.map(
                "answerType", answerType(engineResponse.intent()),
                "message", engineResponse.assistantMessage(),
                "builds", builds,
                "partRecommendation", partRecommendation,
                "actions", actions,
                "warnings", distinct(warnings),
                "evidenceIds", engineResponse.evidenceIds(),
                "agentSessionId", engineResponse.agentSessionId()
        );
    }

    private Map<String, Object> routeResponse(RouteIntent routeIntent) {
        return MockData.map(
                "answerType", "GENERAL",
                "message", routeIntent.message(),
                "builds", List.of(),
                "partRecommendation", null,
                "actions", List.of(actionMap(
                        "OPEN_ROUTE",
                        routeIntent.label(),
                        routeIntent.message(),
                        MockData.map(
                                "route", routeIntent.route(),
                                "source", "AI_BUILD_CHAT",
                                "reason", "FAST_ROUTE"
                        ),
                        false
                )),
                "warnings", List.of(),
                "evidenceIds", List.of(),
                "agentSessionId", null
        );
    }

    private List<Map<String, Object>> publicEngineActions(List<AiChatAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiChatAction action : actions) {
            if (action == null || action.type() == null) {
                continue;
            }
            if ("OPEN_ROUTE".equals(action.type().name())) {
                String route = text(action.payload() == null ? null : action.payload().get("route"));
                if (route != null && isAllowedRoute(route)) {
                    result.add(actionMap(
                            "OPEN_ROUTE",
                            firstText(action.label(), "화면 이동"),
                            routeMessage(route),
                            MockData.map(
                                    "route", route,
                                    "source", "AI_BUILD_CHAT",
                                    "reason", "ENGINE_ROUTE"
                            ),
                            false
                    ));
                }
            } else if ("ADD_PART_TO_DRAFT".equals(action.type().name())) {
                Map<String, Object> payload = action.payload() == null ? Map.of() : action.payload();
                if (text(payload.get("partId")) != null && text(payload.get("category")) != null && numberValue(payload.get("quantity")) != null) {
                    result.add(actionMap(
                            "ADD_PART_TO_DRAFT",
                            firstText(action.label(), categoryLabel(text(payload.get("category"))) + " 추가"),
                            firstText(text(payload.get("name")), categoryLabel(text(payload.get("category")))) + "을(를) 견적에 추가합니다.",
                            new LinkedHashMap<>(payload),
                            false
                    ));
                }
            } else if ("CREATE_PRICE_ALERT".equals(action.type().name())) {
                Map<String, Object> payload = action.payload() == null ? Map.of() : action.payload();
                if (numberValue(payload.get("targetPrice")) != null) {
                    result.add(actionMap(
                            "CREATE_PRICE_ALERT",
                            firstText(action.label(), "목표가 알림 설정"),
                            "요청한 목표가 조건으로 가격 알림을 준비합니다.",
                            new LinkedHashMap<>(payload),
                            false
                    ));
                }
            }
        }
        return result;
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
                    null,
                    List.of(),
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
                null,
                List.of(),
                warnings
        );
        response.put("simulation", simulation);

        return Optional.of(response);
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

        if (filters.isEmpty()) {
            return partRecommendations(category, 1).stream()
                    .findFirst()
                    .map(this::partCandidate);
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
        result.put("disclaimer", "실제 FPS는 게임 버전, 옵션, 드라이버, 냉각 상태에 따라 달라질 수 있습니다.");
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
                "label", "벤치마크 기반 점수",
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

    private Optional<Map<String, Object>> fastDraftActionResponse(Map<String, Object> request, String message) {
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        String category = detectPartCategory(message);
        if (draftItems.isEmpty()) {
            return fastNoDraftPartSelectionResponse(request, message, category);
        }

        if (isRemoveIntent(message)) {
            Map<String, Object> item = findDraftItem(draftItems, category, message);
            if (!item.isEmpty()) {
                return Optional.of(fastResponse(
                        "PART",
                        categoryLabel(text(item.get("category"))) + " 제거 변경안을 준비했습니다.",
                        null,
                        List.of(removeAction(item)),
                        List.of()
                ));
            }
            return Optional.empty();
        }

        if (isFastQuantityIntent(message)) {
            Map<String, Object> item = findDraftItem(draftItems, category, message);
            String itemCategory = text(item.get("category"));
            if (!item.isEmpty() && ("RAM".equals(itemCategory) || "STORAGE".equals(itemCategory))) {
                return Optional.of(fastResponse(
                        "PART",
                        categoryLabel(itemCategory) + " 수량 변경안을 준비했습니다.",
                        null,
                        List.of(quantityAction(item, message)),
                        List.of()
                ));
            }
            return Optional.empty();
        }

        String priceDirection = fastPriceDirection(message);
        if (priceDirection == null || partReplacementRanker == null) {
            return Optional.empty();
        }
        Map<String, Object> currentItem = category == null && "CHEAPER".equals(priceDirection)
                ? highestPricedDraftItem(draftItems)
                : findDraftItem(draftItems, category, message);
        if (currentItem.isEmpty()) {
            return Optional.empty();
        }
        String effectiveCategory = text(currentItem.get("category"));
        if (effectiveCategory == null) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> directReplacement = directReplacementResponse(request, message, effectiveCategory, draftItems);
        if (directReplacement.isPresent()) {
            return directReplacement;
        }

        Integer targetMaxPrice = category == null ? null : parseBudgetWon(message);
        List<AiChatEngineResponse.PartRecommendation> messageMatchedCandidates = messageMatchedBrandRecommendations(effectiveCategory, message, 80);
        List<AiChatEngineResponse.PartRecommendation> candidates = messageMatchedCandidates.isEmpty()
                ? preferMessageMatchedRecommendations(partRecommendations(effectiveCategory, 200), message)
                : messageMatchedCandidates;
        PartReplacementRanker.SelectionResult selection = partReplacementRanker.select(
                effectiveCategory,
                currentItem,
                priceDirection,
                targetMaxPrice,
                candidates,
                24
        );
        List<String> warnings = new ArrayList<>(selection.warnings());
        List<AiChatEngineResponse.PartRecommendation> safeRecommendations = failSafePartRecommendations(selection.parts(), request, warnings);
        List<Map<String, Object>> actions = replacementActions(safeRecommendations, draftItems, false);
        if (actions.isEmpty()) {
            return Optional.of(fastResponse(
                    "PART",
                    categoryLabel(effectiveCategory) + " 조건에 맞는 안전한 교체 후보를 찾지 못했습니다. 조건을 조금 넓혀 다시 요청해 주세요.",
                    null,
                    List.of(askFollowUpAction(categoryLabel(effectiveCategory) + " 조건 조정 필요", "현재 견적 기준에서 바로 적용 가능한 안전 후보가 부족합니다.")),
                    distinct(warnings)
            ));
        }
        return Optional.of(fastResponse(
                "PART",
                categoryLabel(effectiveCategory) + " 교체 후보를 바로 찾았습니다.",
                partRecommendation(safeRecommendations, Map.of()),
                actions,
                warnings
        ));
    }

    private Optional<Map<String, Object>> directReplacementResponse(
            Map<String, Object> request,
            String message,
            String category,
            List<Map<String, Object>> draftItems
    ) {
        List<AiChatEngineResponse.PartRecommendation> directCandidates = directReplacementCandidates(category, message);
        if (directCandidates.isEmpty()) {
            return Optional.empty();
        }
        List<String> warnings = new ArrayList<>();
        List<AiChatEngineResponse.PartRecommendation> safeRecommendations = failSafePartRecommendations(directCandidates, request, warnings)
                .stream()
                .limit(3)
                .toList();
        List<Map<String, Object>> actions = replacementActions(safeRecommendations, draftItems, false);
        if (actions.isEmpty()) {
            return Optional.of(fastResponse(
                    "PART",
                    categoryLabel(category) + " 조건에 맞는 안전한 교체 후보를 찾지 못했습니다. 조건을 조금 넓혀 다시 요청해 주세요.",
                    null,
                    List.of(askFollowUpAction(categoryLabel(category) + " 조건 조정 필요", "현재 견적 기준에서 바로 적용 가능한 안전 후보가 부족합니다.")),
                    distinct(warnings)
            ));
        }
        return Optional.of(fastResponse(
                "PART",
                categoryLabel(category) + " 조건에 맞는 교체 후보를 바로 찾았습니다.",
                partRecommendation(safeRecommendations, Map.of()),
                actions,
                warnings
        ));
    }

    private Optional<Map<String, Object>> fastNoDraftPartSelectionResponse(
            Map<String, Object> request,
            String message,
            String category
    ) {
        if (category == null) {
            return Optional.empty();
        }
        List<AiChatEngineResponse.PartRecommendation> directCandidates = directReplacementCandidates(category, message);
        List<AiChatEngineResponse.PartRecommendation> candidates = directCandidates.isEmpty()
                ? noDraftPartSelectionCandidates(category, message)
                : directCandidates;
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<String> warnings = new ArrayList<>();
        List<AiChatEngineResponse.PartRecommendation> safeRecommendations = failSafePartRecommendations(candidates, request, warnings)
                .stream()
                .limit(3)
                .toList();
        if (safeRecommendations.isEmpty()) {
            return Optional.of(fastResponse(
                    "PART",
                    categoryLabel(category) + " 조건에 맞는 안전한 후보를 찾지 못했습니다. 조건을 조금 넓혀 다시 요청해 주세요.",
                    null,
                    List.of(askFollowUpAction(categoryLabel(category) + " 조건 조정 필요", "현재 조건에서 바로 제안할 수 있는 안전 후보가 부족합니다.")),
                    distinct(warnings)
            ));
        }
        return Optional.of(fastResponse(
                "PART",
                categoryLabel(category) + " 후보를 바로 찾았습니다. 적용 전 확인해 주세요.",
                partRecommendation(safeRecommendations, Map.of()),
                replacementActions(safeRecommendations, List.of(), false),
                warnings
        ));
    }

    private List<AiChatEngineResponse.PartRecommendation> directReplacementCandidates(String category, String message) {
        List<AiChatEngineResponse.PartRecommendation> brandCandidates = messageMatchedBrandRecommendations(category, message, 80);
        if (!brandCandidates.isEmpty()) {
            return brandCandidates;
        }
        Integer wattage = parseWattage(message);
        if ("PSU".equals(category) && wattage != null && wattage > 0) {
            return partRecommendations(category, 200).stream()
                    .filter(part -> Math.max(
                            firstNumber(part.attributes().get("capacityW"), 0),
                            firstNumber(part.attributes().get("wattage"), 0)
                    ) >= wattage)
                    .toList();
        }
        return List.of();
    }

    private List<AiChatEngineResponse.PartRecommendation> noDraftPartSelectionCandidates(String category, String message) {
        List<AiChatEngineResponse.PartRecommendation> candidates = partRecommendations(category, 200);
        Integer capacityGb = parseCapacityGb(message);
        if (capacityGb != null && ("RAM".equals(category) || "STORAGE".equals(category))) {
            List<AiChatEngineResponse.PartRecommendation> capacityMatches = candidates.stream()
                    .filter(part -> firstNumber(part.attributes().get("capacityGb"), 0) >= capacityGb)
                    .toList();
            if (!capacityMatches.isEmpty()) {
                return capacityMatches;
            }
        }
        return preferMessageMatchedRecommendations(candidates, message);
    }

    private List<AiChatEngineResponse.PartRecommendation> preferMessageMatchedRecommendations(
            List<AiChatEngineResponse.PartRecommendation> candidates,
            String message
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String normalized = normalizeCommand(message);
        List<AiChatEngineResponse.PartRecommendation> matched = candidates.stream()
                .filter(candidate -> messageMatchesPartCandidate(normalized, candidate))
                .toList();
        return matched.isEmpty() ? candidates : matched;
    }

    private boolean messageMatchesPartCandidate(String normalizedMessage, AiChatEngineResponse.PartRecommendation candidate) {
        if (normalizedMessage == null || normalizedMessage.isBlank() || candidate == null) {
            return false;
        }
        String manufacturer = normalizeCommand(candidate.manufacturer());
        if (!manufacturer.isBlank() && normalizedMessage.contains(manufacturer)) {
            return true;
        }
        String name = normalizeCommand(candidate.name());
        for (String token : normalizedMessage.split("[^0-9a-zA-Z가-힣]+")) {
            if (token.length() >= 3 && name.contains(token)) {
                return true;
            }
        }
        for (String token : List.of("msi", "asus", "gigabyte", "기가바이트", "리안리", "lianli", "corsair", "커세어", "samsung", "삼성")) {
            String normalizedToken = normalizeCommand(token);
            if (normalizedMessage.contains(normalizedToken) && name.contains(normalizedToken)) {
                return true;
            }
        }
        Matcher numericToken = Pattern.compile("\\d{3,5}[a-zA-Z]*").matcher(normalizedMessage);
        while (numericToken.find()) {
            if (name.contains(normalizeCommand(numericToken.group()))) {
                return true;
            }
        }
        return false;
    }

    private List<AiChatEngineResponse.PartRecommendation> messageMatchedBrandRecommendations(String category, String message, int limit) {
        String brandToken = brandToken(message);
        if (category == null || brandToken == null) {
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
                          AND (
                            lower(coalesce(p.manufacturer, '')) LIKE '%' || lower(?) || '%'
                            OR lower(p.name) LIKE '%' || lower(?) || '%'
                          )
                        ORDER BY b.score DESC NULLS LAST, p.price ASC, p.name ASC
                        LIMIT ?
                        """, category, brandToken, brandToken, Math.max(1, limit))
                .stream()
                .map(this::partRecommendation)
                .toList();
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

    private Map<String, Object> fastResponse(
            String answerType,
            String message,
            Map<String, Object> partRecommendation,
            List<Map<String, Object>> actions,
            List<String> warnings
    ) {
        return fastResponse(answerType, message, List.of(), partRecommendation, actions, warnings);
    }

    private Map<String, Object> fastResponse(
            String answerType,
            String message,
            List<Map<String, Object>> builds,
            Map<String, Object> partRecommendation,
            List<Map<String, Object>> actions,
            List<String> warnings
    ) {
        return MockData.map(
                "answerType", answerType,
                "message", message,
                "builds", builds == null ? List.of() : builds,
                "partRecommendation", partRecommendation,
                "actions", actions,
                "warnings", distinct(warnings),
                "evidenceIds", List.of(),
                "agentSessionId", null
        );
    }

    private Optional<Map<String, Object>> deterministicFastResponse(Map<String, Object> request, String message, BudgetIntent rawBudgetIntent) {
        if (isPriceAlertIntent(message)) {
            Optional<Map<String, Object>> priceAlert = deterministicPriceAlertResponse(request, message);
            if (priceAlert.isPresent()) {
                return priceAlert;
            }
        }

        String category = detectPartCategory(message);
        if (isStandalonePartRecommend(message, request, category)) {
            List<AiChatEngineResponse.PartRecommendation> recommendations = partRecommendations(category, 3);
            if (!recommendations.isEmpty()) {
                Map<String, Object> parsedContext = Map.of();
                return Optional.of(fastResponse(
                        "PART",
                        categoryLabel(category) + " 후보를 내부 자산과 벤치마크 기준으로 바로 골랐습니다.",
                        List.of(),
                        partRecommendation(recommendations, parsedContext),
                        replacementActions(recommendations, List.of(), false),
                        List.of()
                ));
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
                        null,
                        List.of(),
                        warnings
                ));
            }
        }
        return Optional.empty();
    }

    private Optional<Map<String, Object>> deterministicPriceAlertResponse(Map<String, Object> request, String message) {
        Integer targetPrice = parseBudgetWon(message);
        if (targetPrice == null || targetPrice <= 0) {
            return Optional.empty();
        }
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        if (draftItems.isEmpty()) {
            return Optional.empty();
        }
        String category = detectPartCategory(message);
        Map<String, Object> item = findDraftItem(draftItems, category, message);
        if (item.isEmpty()) {
            item = draftItems.get(0);
        }
        String itemCategory = text(item.get("category"));
        String itemName = firstText(text(item.get("name")), categoryLabel(itemCategory));
        Map<String, Object> action = actionMap(
                "CREATE_PRICE_ALERT",
                itemName + " 목표가 알림",
                itemName + " 가격이 " + formatBudgetLabel(targetPrice) + " 이하가 되면 알림을 받을 수 있게 준비합니다.",
                MockData.map(
                        "partId", text(item.get("partId")),
                        "category", itemCategory,
                        "targetPrice", targetPrice,
                        "source", "AI_BUILD_CHAT"
                )
        );
        return Optional.of(fastResponse(
                "GENERAL",
                itemName + " 목표가 알림을 바로 준비했습니다.",
                List.of(),
                null,
                List.of(action),
                List.of()
        ));
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

        int ramQuantity = rawBudgetIntent.budget() <= 1_200_000 ? 1 : 2;
        List<Map<String, Object>> result = new ArrayList<>();
        boolean preferLiteBuild = rawBudgetIntent.budget() <= 1_500_000;
        if (preferLiteBuild) {
            collectBudgetFallbackBuilds(false, false, ramQuantity, rawBudgetIntent, engineResponse.evidenceIds(), result);
            collectBudgetFallbackBuilds(true, true, ramQuantity, rawBudgetIntent, engineResponse.evidenceIds(), result);
        } else {
            collectBudgetFallbackBuilds(true, true, ramQuantity, rawBudgetIntent, engineResponse.evidenceIds(), result);
            collectBudgetFallbackBuilds(false, false, ramQuantity, rawBudgetIntent, engineResponse.evidenceIds(), result);
        }

        if (!result.isEmpty()) {
            warnings.add("명시 예산 범위에 맞춰 내부 자산 기준 보조 견적을 재구성했습니다.");
            if (guardStats != null) {
                guardStats.routeFallbackUsed = true;
            }
        }
        return result.stream().limit(3).toList();
    }

    private void collectBudgetFallbackBuilds(
            boolean includeGpu,
            boolean includeCooler,
            int ramQuantity,
            BudgetIntent rawBudgetIntent,
            List<String> evidenceIds,
            List<Map<String, Object>> result
    ) {
        if (result.size() >= 3) {
            return;
        }
        List<List<PartCandidate>> groups = new ArrayList<>();
        for (String category : fallbackCategories(includeGpu, includeCooler)) {
            List<PartCandidate> candidates = pricePartCandidates(category, fallbackCandidateLimit(category, includeGpu));
            if (candidates.isEmpty()) {
                return;
            }
            groups.add(candidates);
        }
        int lowerBound = budgetLowerBound(rawBudgetIntent);
        int upperBound = budgetUpperBound(rawBudgetIntent);
        int[] minRemaining = minRemainingPrices(groups, ramQuantity);
        int[] maxRemaining = maxRemainingPrices(groups, ramQuantity);
        collectBudgetFallbackCombination(
                groups,
                0,
                new ArrayList<>(),
                new LinkedHashSet<>(),
                0,
                lowerBound,
                upperBound,
                minRemaining,
                maxRemaining,
                ramQuantity,
                rawBudgetIntent,
                evidenceIds,
                result
        );
    }

    private void collectBudgetFallbackCombination(
            List<List<PartCandidate>> groups,
            int groupIndex,
            List<PartCandidate> selected,
            LinkedHashSet<String> seenKeys,
            int partialPrice,
            int lowerBound,
            int upperBound,
            int[] minRemaining,
            int[] maxRemaining,
            int ramQuantity,
            BudgetIntent rawBudgetIntent,
            List<String> evidenceIds,
            List<Map<String, Object>> result
    ) {
        if (result.size() >= 3) {
            return;
        }
        if (partialPrice + minRemaining[groupIndex] > upperBound) {
            return;
        }
        if (partialPrice + maxRemaining[groupIndex] < lowerBound) {
            return;
        }
        if (groupIndex >= groups.size()) {
            String key = selected.stream().map(PartCandidate::publicId).toList().toString();
            if (!seenKeys.add(key)) {
                return;
            }
            int totalPrice = partialPrice;
            if (!withinBudgetGuard(MockData.map("totalPrice", totalPrice), Map.of(), rawBudgetIntent)) {
                return;
            }
            List<String> localWarnings = new ArrayList<>();
            int toolBudget = "MAX".equals(rawBudgetIntent.mode()) ? rawBudgetIntent.budget() : totalPrice;
            List<Map<String, Object>> toolResults = toolResults(selected, toolBudget, localWarnings);
            if (hasBlockingToolFailure(toolResults)) {
                return;
            }
            localWarnings.addAll(toolWarnings(toolResults));
            Tier tier = TIERS.get(Math.min(result.size(), TIERS.size() - 1));
            result.add(budgetFallbackBuildMap(tier, selected, rawBudgetIntent, ramQuantity, toolResults, localWarnings, evidenceIds));
            return;
        }
        for (PartCandidate candidate : groups.get(groupIndex)) {
            int candidatePrice = effectiveCandidatePrice(candidate, ramQuantity);
            int nextPartialPrice = partialPrice + candidatePrice;
            if (nextPartialPrice + minRemaining[groupIndex + 1] > upperBound) {
                break;
            }
            if (nextPartialPrice + maxRemaining[groupIndex + 1] < lowerBound) {
                continue;
            }
            selected.add(candidate);
            collectBudgetFallbackCombination(
                    groups,
                    groupIndex + 1,
                    selected,
                    seenKeys,
                    nextPartialPrice,
                    lowerBound,
                    upperBound,
                    minRemaining,
                    maxRemaining,
                    ramQuantity,
                    rawBudgetIntent,
                    evidenceIds,
                    result
            );
            selected.remove(selected.size() - 1);
            if (result.size() >= 3) {
                return;
            }
        }
    }

    private static int budgetLowerBound(BudgetIntent rawBudgetIntent) {
        if (rawBudgetIntent == null || !rawBudgetIntent.hasBudget()) {
            return 0;
        }
        if ("MIN".equals(rawBudgetIntent.mode())) {
            return rawBudgetIntent.budget();
        }
        if ("TARGET".equals(rawBudgetIntent.mode())) {
            return (int) Math.floor(rawBudgetIntent.budget() * 0.875);
        }
        return 0;
    }

    private static int budgetUpperBound(BudgetIntent rawBudgetIntent) {
        if (rawBudgetIntent == null || !rawBudgetIntent.hasBudget()) {
            return Integer.MAX_VALUE;
        }
        if ("MAX".equals(rawBudgetIntent.mode())) {
            return rawBudgetIntent.budget();
        }
        if ("TARGET".equals(rawBudgetIntent.mode())) {
            return (int) Math.ceil(rawBudgetIntent.budget() * 1.125);
        }
        return Integer.MAX_VALUE;
    }

    private static int[] minRemainingPrices(List<List<PartCandidate>> groups, int ramQuantity) {
        int[] remaining = new int[groups.size() + 1];
        remaining[groups.size()] = 0;
        for (int index = groups.size() - 1; index >= 0; index -= 1) {
            int min = groups.get(index).stream()
                    .mapToInt(candidate -> effectiveCandidatePrice(candidate, ramQuantity))
                    .min()
                    .orElse(0);
            remaining[index] = remaining[index + 1] + min;
        }
        return remaining;
    }

    private static int[] maxRemainingPrices(List<List<PartCandidate>> groups, int ramQuantity) {
        int[] remaining = new int[groups.size() + 1];
        remaining[groups.size()] = 0;
        for (int index = groups.size() - 1; index >= 0; index -= 1) {
            int max = groups.get(index).stream()
                    .mapToInt(candidate -> effectiveCandidatePrice(candidate, ramQuantity))
                    .max()
                    .orElse(0);
            remaining[index] = remaining[index + 1] + max;
        }
        return remaining;
    }

    private static int effectiveCandidatePrice(PartCandidate candidate, int ramQuantity) {
        int quantity = "RAM".equals(candidate.category()) ? Math.max(1, Math.min(4, ramQuantity)) : defaultQuantity(candidate.category());
        return Math.max(0, candidate.price() == null ? 0 : candidate.price()) * quantity;
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

    private List<Map<String, Object>> changedCurrentBuilds(
            AiChatEngineResponse engineResponse,
            Map<String, Object> request,
            List<AiChatEngineResponse.PartRecommendation> options,
            List<String> warnings
    ) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        String category = options.get(0).category();
        List<AiBuildCandidate> baseBuilds = currentBuilds(request.get("currentBuilds"), warnings);
        if (baseBuilds.isEmpty()) {
            return List.of();
        }
        List<PartCandidate> replacements = options.stream().map(this::partCandidate).toList();
        List<Map<String, Object>> updatedBuilds = new ArrayList<>();
        for (int index = 0; index < baseBuilds.size(); index += 1) {
            AiBuildCandidate baseBuild = baseBuilds.get(index);
            PartCandidate replacement = replacements.get(Math.min(index, replacements.size() - 1));
            List<PartCandidate> nextParts = baseBuild.parts().stream()
                    .map(part -> category.equals(part.category()) ? replacement : part)
                    .toList();
            List<String> applied = new ArrayList<>(baseBuild.appliedPartCategories());
            applied.add(category);
            updatedBuilds.add(buildMap(new AiBuildCandidate(baseBuild.tier(), baseBuild.budgetWon(), baseBuild.budgetLabel(), nextParts, distinct(applied))));
        }
        return updatedBuilds;
    }

    private Map<String, Object> partRecommendation(List<AiChatEngineResponse.PartRecommendation> parts, Map<String, Object> parsedContext) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        String category = parts.get(0).category();
        return MockData.map(
                "category", category,
                "label", categoryLabel(category),
                "intro", categoryLabel(category) + " 후보를 AI 엔진과 내부 자산 DB 기준으로 정리했습니다.",
                "options", parts.stream()
                        .map(part -> {
                            PartCandidate candidate = partCandidate(part);
                            return partItem(candidate, "AI 엔진 내부 자산 추천", quantityForRecommendation(candidate, parsedContext));
                        })
                        .toList()
        );
    }

    private List<Map<String, Object>> draftActions(
            AiChatEngineResponse engineResponse,
            Map<String, Object> request,
            List<AiChatEngineResponse.PartRecommendation> safePartRecommendations
    ) {
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        if (currentQuoteDraft.isEmpty()) {
            return List.of();
        }
        String message = text(request.get("message"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        Map<String, Object> parsedContext = engineResponse.parsedContext() == null ? Map.of() : engineResponse.parsedContext();
        Map<String, Object> draftEdit = objectMap(parsedContext.get("draftEdit"));
        String category = firstText(text(draftEdit.get("category")), firstText(detectPartCategory(message), firstRecommendedCategory(engineResponse)));
        String operation = text(draftEdit.get("operation"));
        String priceDirection = text(draftEdit.get("priceDirection"));

        if ("REMOVE".equals(operation) || isRemoveIntent(message)) {
            Map<String, Object> item = findDraftItem(draftItems, category, message);
            if (!item.isEmpty()) {
                return List.of(removeAction(item));
            }
            return List.of(askFollowUpAction("삭제할 부품을 찾지 못했습니다.", "예: GPU 빼줘, RAM 삭제해줘처럼 부품 종류를 함께 알려주세요."));
        }

        if ("UPDATE_QUANTITY".equals(operation) || isQuantityIntent(message) || parseCapacityGb(message) != null) {
            Map<String, Object> item = findDraftItem(draftItems, category, message);
            if (!item.isEmpty()) {
                return List.of(quantityAction(item, message));
            }
        }

        if ("CHEAPER".equals(priceDirection) || isBudgetIntent(message)) {
            List<Map<String, Object>> actions = replacementActions(safePartRecommendations, draftItems, true);
            return actions.isEmpty()
                    ? List.of(askFollowUpAction("예산 조정 후보가 부족합니다.", "CPU/GPU/RAM처럼 낮추고 싶은 부품을 알려주면 교체안을 제안할 수 있습니다."))
                    : actions;
        }

        List<Map<String, Object>> actions = replacementActions(safePartRecommendations, draftItems, false);
        if (!actions.isEmpty()) {
            return actions;
        }

        return List.of();
    }

    private List<Map<String, Object>> replacementActions(
            List<AiChatEngineResponse.PartRecommendation> recommendations,
            List<Map<String, Object>> draftItems,
            boolean multiple
    ) {
        if (recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        int limit = 1;
        for (int index = 0; index < limit; index += 1) {
            AiChatEngineResponse.PartRecommendation candidate = recommendations.get(index);
            Map<String, Object> existing = findDraftItem(draftItems, candidate.category(), candidate.name());
            String type = existing.isEmpty() ? "ADD_PART_TO_DRAFT" : "REPLACE_DRAFT_PART";
            int quantity = quantityForRecommendation(partCandidate(candidate), Map.of());
            actions.add(actionMap(
                    type,
                    categoryLabel(candidate.category()) + " " + (existing.isEmpty() ? "추가" : "교체"),
                    candidate.name() + "을(를) 견적에 " + (existing.isEmpty() ? "추가합니다." : "반영합니다."),
                    MockData.map(
                            "partId", candidate.partId(),
                            "category", candidate.category(),
                            "quantity", quantity,
                            "source", "AI_BUILD_CHAT",
                            "currentPartId", text(existing.get("partId"))
                    )
            ));
        }
        return actions;
    }

    private List<AiChatEngineResponse.PartRecommendation> failSafePartRecommendations(
            List<AiChatEngineResponse.PartRecommendation> recommendations,
            Map<String, Object> request,
            List<String> warnings
    ) {
        return failSafePartRecommendations(recommendations, request, warnings, null);
    }

    private List<AiChatEngineResponse.PartRecommendation> failSafePartRecommendations(
            List<AiChatEngineResponse.PartRecommendation> recommendations,
            Map<String, Object> request,
            List<String> warnings,
            BuildChatGuardStats guardStats
    ) {
        if (recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        if (draftItems.isEmpty()) {
            return recommendations;
        }
        List<AiChatEngineResponse.PartRecommendation> safe = new ArrayList<>();
        int excluded = 0;
        for (AiChatEngineResponse.PartRecommendation recommendation : recommendations) {
            if (!hasToolCheckContextForRecommendation(draftItems, recommendation.category())) {
                safe.add(recommendation);
                continue;
            }
            List<PartCandidate> nextParts = replacementPreviewParts(draftItems, recommendation);
            List<String> localWarnings = new ArrayList<>();
            List<Map<String, Object>> toolResults = toolResults(nextParts, totalPrice(nextParts), localWarnings);
            if (hasBlockingToolFailure(toolResults)) {
                excluded += 1;
                continue;
            }
            safe.add(recommendation);
        }
        if (excluded > 0) {
            if (guardStats != null) {
                guardStats.blockingFailDropped += excluded;
            }
            warnings.add("Tool FAIL 후보 " + excluded + "개를 추천/적용 후보에서 제외했습니다.");
        }
        return safe;
    }

    private boolean hasToolCheckContextForRecommendation(List<Map<String, Object>> draftItems, String replacementCategory) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (Map<String, Object> item : draftItems) {
            String category = text(item.get("category"));
            if (category != null) {
                categories.add(category);
            }
        }
        return switch (replacementCategory) {
            case "CPU" -> categories.contains("MOTHERBOARD");
            case "MOTHERBOARD" -> categories.contains("CPU") || categories.contains("RAM");
            case "RAM" -> categories.contains("MOTHERBOARD");
            case "GPU" -> categories.contains("CASE") || categories.contains("PSU");
            case "PSU" -> categories.contains("GPU");
            case "CASE" -> categories.contains("GPU") || categories.contains("COOLER") || categories.contains("PSU");
            case "COOLER" -> categories.contains("CPU") || categories.contains("CASE");
            default -> false;
        };
    }

    private List<PartCandidate> replacementPreviewParts(List<Map<String, Object>> draftItems, AiChatEngineResponse.PartRecommendation recommendation) {
        String category = recommendation.category();
        List<PartCandidate> nextParts = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> item : draftItems) {
            if (category.equals(text(item.get("category")))) {
                if (!replaced) {
                    nextParts.add(partCandidate(recommendation));
                    replaced = true;
                }
                continue;
            }
            PartCandidate draftPart = partCandidateFromDraftItem(item);
            if (draftPart != null) {
                nextParts.add(draftPart);
            }
        }
        if (!replaced) {
            nextParts.add(partCandidate(recommendation));
        }
        return nextParts;
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

    private Map<String, Object> removeAction(Map<String, Object> item) {
        String category = text(item.get("category"));
        String name = firstText(text(item.get("name")), categoryLabel(category));
        return actionMap(
                "REMOVE_DRAFT_PART",
                categoryLabel(category) + " 빼기",
                name + "을(를) 견적에서 제거합니다. 필수 부품을 제거하면 조합 검증에서 경고가 발생할 수 있습니다.",
                MockData.map(
                        "partId", text(item.get("partId")),
                        "category", category,
                        "source", "AI_BUILD_CHAT"
                )
        );
    }

    private Map<String, Object> quantityAction(Map<String, Object> item, String message) {
        String category = text(item.get("category"));
        if (!"RAM".equals(category) && !"STORAGE".equals(category)) {
            return askFollowUpAction("수량 변경은 RAM/SSD에 우선 적용됩니다.", categoryLabel(category) + "은 보통 1개 구성이라 교체할 제품을 알려주세요.");
        }
        int currentQuantity = Math.max(1, number(item.get("quantity"), 1));
        int nextQuantity = parseQuantity(message);
        Integer targetCapacityGb = parseCapacityGb(message);
        if (nextQuantity <= 0 && targetCapacityGb != null) {
            int perUnitGb = Math.max(1, capacityPerUnitGb(item));
            nextQuantity = Math.max(1, (int) Math.ceil(targetCapacityGb / (double) perUnitGb));
        }
        if (nextQuantity <= 0) {
            nextQuantity = currentQuantity + 1;
        }
        nextQuantity = Math.max(1, Math.min(9, nextQuantity));
        String name = firstText(text(item.get("name")), categoryLabel(category));
        return actionMap(
                "UPDATE_DRAFT_QUANTITY",
                categoryLabel(category) + " 수량 " + nextQuantity + "개로 변경",
                name + " 수량을 " + currentQuantity + "개에서 " + nextQuantity + "개로 변경합니다.",
                MockData.map(
                        "partId", text(item.get("partId")),
                        "category", category,
                        "quantity", nextQuantity,
                        "source", "AI_BUILD_CHAT"
                )
        );
    }

    private Map<String, Object> askFollowUpAction(String label, String description) {
        return actionMap(
                "ASK_FOLLOW_UP",
                label,
                description,
                MockData.map("source", "AI_BUILD_CHAT")
        );
    }

    private Map<String, Object> actionMap(String type, String label, String description, Map<String, Object> payload) {
        return actionMap(type, label, description, payload, false);
    }

    private Map<String, Object> actionMap(String type, String label, String description, Map<String, Object> payload, boolean requiresConfirmation) {
        Map<String, Object> payloadWithPolicy = new LinkedHashMap<>(payload == null ? Map.of() : payload);
        applyActionSafetyMetadata(type, payloadWithPolicy);
        String suffix = firstText(text(payloadWithPolicy.get("partId")), firstText(text(payloadWithPolicy.get("category")), label));
        return MockData.map(
                "id", "draft-action-" + type.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + slug(suffix),
                "type", type,
                "label", label,
                "description", description,
                "payload", payloadWithPolicy,
                "requiresConfirmation", requiresConfirmation
        );
    }

    private static void applyActionSafetyMetadata(String type, Map<String, Object> payload) {
        if (payload.containsKey("intentConfidence") && payload.containsKey("sideEffectRisk")) {
            return;
        }
        switch (type) {
            case "OPEN_ROUTE" -> {
                payload.put("intentConfidence", "HIGH");
                payload.put("sideEffectRisk", "NONE");
            }
            case "REMOVE_DRAFT_PART", "UPDATE_DRAFT_QUANTITY" -> {
                payload.put("intentConfidence", "HIGH");
                payload.put("sideEffectRisk", "LOW");
            }
            case "ASK_FOLLOW_UP" -> {
                payload.put("intentConfidence", "LOW");
                payload.put("sideEffectRisk", "NONE");
            }
            default -> {
                payload.put("intentConfidence", "MEDIUM");
                payload.put("sideEffectRisk", "MEDIUM");
            }
        }
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

    private String firstRecommendedCategory(AiChatEngineResponse engineResponse) {
        if (engineResponse.partRecommendations() == null || engineResponse.partRecommendations().isEmpty()) {
            return null;
        }
        return engineResponse.partRecommendations().get(0).category();
    }

    private Map<String, Object> highestPricedDraftItem(List<Map<String, Object>> draftItems) {
        return draftItems.stream()
                .filter(item -> text(item.get("category")) != null)
                .max((left, right) -> Integer.compare(
                        number(firstNumber(left.get("lineTotal"), left.get("currentPrice"), left.get("price"), left.get("unitPriceAtAdd")), 0),
                        number(firstNumber(right.get("lineTotal"), right.get("currentPrice"), right.get("price"), right.get("unitPriceAtAdd")), 0)
                ))
                .orElse(Map.of());
    }

    private boolean isRemoveIntent(String message) {
        return containsAny(message, "빼", "삭제", "제거", "remove", "delete");
    }

    private boolean isQuantityIntent(String message) {
        return containsAny(message, "수량", "개로", "개", "장", "늘려", "줄여");
    }

    private boolean isBudgetIntent(String message) {
        return containsAny(message, "예산", "낮춰", "줄여", "안으로", "이하", "초과", "비싸");
    }

    private boolean isFastQuantityIntent(String message) {
        String normalized = normalizeCommand(message);
        return parseQuantity(message) > 0
                || parseCapacityGb(message) != null
                || containsAnyNormalized(normalized, "수량", "개로", "장으로", "늘려", "줄여");
    }

    private String fastPriceDirection(String message) {
        String normalized = normalizeCommand(message);
        if (!isFastReplacementCommand(normalized)) {
            return null;
        }
        if (containsAnyNormalized(normalized, "비슷한가격", "비슷한금액", "그가격대", "동급", "유사한가격", "비슷한걸로")) {
            return "SIMILAR_PRICE";
        }
        if (containsAnyNormalized(normalized, "더싼", "싼걸", "저렴", "비싸", "낮춰", "예산낮", "가격낮", "가성비")) {
            return "CHEAPER";
        }
        if (containsAnyNormalized(normalized, "더좋", "상위", "업그레이드", "더빠른", "빠른걸", "여유", "넉넉", "잘식히", "조용", "고성능", "큰그래픽카드", "이상")) {
            return "MORE_EXPENSIVE";
        }
        if (containsAnyNormalized(normalized, "맞춰", "걸로", "브랜드", "모델")) {
            return "SIMILAR_PRICE";
        }
        return null;
    }

    private static boolean isFastReplacementCommand(String normalized) {
        return containsAnyNormalized(
                normalized,
                "바꿔",
                "교체",
                "걸로",
                "맞춰",
                "추천",
                "더싼",
                "더좋",
                "비슷한가격",
                "업그레이드",
                "여유",
                "넉넉",
                "빠른",
                "조용",
                "잘식히",
                "낮춰",
                "이상"
        );
    }

    private RouteIntent routeIntent(String message) {
        String normalized = normalizeCommand(message);
        String category = detectPartCategory(message);
        if (category != null && hasRouteVerb(normalized) && !isProductDetailIntent(normalized) && !hasProductFilterHint(message)) {
            String route = "/self-quote?category=" + category;
            return new RouteIntent(route, categoryLabel(category) + " 부품 보기", categoryLabel(category) + " 부품 화면으로 이동했습니다.");
        }
        if (containsAnyNormalized(normalized, "셀프견적", "수동견적", "견적장바구니", "장바구니")
                && !isDraftMutationCommand(normalized)) {
            return new RouteIntent("/self-quote", "셀프 견적 열기", "셀프 견적 화면으로 이동했습니다.");
        }
        if (containsAnyNormalized(normalized, "내견적함", "견적함", "저장된견적", "견적목록")) {
            return new RouteIntent("/my/quotes", "내 견적함 열기", "내 견적함 화면으로 이동했습니다.");
        }
        if (containsAnyNormalized(normalized, "ai견적", "자연어견적", "요구사항", "견적입력")) {
            return new RouteIntent("/requirements/new", "AI 견적 열기", "AI 견적 입력 화면으로 이동했습니다.");
        }
        if (containsAnyNormalized(normalized, "as접수", "수리접수", "지원접수", "고장접수")) {
            return new RouteIntent("/support/new", "AS 접수 열기", "AS 접수 화면으로 이동했습니다.");
        }
        if (containsAnyNormalized(normalized, "as챗봇", "asai", "수리챗봇", "지원챗봇")) {
            return new RouteIntent("/support/ai-chat", "AS AI 챗봇 열기", "AS AI 챗봇 화면으로 이동했습니다.");
        }
        if (containsAnyNormalized(normalized, "구매하기", "결제", "checkout")) {
            return new RouteIntent("/checkout", "구매하기 열기", "구매하기 화면으로 이동했습니다.");
        }
        return null;
    }

    private static boolean hasRouteVerb(String normalized) {
        boolean routeLike = containsAnyNormalized(normalized, "보여", "열어", "이동", "가자", "목록", "페이지", "카테고리", "부품", "화면");
        boolean recommendationOnly = normalized.contains("추천")
                && !containsAnyNormalized(normalized, "보여", "열어", "목록", "페이지", "부품");
        return routeLike && !recommendationOnly;
    }

    private static boolean isDraftMutationCommand(String normalized) {
        return containsAnyNormalized(normalized, "담아", "넣어", "적용", "추가", "빼", "삭제", "제거", "바꿔", "교체", "수량", "변경");
    }

    private static boolean isProductDetailIntent(String normalized) {
        boolean detailWord = containsAnyNormalized(normalized, "상세", "상품페이지", "제품페이지", "제품상세", "상품상세");
        boolean concreteProductHint = Pattern.compile("\\d{3,5}").matcher(normalized).find()
                || containsAnyNormalized(normalized, "asus", "msi", "gigabyte", "lianli", "리안리", "samsung", "삼성", "corsair", "커세어", "noctua", "녹투아", "arctic");
        return detailWord && concreteProductHint;
    }

    private static boolean hasProductFilterHint(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String compact = normalizeCommand(message);
        return Pattern.compile("\\d{3,5}").matcher(normalized).find()
                || containsAnyNormalized(compact,
                "asus", "msi", "gigabyte", "기가바이트", "lianli", "리안리", "samsung", "삼성",
                "corsair", "커세어", "noctua", "녹투아", "arctic", "nvidia", "엔비디아", "amd", "intel", "인텔",
                "라이젠", "ddr5", "ddr4", "nvme", "수랭", "공랭", "aio");
    }

    private static boolean containsAny(String message, String... keywords) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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

    private static boolean isAllowedRoute(String route) {
        if (List.of(
                "/self-quote",
                "/my/quotes",
                "/requirements/new",
                "/support/new",
                "/support/ai-chat",
                "/checkout"
        ).contains(route)) {
            return true;
        }
        return route.matches("^/self-quote\\?category=(CPU|MOTHERBOARD|RAM|GPU|STORAGE|PSU|CASE|COOLER)(?:&q=[^#\\s]+)?$")
                || route.matches("^/parts/[0-9a-fA-F-]{8,}$");
    }

    private static String routeMessage(String route) {
        if (route.startsWith("/self-quote?category=")) {
            String category = routeCategory(route);
            return categoryLabel(category) + " 부품 화면으로 이동했습니다.";
        }
        return switch (route) {
            case "/self-quote" -> "셀프 견적 화면으로 이동했습니다.";
            case "/my/quotes" -> "내 견적함 화면으로 이동했습니다.";
            case "/requirements/new" -> "AI 견적 입력 화면으로 이동했습니다.";
            case "/support/new" -> "AS 접수 화면으로 이동했습니다.";
            case "/support/ai-chat" -> "AS AI 챗봇 화면으로 이동했습니다.";
            case "/checkout" -> "구매하기 화면으로 이동했습니다.";
            default -> "요청한 화면으로 이동했습니다.";
        };
    }

    private static String routeCategory(String route) {
        Matcher matcher = Pattern.compile("[?&]category=(CPU|MOTHERBOARD|RAM|GPU|STORAGE|PSU|CASE|COOLER)").matcher(route == null ? "" : route);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void logBuildChatPath(
            String pathType,
            long startedNanos,
            Long userId,
            String requestedAiProfile,
            boolean cacheHit,
            BuildChatGuardStats guardStats
    ) {
        long latencyMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        BuildChatGuardStats stats = guardStats == null ? BuildChatGuardStats.empty() : guardStats;
        log.info(
                "Build Chat pathType={} latencyMs={} userId={} requestedAiProfile={} cacheHit={} budgetGuardDropped={} blockingFailDropped={} routeFallbackUsed={}",
                pathType,
                latencyMs,
                userId,
                requestedAiProfile,
                cacheHit,
                stats.budgetGuardDropped,
                stats.blockingFailDropped,
                stats.routeFallbackUsed
        );
    }

    private static int parseQuantity(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Matcher matcher = QUANTITY_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
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

    private static int capacityPerUnitGb(Map<String, Object> item) {
        Map<String, Object> attributes = objectMap(item.get("attributes"));
        Integer capacity = numberValue(attributes.get("capacityGb"));
        if (capacity == null) {
            capacity = numberValue(attributes.get("kitCapacityGb"));
        }
        if (capacity == null) {
            capacity = numberValue(attributes.get("memoryGb"));
        }
        if (capacity != null && capacity > 0) {
            return capacity;
        }
        Integer nameCapacity = parseCapacityGb(firstText(text(item.get("name")), ""));
        return nameCapacity == null || nameCapacity <= 0 ? 32 : nameCapacity;
    }

    private List<AiBuildCandidate> currentBuilds(Object value, List<String> warnings) {
        List<Map<String, Object>> rawBuilds = objectMaps(value);
        if (rawBuilds.isEmpty()) {
            return List.of();
        }
        try {
            List<AiBuildCandidate> candidates = new ArrayList<>();
            for (int index = 0; index < rawBuilds.size(); index += 1) {
                Map<String, Object> rawBuild = rawBuilds.get(index);
                Tier tier = tier(text(rawBuild.get("tier")), index);
                List<PartCandidate> parts = objectMaps(rawBuild.get("items")).stream()
                        .map(item -> partByPublicId(text(item.get("partId"))))
                        .toList();
                String budgetLabel = text(rawBuild.get("budgetLabel"));
                int budgetWon = number(rawBuild.get("budgetWon"), totalPrice(parts));
                candidates.add(new AiBuildCandidate(tier, budgetWon, budgetLabel, parts, stringList(rawBuild.get("appliedPartCategories"))));
            }
            return candidates;
        } catch (RuntimeException error) {
            warnings.add("이전 AI 추천 조합을 최신 DB 가격으로 복원하지 못해 부품 후보만 표시합니다.");
            return List.of();
        }
    }

    private Map<String, Object> buildMap(AiBuildCandidate build) {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> items = build.parts().stream()
                .map(part -> partItem(part, "DB 현재가 기준"))
                .toList();
        int totalPrice = totalPrice(build.parts());
        boolean unspecifiedBudget = "예산 미지정".equals(build.budgetLabel());
        int toolBudget = build.budgetWon() <= 0 || unspecifiedBudget ? totalPrice : build.budgetWon();
        List<Map<String, Object>> toolResults = toolResults(build.parts(), toolBudget, warnings);
        warnings.addAll(toolWarnings(toolResults));
        String budgetLabel = firstText(build.budgetLabel(), build.budgetWon() <= 0 ? "예산 미지정" : formatBudgetLabel(build.budgetWon()));
        return MockData.map(
                "id", "ai-engine-current-" + build.tier().id() + "-" + appliedSuffix(build.appliedPartCategories()),
                "tier", build.tier().id(),
                "label", build.tier().label(),
                "title", build.tier().title() + " 추천 조합",
                "summary", "AI 엔진 추천 부품을 기존 추천 조합에 반영했습니다.",
                "totalPrice", totalPrice,
                "badges", badges(build.tier().title(), Map.of()),
                "budgetWon", toolBudget,
                "budgetLabel", budgetLabel,
                "tierLabel", build.tier().title(),
                "appliedPartCategories", build.appliedPartCategories(),
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(warnings),
                "confidence", confidence(toolResults, warnings)
        );
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

    private static Tier tier(String tierId, int index) {
        if (tierId != null) {
            String normalized = tierId.toLowerCase(Locale.ROOT);
            for (Tier tier : TIERS) {
                if (tier.id().equals(normalized)) {
                    return tier;
                }
            }
        }
        return TIERS.get(Math.max(0, Math.min(index, TIERS.size() - 1)));
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

    private static String appliedSuffix(List<String> appliedPartCategories) {
        if (appliedPartCategories == null || appliedPartCategories.isEmpty()) {
            return "base";
        }
        return String.join("-", appliedPartCategories).toLowerCase(Locale.ROOT);
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

    private static int number(Object value, int fallback) {
        Integer number = numberValue(value);
        return number == null ? fallback : number;
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

    private static String formatScore(Double value) {
        if (value == null) {
            return "-";
        }
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
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

    private record RouteIntent(String route, String label, String message) {
    }

    private record AiBuildCandidate(Tier tier, int budgetWon, String budgetLabel, List<PartCandidate> parts, List<String> appliedPartCategories) {
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
