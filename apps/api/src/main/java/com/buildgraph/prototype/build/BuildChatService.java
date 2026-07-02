package com.buildgraph.prototype.build;

import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolBuildPart;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final Pattern BUDGET_WON = Pattern.compile("(\\d{6,})\\s*원?");
    private static final Pattern QUANTITY_PATTERN = Pattern.compile("(\\d+)\\s*(?:개|장|ea|pcs|개로)");
    private static final Pattern CAPACITY_GB_PATTERN = Pattern.compile("(\\d+)\\s*(?:gb|기가|기가바이트)", Pattern.CASE_INSENSITIVE);
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
    private static final List<String> BLOCKING_FAIL_TOOLS = List.of("compatibility", "power", "size");

    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;
    private final AiChatEngine aiChatEngine;
    private final BuildChatCacheService buildChatCacheService;

    @Autowired
    public BuildChatService(JdbcTemplate jdbcTemplate, ToolCheckService toolCheckService, AiChatEngine aiChatEngine, BuildChatCacheService buildChatCacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCheckService = toolCheckService;
        this.aiChatEngine = aiChatEngine;
        this.buildChatCacheService = buildChatCacheService;
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
        Map<String, Object> body = request == null ? Map.of() : request;
        String message = requireText(body.get("message"), "message는 필수입니다.");
        Long userId = user == null ? null : user.internalId();
        log.debug(
                "Build Chat request received: userId={}, requestedAiProfile={}, cacheLookup=true, cacheService={}",
                userId,
                requestedAiProfile,
                buildChatCacheService.getClass().getName()
        );
        RouteIntent routeIntent = routeIntent(message);
        if (routeIntent != null) {
            return routeResponse(routeIntent);
        }
        var cachedResponse = buildChatCacheService.lookup(body, requestedAiProfile, userId);
        if (cachedResponse.isPresent()) {
            return cachedResponse.get();
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
        Map<String, Object> response = responseMap(engineResponse, body);
        log.debug("Build Chat response generated: userId={}, requestedAiProfile={}, cacheStore=true", userId, requestedAiProfile);
        buildChatCacheService.store(body, requestedAiProfile, userId, response);
        return response;
    }

    static Integer parseBudgetWon(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.replace(",", "").toLowerCase(Locale.ROOT);
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

    static String detectPartCategory(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<CategoryKeywords> checks = List.of(
                new CategoryKeywords("MOTHERBOARD", List.of("메인보드", "마더보드", "보드", "motherboard")),
                new CategoryKeywords("COOLER", List.of("쿨러", "cooler", "수랭", "공랭")),
                new CategoryKeywords("STORAGE", List.of("ssd", "스토리지", "저장장치", "저장 공간", "nvme")),
                new CategoryKeywords("PSU", List.of("파워", "psu", "전원공급", "전원 공급")),
                new CategoryKeywords("CASE", List.of("케이스", "case")),
                new CategoryKeywords("GPU", List.of("gpu", "그래픽카드", "그래픽 카드", "그래픽", "vga", "rtx", "cuda")),
                new CategoryKeywords("CPU", List.of("cpu", "프로세서", "라이젠", "ryzen", "intel", "인텔")),
                new CategoryKeywords("RAM", List.of("ram", "램", "메모리", "memory"))
        );
        return checks.stream()
                .filter(check -> check.keywords().stream().anyMatch(normalized::contains))
                .map(CategoryKeywords::category)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> responseMap(AiChatEngineResponse engineResponse, Map<String, Object> request) {
        List<String> warnings = new ArrayList<>();
        List<AiChatEngineResponse.PartRecommendation> safePartRecommendations = failSafePartRecommendations(engineResponse.partRecommendations(), request, warnings);
        List<Map<String, Object>> builds = switch (engineResponse.intent()) {
            case FULL_BUILD_RECOMMEND -> engineBuilds(engineResponse, warnings);
            case PART_RECOMMEND, BUILD_MODIFY -> changedCurrentBuilds(engineResponse, request, safePartRecommendations, warnings);
            default -> engineBuilds(engineResponse, warnings);
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
            }
        }
        return result;
    }

    private List<Map<String, Object>> engineBuilds(AiChatEngineResponse engineResponse, List<String> warnings) {
        List<AiChatEngineResponse.BuildRecommendation> recommendations = engineResponse.recommendations();
        if (recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < recommendations.size(); index += 1) {
            Map<String, Object> build = engineBuildMap(recommendations.get(index), index, engineResponse, warnings);
            if (hasBlockingToolFailure(objectMaps(build.get("toolResults")))) {
                warnings.add("Tool 검증에서 장착/호환/전력 불가로 판정된 추천 조합을 제외했습니다.");
                continue;
            }
            result.add(build);
        }
        return result;
    }

    private Map<String, Object> engineBuildMap(
            AiChatEngineResponse.BuildRecommendation recommendation,
            int index,
            AiChatEngineResponse engineResponse,
            List<String> warnings
    ) {
        Tier tier = TIERS.get(Math.max(0, Math.min(index, TIERS.size() - 1)));
        List<PartCandidate> parts = recommendation.items().stream()
                .map(this::partCandidate)
                .toList();
        Map<String, Object> parsedContext = engineResponse.parsedContext() == null ? Map.of() : engineResponse.parsedContext();
        int totalPrice = totalPrice(parts, parsedContext);
        Integer userBudget = numberValue(parsedContext.get("budget"));
        int toolBudget = userBudget == null || userBudget <= 0 ? totalPrice : userBudget;
        List<Map<String, Object>> toolResults = toolResults(parts, toolBudget, warnings);
        List<String> buildWarnings = new ArrayList<>(toolWarnings(toolResults));
        if (userBudget != null && totalPrice > userBudget && hasHardConstraint(parsedContext)) {
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
        int limit = multiple ? Math.min(3, recommendations.size()) : 1;
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
            warnings.add("Tool FAIL 후보 " + excluded + "개를 추천/적용 후보에서 제외했습니다.");
        }
        return safe;
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
                .anyMatch(result -> "FAIL".equals(text(result.get("status")))
                        && BLOCKING_FAIL_TOOLS.contains(text(result.get("tool"))));
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
        String suffix = firstText(text(payload.get("partId")), firstText(text(payload.get("category")), label));
        return MockData.map(
                "id", "draft-action-" + type.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + slug(suffix),
                "type", type,
                "label", label,
                "description", description,
                "payload", payload,
                "requiresConfirmation", requiresConfirmation
        );
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

    private boolean isRemoveIntent(String message) {
        return containsAny(message, "빼", "삭제", "제거", "remove", "delete");
    }

    private boolean isQuantityIntent(String message) {
        return containsAny(message, "수량", "개로", "개", "장", "늘려", "줄여");
    }

    private boolean isBudgetIntent(String message) {
        return containsAny(message, "예산", "낮춰", "줄여", "안으로", "이하", "초과", "비싸");
    }

    private RouteIntent routeIntent(String message) {
        String normalized = normalizeCommand(message);
        String category = detectPartCategory(message);
        if (category != null && hasRouteVerb(normalized) && !isProductDetailIntent(normalized)) {
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
        return route.matches("^/self-quote\\?category=(CPU|MOTHERBOARD|RAM|GPU|STORAGE|PSU|CASE|COOLER)$")
                || route.matches("^/parts/[0-9a-fA-F-]{8,}$");
    }

    private static String routeMessage(String route) {
        if (route.startsWith("/self-quote?category=")) {
            String category = route.substring(route.indexOf('=') + 1);
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

    private static boolean hasHardConstraint(Map<String, Object> parsedContext) {
        return "MUST_INCLUDE".equals(text(parsedContext.get("hardConstraintPolicy")))
                || !stringList(parsedContext.get("requiredGpuClasses")).isEmpty()
                || !stringList(parsedContext.get("requiredPartKeywords")).isEmpty();
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

    private record RouteIntent(String route, String label, String message) {
    }

    private record AiBuildCandidate(Tier tier, int budgetWon, String budgetLabel, List<PartCandidate> parts, List<String> appliedPartCategories) {
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
