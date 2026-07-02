package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DefaultAiChatEngine implements AiChatEngine {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern BUDGET_MANWON = Pattern.compile("([0-9]{1,4})\\s*만\\s*원?");
    private static final Pattern BUDGET_NUMBER = Pattern.compile("([0-9][0-9,]{5,})\\s*원?");
    private static final Pattern RTX_CLASS = Pattern.compile("(?i)(?:rtx|geforce|지포스)?\\s*(40[6-9]0|50[6-9]0)");
    private static final int STANDARD_UNSPECIFIED_BUDGET = 3_000_000;
    private static final int PERFORMANCE_UNSPECIFIED_BUDGET = 5_000_000;
    private static final int ENTHUSIAST_OPEN_BUDGET = 12_000_000;
    private static final String REQUIREMENT_PARSE_SCHEMA_NAME = "buildgraph_quote_requirement_profile";
    private static final String BUILD_CHAT_SCHEMA_NAME = "buildgraph_ai_build_chat_plan";
    private static final String CHAT_RAG_ROOT_ID = "00000000-0000-0000-0000-000000000000";
    private static final String REQUIREMENT_PARSE_SYSTEM_PROMPT = """
            당신은 BuildGraph AI의 견적 생성 입력서를 만드는 엔진입니다.
            제공된 사용자 입력, 선택 입력, RAG 근거만 사용하십시오.
            부품명, 가격, 성능 수치는 지어내지 말고, 확실하지 않으면 null 또는 빈 배열을 사용하십시오.
            출력은 서버가 제공한 JSON schema를 반드시 따릅니다.
            """;
    private static final String BUILD_CHAT_SYSTEM_PROMPT = """
            당신은 BuildGraph 쇼핑몰 챗봇의 의도 분석 엔진입니다.
            사용자 메시지, 현재 화면 context, RAG 근거만 보고 intent와 추천 조건을 구조화하십시오.
            부품 ID, 실제 가격, FPS 수치, 상품명은 지어내지 마십시오. 실제 부품 선택은 서버 DB가 수행합니다.
            RTX 5090처럼 사용자가 명시한 부품/클래스는 requiredGpuClasses와 hardConstraintPolicy에 반드시 보존하십시오.
            셀프 견적 변경 요청은 draftEdit에 교체 대상 category, operation, priceDirection, targetMaxPrice를 구조화하십시오.
            예: “그래픽카드가 너무 비싸니 싼 걸로”는 category=GPU, operation=REPLACE, priceDirection=CHEAPER입니다.
            예산이 없으면 budget은 null입니다. 일반 성능 목표는 budgetPolicy=UNSPECIFIED이고, 예산 없는 최고급/끝판왕/명시 5090 의도만 OPEN_BUDGET입니다.
            출력은 서버가 제공한 JSON schema를 반드시 따릅니다.
            """;
    private static final List<String> BUILD_CATEGORIES = List.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );

    private final JdbcTemplate jdbcTemplate;
    private final AgentTraceService agentTraceService;
    private final AgentRagRetrievalService agentRagRetrievalService;
    private final OpenAiResponsesClient openAiResponsesClient;
    private final AiProfileConfig aiProfileConfig;
    private final PartReplacementRanker partReplacementRanker;

    public DefaultAiChatEngine(
            JdbcTemplate jdbcTemplate,
            AgentTraceService agentTraceService,
            AgentRagRetrievalService agentRagRetrievalService,
            OpenAiResponsesClient openAiResponsesClient,
            AiProfileConfig aiProfileConfig,
            PartReplacementRanker partReplacementRanker
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.agentTraceService = agentTraceService;
        this.agentRagRetrievalService = agentRagRetrievalService;
        this.openAiResponsesClient = openAiResponsesClient;
        this.aiProfileConfig = aiProfileConfig;
        this.partReplacementRanker = partReplacementRanker;
    }

    @Override
    public AiChatEngineResponse respond(AiChatEngineRequest request) {
        String message = requireText(request == null ? null : request.message(), "챗봇 메시지가 필요합니다.");
        Map<String, Object> context = request == null || request.context() == null ? Map.of() : request.context();
        AiChatIntent intent = classify(message, request == null ? null : request.selectedCategory(), context);
        Map<String, Object> parsedContext = deterministicParsedContext(context, message);

        return switch (intent) {
            case FULL_BUILD_RECOMMEND -> fullBuildResponse(message, parsedContext);
            case PART_RECOMMEND -> partRecommendResponse(message, request == null ? null : request.selectedCategory());
            case BUILD_MODIFY -> buildModifyResponse(message, request == null ? null : request.selectedCategory(), context, Map.of());
            case PRICE_ALERT_HELP -> priceAlertResponse(message, request == null ? null : request.selectedCategory());
            case EXPLAIN -> explainResponse(message);
            case ASK_FOLLOW_UP -> askFollowUpResponse(message);
        };
    }

    @Override
    public AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request) {
        return respondLlmRequired(request, null);
    }

    @Override
    public AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request, String requestedAiProfile) {
        String message = requireText(request == null ? null : request.message(), "챗봇 메시지가 필요합니다.");
        if (!openAiResponsesClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다.");
        }
        AiProfileDefinition buildProfile = requireBuildChatProfile(requestedAiProfile);
        Map<String, Object> context = request == null || request.context() == null ? Map.of() : request.context();
        Map<String, Object> fallbackContext = deterministicParsedContext(context, message);
        AgentRunProfile profile = AgentRunProfiles.requirementParse();
        List<AgentRagEvidenceDraft> evidenceSet = agentRagRetrievalService.retrieveEvidenceSet(
                new AgentSessionRoot(AgentSessionRootType.REQUIREMENT, CHAT_RAG_ROOT_ID),
                profile,
                message,
                buildProfile.ragTopK()
        );
        List<String> evidenceIds = evidenceSet.stream()
                .map(DefaultAiChatEngine::sourceEvidenceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, Object> plan;
        try {
            plan = llmBuildChatPlan(message, request, context, fallbackContext, evidenceIds, evidenceSet, buildProfile);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 응답 JSON을 처리할 수 없습니다.", error);
        }

        AiChatIntent fallbackIntent = classify(message, request == null ? null : request.selectedCategory(), context);
        AiChatIntent intent = normalizeIntent(text(plan.get("intent")), fallbackIntent);
        Map<String, Object> draftEdit = normalizeDraftEdit(objectMap(plan.get("draftEdit")), message, request == null ? null : request.selectedCategory(), context);
        if (fallbackIntent == AiChatIntent.BUILD_MODIFY && !"NONE".equals(text(draftEdit.get("operation")))) {
            intent = AiChatIntent.BUILD_MODIFY;
        }
        String selectedCategory = firstText(categoryFrom(text(draftEdit.get("category"))), firstText(categoryFrom(text(plan.get("selectedCategory"))), request == null ? null : request.selectedCategory()));
        Map<String, Object> parsedContext = normalizeParsedContext(objectMap(plan.get("parsedContext")), fallbackContext);
        if (!draftEdit.isEmpty()) {
            parsedContext.put("draftEdit", draftEdit);
        }
        String assistantMessage = firstText(text(plan.get("assistantMessage")), null);
        AiChatEngineResponse base = switch (intent) {
            case FULL_BUILD_RECOMMEND -> fullBuildResponse(message, parsedContext);
            case PART_RECOMMEND -> partRecommendResponse(message, selectedCategory);
            case BUILD_MODIFY -> buildModifyResponse(message, selectedCategory, context, draftEdit);
            case PRICE_ALERT_HELP -> priceAlertResponse(message, selectedCategory);
            case EXPLAIN -> explainResponse(message);
            case ASK_FOLLOW_UP -> askFollowUpResponse(message);
        };
        return withLlmMetadata(base, assistantMessage, parsedContext, evidenceIds);
    }

    @Override
    public QuoteRequirementAnalysisResult analyzeQuoteRequirement(QuoteRequirementAnalysisRequest request) {
        String requirementId = requireText(request == null ? null : request.requirementId(), "requirementId가 필요합니다.");
        String message = requireText(request.message(), "자연어 요구사항은 필수입니다.");
        Map<String, Object> optionalInputs = request.optionalInputs() == null ? Map.of() : request.optionalInputs();
        Map<String, Object> fallbackContext = normalizeParsedContext(
                request.fallbackContext() == null ? Map.of() : request.fallbackContext(),
                deterministicParsedContext(optionalInputs, message)
        );

        AgentSessionRoot root = new AgentSessionRoot(AgentSessionRootType.REQUIREMENT, requirementId);
        AgentRunProfile profile = AgentRunProfiles.requirementParse();
        Long userInternalId = longValue(optionalInputs.get("_userInternalId"));
        String sessionId = null;
        try {
            sessionId = agentTraceService.createQueuedSession(root, "SYSTEM", profile.purpose(), userInternalId);
            String activeSessionId = sessionId;
            agentTraceService.advanceStatus(sessionId, AgentStatus.RUNNING, "SYSTEM", "quote requirement AI engine requested");
            List<AgentRagEvidenceDraft> evidenceSet = agentRagRetrievalService.retrieveEvidenceSet(root, profile);
            List<String> evidenceIds = evidenceSet.stream()
                    .map(evidence -> agentTraceService.recordRagEvidence(activeSessionId, evidence))
                    .toList();
            agentTraceService.advanceStatus(sessionId, AgentStatus.RAG_SEARCHED, "SYSTEM", "quote requirement RAG evidence retrieved");

            if (openAiResponsesClient.isConfigured()) {
                try {
                    Map<String, Object> llmContext = llmParsedContext(message, optionalInputs, fallbackContext, evidenceIds, evidenceSet);
                    Map<String, Object> parsedContext = withAgentParseMetadata(
                            normalizeParsedContext(llmContext, fallbackContext),
                            "AI_CHAT_ENGINE_LLM",
                            sessionId,
                            evidenceIds,
                            evidenceSet,
                            text(llmContext.get("parseNotes")),
                            null
                    );
                    String summary = firstText(text(parsedContext.get("parseNotes")), "AI chat engine generated a quote requirement profile.");
                    agentTraceService.advanceStatus(sessionId, AgentStatus.TOOLS_CALLED, "SYSTEM", "quote requirement parsing does not run hardware tools");
                    agentTraceService.updateSummary(sessionId, summary);
                    agentTraceService.advanceStatus(sessionId, AgentStatus.SUMMARY_READY, "SYSTEM", "quote requirement profile generated");
                    agentTraceService.advanceStatus(sessionId, AgentStatus.SUCCEEDED, "SYSTEM", "quote requirement AI engine completed");
                    return new QuoteRequirementAnalysisResult(parsedContext, sessionId, summary, evidenceIds);
                } catch (RuntimeException llmError) {
                    Map<String, Object> parsedContext = withAgentParseMetadata(
                            fallbackContext,
                            "AI_CHAT_ENGINE_FALLBACK",
                            sessionId,
                            evidenceIds,
                            evidenceSet,
                            "LLM structured parse failed; deterministic quote profile was used.",
                            safeReason(llmError)
                    );
                    String summary = "RAG evidence retrieved, but LLM structured parse failed. Deterministic quote profile was used.";
                    agentTraceService.updateSummary(sessionId, summary);
                    agentTraceService.advanceStatus(sessionId, AgentStatus.FALLBACK_READY, "SYSTEM", "quote requirement LLM failed");
                    agentTraceService.advanceStatus(sessionId, AgentStatus.SUCCEEDED, "SYSTEM", "quote requirement fallback completed");
                    return new QuoteRequirementAnalysisResult(parsedContext, sessionId, summary, evidenceIds);
                }
            }

            Map<String, Object> parsedContext = withAgentParseMetadata(
                    fallbackContext,
                    "AI_CHAT_ENGINE_DETERMINISTIC",
                    sessionId,
                    evidenceIds,
                    evidenceSet,
                    "RAG evidence retrieved; deterministic quote profile was used because OpenAI is not configured.",
                    null
            );
            String summary = "RAG evidence retrieved; deterministic quote profile generated.";
            agentTraceService.advanceStatus(sessionId, AgentStatus.TOOLS_CALLED, "SYSTEM", "quote requirement parsing does not run hardware tools");
            agentTraceService.updateSummary(sessionId, summary);
            agentTraceService.advanceStatus(sessionId, AgentStatus.SUMMARY_READY, "SYSTEM", "quote requirement profile generated");
            agentTraceService.advanceStatus(sessionId, AgentStatus.SUCCEEDED, "SYSTEM", "quote requirement AI engine completed");
            return new QuoteRequirementAnalysisResult(parsedContext, sessionId, summary, evidenceIds);
        } catch (RuntimeException error) {
            Map<String, Object> parsedContext = withAgentParseMetadata(
                    fallbackContext,
                    "AI_CHAT_ENGINE_DETERMINISTIC_FALLBACK",
                    sessionId,
                    List.of(),
                    List.of(),
                    "Quote AI engine failed before RAG evidence could be attached; deterministic quote profile was used.",
                    safeReason(error)
            );
            return new QuoteRequirementAnalysisResult(parsedContext, sessionId, null, List.of());
        }
    }

    private AiChatEngineResponse fullBuildResponse(String message, Map<String, Object> parsedContext) {
        List<AiChatEngineResponse.BuildRecommendation> recommendations = buildRecommendations(message, parsedContext);
        List<AiChatAction> actions = new ArrayList<>();
        actions.add(new AiChatAction(AiChatActionType.OPEN_SELF_QUOTE, "셀프 견적으로 보기", Map.of("route", "/self-quote")));
        if (!recommendations.isEmpty()) {
            actions.add(new AiChatAction(
                    AiChatActionType.ADD_BUILD_TO_DRAFT,
                    "추천 조합 담기",
                    MockData.map(
                            "source", "AI_CHAT_ENGINE",
                            "items", recommendations.get(0).items().stream()
                                    .map(part -> MockData.map("partId", part.partId(), "category", part.category(), "quantity", defaultQuantity(part.category())))
                                    .toList()
                    )
            ));
        }
        return response(
                "요청하신 조건으로 추천 PC 3개를 준비했습니다. 원하는 조합은 셀프 견적에서 그대로 담아 비교할 수 있습니다.",
                AiChatIntent.FULL_BUILD_RECOMMEND,
                actions,
                recommendations,
                List.of(),
                parsedContext
        );
    }

    private AiChatEngineResponse partRecommendResponse(String message, String selectedCategory) {
        String category = categoryFrom(firstText(selectedCategory, message));
        if (category == null) {
            return askFollowUpResponse(message);
        }
        List<AiChatEngineResponse.PartRecommendation> parts = partRecommendations(category, 3);
        List<AiChatAction> actions = parts.stream()
                .map(part -> new AiChatAction(
                        AiChatActionType.ADD_PART_TO_DRAFT,
                        part.name() + " 담기",
                        MockData.map("partId", part.partId(), "category", part.category(), "quantity", defaultQuantity(part.category()))
                ))
                .toList();
        return response(
                categoryLabel(category) + " 후보를 내부 자산 기준으로 골랐습니다. 담기 버튼은 기존 셀프 견적 장바구니 API로 연결하면 됩니다.",
                AiChatIntent.PART_RECOMMEND,
                actions,
                List.of(),
                parts,
                MockData.map("category", category)
        );
    }

    private AiChatEngineResponse buildModifyResponse(String message) {
        return buildModifyResponse(message, null, Map.of(), Map.of());
    }

    private AiChatEngineResponse buildModifyResponse(String message, String selectedCategory) {
        return buildModifyResponse(message, selectedCategory, Map.of(), Map.of());
    }

    private AiChatEngineResponse buildModifyResponse(String message, String selectedCategory, Map<String, Object> context, Map<String, Object> draftEdit) {
        Map<String, Object> normalizedDraftEdit = normalizeDraftEdit(draftEdit, message, selectedCategory, context);
        String category = categoryFrom(firstText(selectedCategory, message));
        String effectiveCategory = firstText(categoryFrom(text(normalizedDraftEdit.get("category"))), category);
        if (effectiveCategory == null) {
            effectiveCategory = categoryFrom(text(mostExpensiveDraftItem(context).get("category")));
        }
        if (effectiveCategory == null) {
            effectiveCategory = "RAM";
        }
        Map<String, Object> currentItem = currentDraftItem(context, effectiveCategory);
        String priceDirection = normalizePriceDirection(text(normalizedDraftEdit.get("priceDirection")));
        Integer targetMaxPrice = numberValue(normalizedDraftEdit.get("targetMaxPrice"));
        PartReplacementRanker.SelectionResult selection = draftEditPartRecommendations(
                effectiveCategory,
                currentItem,
                context,
                priceDirection,
                targetMaxPrice,
                3
        );
        List<AiChatEngineResponse.PartRecommendation> candidates = selection.parts();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", effectiveCategory);
        payload.put("quantity", defaultQuantity(effectiveCategory));
        payload.put("source", "AI_CHAT_ENGINE");
        if (currentItem.get("partId") != null) {
            payload.put("currentPartId", text(currentItem.get("partId")));
        }
        payload.put("priceDirection", priceDirection);
        if (!candidates.isEmpty()) {
            AiChatEngineResponse.PartRecommendation firstCandidate = candidates.get(0);
            payload.put("partId", firstCandidate.partId());
            payload.put("name", firstCandidate.name());
            payload.put("price", firstCandidate.price());
        }
        Map<String, Object> parsedContext = MockData.map("category", effectiveCategory, "draftEdit", normalizedDraftEdit);
        if (!selection.warnings().isEmpty()) {
            parsedContext.put("warnings", selection.warnings());
        }
        return response(
                buildModifyMessage(effectiveCategory, priceDirection, currentItem, candidates),
                AiChatIntent.BUILD_MODIFY,
                List.of(new AiChatAction(AiChatActionType.REPLACE_DRAFT_PART, "견적 부품 교체", payload)),
                List.of(),
                candidates,
                parsedContext
        );
    }

    private AiChatEngineResponse priceAlertResponse(String message, String selectedCategory) {
        String category = categoryFrom(firstText(selectedCategory, message));
        Integer targetPrice = inferBudget(message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", category);
        payload.put("targetPrice", targetPrice);
        payload.put("source", "AI_CHAT_ENGINE");
        return response(
                targetPrice == null
                        ? "목표가 알림을 만들려면 원하는 가격을 함께 알려주세요."
                        : "목표가 알림에 필요한 가격 조건을 추출했습니다.",
                targetPrice == null ? AiChatIntent.ASK_FOLLOW_UP : AiChatIntent.PRICE_ALERT_HELP,
                List.of(new AiChatAction(targetPrice == null ? AiChatActionType.ASK_FOLLOW_UP : AiChatActionType.CREATE_PRICE_ALERT, "목표가 알림 설정", payload)),
                List.of(),
                category == null ? List.of() : partRecommendations(category, 3),
                MockData.map("category", category, "targetPrice", targetPrice)
        );
    }

    private AiChatEngineResponse explainResponse(String message) {
        return response(
                "추천 근거는 예산, 내부 자산 현재가, 주요 부품 스펙, RAG 근거, Tool 검증 결과를 기준으로 설명합니다.",
                AiChatIntent.EXPLAIN,
                List.of(new AiChatAction(AiChatActionType.OPEN_SELF_QUOTE, "견적에서 근거 보기", Map.of("route", "/self-quote"))),
                List.of(),
                List.of(),
                MockData.map("question", message)
        );
    }

    private AiChatEngineResponse askFollowUpResponse(String message) {
        return response(
                "추천하려면 용도나 부품 종류가 더 필요합니다. 예: QHD 게임용 PC, RTX 5070 GPU 추천, RAM 64GB로 변경처럼 입력해 주세요.",
                AiChatIntent.ASK_FOLLOW_UP,
                List.of(new AiChatAction(AiChatActionType.ASK_FOLLOW_UP, "추가 질문", MockData.map("missing", List.of("usageOrCategory"), "message", message))),
                List.of(),
                List.of(),
                MockData.map("rawMessage", message)
        );
    }

    private AiChatEngineResponse response(
            String assistantMessage,
            AiChatIntent intent,
            List<AiChatAction> actions,
            List<AiChatEngineResponse.BuildRecommendation> recommendations,
            List<AiChatEngineResponse.PartRecommendation> partRecommendations,
            Map<String, Object> parsedContext
    ) {
        return new AiChatEngineResponse(
                assistantMessage,
                intent,
                actions,
                recommendations,
                partRecommendations,
                parsedContext,
                List.of(),
                List.of(),
                null
        );
    }

    private AiChatEngineResponse withLlmMetadata(
            AiChatEngineResponse response,
            String assistantMessage,
            Map<String, Object> parsedContext,
            List<String> evidenceIds
    ) {
        return new AiChatEngineResponse(
                firstText(assistantMessage, response.assistantMessage()),
                response.intent(),
                response.actions(),
                response.recommendations(),
                response.partRecommendations(),
                parsedContext == null ? response.parsedContext() : parsedContext,
                evidenceIds == null ? List.of() : evidenceIds,
                response.toolResults(),
                response.agentSessionId()
        );
    }

    private List<AiChatEngineResponse.BuildRecommendation> buildRecommendations(String message, Map<String, Object> parsedContext) {
        Integer budget = numberValue(parsedContext.get("budget"));
        int effectiveBudget = budget == null ? inferredBudgetFor(parsedContext) : budget;
        List<BuildPreviewPlan> plans = previewPlansFor(message);
        return plans.stream()
                .map(plan -> {
                    List<AiChatEngineResponse.PartRecommendation> items = BUILD_CATEGORIES.stream()
                            .map(category -> choosePart(category, Math.max(50_000, (int) (effectiveBudget * plan.budgetRatio() * categoryWeight(category))), parsedContext))
                            .filter(part -> part != null)
                            .toList();
                    int total = items.stream()
                            .mapToInt(item -> item.price() * defaultQuantity(item.category()))
                            .sum();
                    return new AiChatEngineResponse.BuildRecommendation(
                            plan.name(),
                            plan.recommendedFor(),
                            "내부 자산 후보를 기반으로 만든 챗봇 추천 초안입니다.",
                            total,
                            plan.confidence(),
                            items
                    );
                })
                .toList();
    }

    private static List<BuildPreviewPlan> previewPlansFor(String message) {
        if (isMinimumBudgetMessage(message)) {
            return List.of(
                    new BuildPreviewPlan("기준 이상 추천 조합", "요청 금액 이상에서 성능 기준을 맞춤", 1.35, "HIGH"),
                    new BuildPreviewPlan("상위 균형 추천 조합", "요청 금액보다 여유 있게 성능과 안정성을 확보", 1.50, "HIGH"),
                    new BuildPreviewPlan("프리미엄 확장 추천 조합", "요청 금액 이상에서 업그레이드 여유까지 확보", 1.70, "MEDIUM")
            );
        }
        return List.of(
                new BuildPreviewPlan("가성비형 추천 조합", "예산 안에서 핵심 성능 우선", 0.78, "MEDIUM"),
                new BuildPreviewPlan("균형형 추천 조합", "게임, 작업, 안정성 균형", 0.96, "HIGH"),
                new BuildPreviewPlan("고성능형 추천 조합", "성능과 업그레이드 여유 우선", 1.14, "MEDIUM")
        );
    }

    private static boolean isMinimumBudgetMessage(String message) {
        String normalized = text(message).toLowerCase(Locale.ROOT);
        return normalized.contains("이상")
                || normalized.contains("최소")
                || normalized.contains("넘게")
                || normalized.contains("넘는")
                || normalized.contains("보다 높")
                || normalized.contains("보다 비싸")
                || normalized.contains("부터")
                || normalized.contains("이하 말고")
                || normalized.contains("아래 말고");
    }

    private AiChatEngineResponse.PartRecommendation choosePart(String category, int targetPrice) {
        return choosePart(category, targetPrice, Map.of());
    }

    private AiChatEngineResponse.PartRecommendation choosePart(String category, int targetPrice, Map<String, Object> parsedContext) {
        List<AiChatEngineResponse.PartRecommendation> parts = partRecommendations(category, 50);
        if ("GPU".equals(category)) {
            List<String> requiredGpuClasses = normalizeGpuClasses(stringList(parsedContext.get("requiredGpuClasses")));
            if (!requiredGpuClasses.isEmpty()) {
                List<AiChatEngineResponse.PartRecommendation> matching = parts.stream()
                        .filter(part -> requiredGpuClasses.contains(gpuClass(part)))
                        .toList();
                if (!matching.isEmpty()) {
                    return matching.stream()
                            .min((left, right) -> Integer.compare(Math.abs(left.price() - targetPrice), Math.abs(right.price() - targetPrice)))
                            .orElse(matching.get(0));
                }
            }
        }
        return parts.stream()
                .min((left, right) -> Integer.compare(Math.abs(left.price() - targetPrice), Math.abs(right.price() - targetPrice)))
                .orElse(null);
    }

    private List<AiChatEngineResponse.PartRecommendation> partRecommendations(String category, int limit) {
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
                          ORDER BY bs.created_at DESC, bs.id DESC
                          LIMIT 1
                        ) b ON true
                        WHERE p.category = ?
                          AND p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                          AND coalesce((p.attributes->>'toolReady')::boolean, false) = true
                        ORDER BY p.price DESC, p.id ASC
                        LIMIT ?
                        """, category, Math.max(1, Math.min(limit, 50)))
                .stream()
                .map(row -> {
                    Map<String, Object> attributes = new LinkedHashMap<>(objectMap(DbValueMapper.json(row, "attributes", Map.of())));
                    Object benchmarkScore = row.get("benchmark_score");
                    if (benchmarkScore != null) {
                        attributes.put("_benchmarkScore", benchmarkScore);
                    }
                    Object benchmarkSummary = row.get("benchmark_summary");
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
                })
                .toList();
    }

    private PartReplacementRanker.SelectionResult draftEditPartRecommendations(
            String category,
            Map<String, Object> currentItem,
            Map<String, Object> context,
            String priceDirection,
            Integer targetMaxPrice,
            int limit
    ) {
        List<AiChatEngineResponse.PartRecommendation> parts = compatibleReplacementParts(category, context, partRecommendations(category, 50));
        return partReplacementRanker.select(category, currentItem, priceDirection, targetMaxPrice, parts, limit);
    }

    private static List<AiChatEngineResponse.PartRecommendation> compatibleReplacementParts(
            String category,
            Map<String, Object> context,
            List<AiChatEngineResponse.PartRecommendation> parts
    ) {
        if (parts.isEmpty()) {
            return parts;
        }
        List<AiChatEngineResponse.PartRecommendation> filtered = switch (category) {
            case "CPU" -> filterByCpuSocket(context, parts);
            case "MOTHERBOARD" -> filterByMotherboardPlatform(context, parts);
            case "RAM" -> filterByMemoryType(context, parts);
            case "GPU" -> filterByGpuFit(context, parts);
            case "PSU" -> filterByPsuCapacity(context, parts);
            case "CASE" -> filterByCaseFit(context, parts);
            case "COOLER" -> filterByCoolerFit(context, parts);
            default -> parts;
        };
        return filtered;
    }

    private static List<AiChatEngineResponse.PartRecommendation> filterByCpuSocket(
            Map<String, Object> context,
            List<AiChatEngineResponse.PartRecommendation> parts
    ) {
        String motherboardSocket = attrText(draftItem(context, "MOTHERBOARD"), "socket");
        if (motherboardSocket == null) {
            return parts;
        }
        return parts.stream()
                .filter(part -> sameRequired(attrText(part.attributes(), "socket"), motherboardSocket))
                .toList();
    }

    private static List<AiChatEngineResponse.PartRecommendation> filterByMotherboardPlatform(
            Map<String, Object> context,
            List<AiChatEngineResponse.PartRecommendation> parts
    ) {
        String cpuSocket = attrText(draftItem(context, "CPU"), "socket");
        String memoryType = attrText(draftItem(context, "RAM"), "memoryType");
        return parts.stream()
                .filter(part -> sameRequired(attrText(part.attributes(), "socket"), cpuSocket))
                .filter(part -> sameRequired(attrText(part.attributes(), "memoryType"), memoryType))
                .toList();
    }

    private static List<AiChatEngineResponse.PartRecommendation> filterByMemoryType(
            Map<String, Object> context,
            List<AiChatEngineResponse.PartRecommendation> parts
    ) {
        String motherboardMemoryType = attrText(draftItem(context, "MOTHERBOARD"), "memoryType");
        if (motherboardMemoryType == null) {
            return parts;
        }
        return parts.stream()
                .filter(part -> sameRequired(attrText(part.attributes(), "memoryType"), motherboardMemoryType))
                .toList();
    }

    private static List<AiChatEngineResponse.PartRecommendation> filterByGpuFit(
            Map<String, Object> context,
            List<AiChatEngineResponse.PartRecommendation> parts
    ) {
        Map<String, Object> currentCase = draftItem(context, "CASE");
        Map<String, Object> currentPsu = draftItem(context, "PSU");
        Integer maxGpuLengthMm = attrNumber(currentCase, "maxGpuLengthMm");
        Integer psuCapacityW = firstPositiveNumber(attrNumber(currentPsu, "capacityW"), attrNumber(currentPsu, "wattage"));
        return parts.stream()
                .filter(part -> lessOrEqualRequired(attrNumber(part.attributes(), "lengthMm"), maxGpuLengthMm))
                .filter(part -> lessOrEqualRequired(attrNumber(part.attributes(), "requiredSystemPowerW"), psuCapacityW))
                .toList();
    }

    private static List<AiChatEngineResponse.PartRecommendation> filterByPsuCapacity(
            Map<String, Object> context,
            List<AiChatEngineResponse.PartRecommendation> parts
    ) {
        Integer requiredPsuW = attrNumber(draftItem(context, "GPU"), "requiredSystemPowerW");
        if (requiredPsuW == null) {
            return parts;
        }
        return parts.stream()
                .filter(part -> greaterOrEqualRequired(firstPositiveNumber(attrNumber(part.attributes(), "capacityW"), attrNumber(part.attributes(), "wattage")), requiredPsuW))
                .toList();
    }

    private static List<AiChatEngineResponse.PartRecommendation> filterByCaseFit(
            Map<String, Object> context,
            List<AiChatEngineResponse.PartRecommendation> parts
    ) {
        Integer gpuLengthMm = attrNumber(draftItem(context, "GPU"), "lengthMm");
        Integer coolerHeightMm = firstPositiveNumber(attrNumber(draftItem(context, "COOLER"), "heightMm"), attrNumber(draftItem(context, "COOLER"), "coolerHeightMm"));
        Integer psuDepthMm = attrNumber(draftItem(context, "PSU"), "depthMm");
        return parts.stream()
                .filter(part -> greaterOrEqualRequired(attrNumber(part.attributes(), "maxGpuLengthMm"), gpuLengthMm))
                .filter(part -> greaterOrEqualRequired(attrNumber(part.attributes(), "maxCpuCoolerHeightMm"), coolerHeightMm))
                .filter(part -> greaterOrEqualRequired(attrNumber(part.attributes(), "maxPsuLengthMm"), psuDepthMm))
                .toList();
    }

    private static List<AiChatEngineResponse.PartRecommendation> filterByCoolerFit(
            Map<String, Object> context,
            List<AiChatEngineResponse.PartRecommendation> parts
    ) {
        String cpuSocket = attrText(draftItem(context, "CPU"), "socket");
        Integer maxCoolerHeightMm = attrNumber(draftItem(context, "CASE"), "maxCpuCoolerHeightMm");
        return parts.stream()
                .filter(part -> socketSupportedRequired(part.attributes().get("socketSupport"), cpuSocket))
                .filter(part -> lessOrEqualRequired(firstPositiveNumber(attrNumber(part.attributes(), "heightMm"), attrNumber(part.attributes(), "coolerHeightMm")), maxCoolerHeightMm))
                .toList();
    }

    private static String buildModifyMessage(
            String category,
            String priceDirection,
            Map<String, Object> currentItem,
            List<AiChatEngineResponse.PartRecommendation> candidates
    ) {
        if (candidates.isEmpty()) {
            return categoryLabel(category) + " 교체 후보를 찾지 못했습니다. 예산이나 성능 조건을 조금 더 구체적으로 알려주세요.";
        }
        String currentName = text(currentItem.get("name"));
        String directionText = switch (priceDirection) {
            case "CHEAPER" -> "더 저렴한";
            case "MORE_EXPENSIVE" -> "상위 성능";
            case "SIMILAR_PRICE" -> "비슷한 가격대";
            default -> "교체 가능한";
        };
        return currentName == null
                ? categoryLabel(category) + "에서 " + directionText + " 후보를 찾았습니다. 적용 버튼을 누르면 견적 장바구니에 반영됩니다."
                : currentName + " 대신 선택할 수 있는 " + directionText + " " + categoryLabel(category) + " 후보를 찾았습니다.";
    }

    private Map<String, Object> llmParsedContext(
            String message,
            Map<String, Object> request,
            Map<String, Object> fallbackContext,
            List<String> evidenceIds,
            List<AgentRagEvidenceDraft> evidenceSet
    ) {
        String output = openAiResponsesClient.createStructuredJson(
                REQUIREMENT_PARSE_SYSTEM_PROMPT,
                json(MockData.map(
                        "rawMessage", message,
                        "optionalInputs", request,
                        "fallbackNormalizer", fallbackContext,
                        "ragEvidenceSet", evidenceItems(evidenceIds, evidenceSet)
                )),
                REQUIREMENT_PARSE_SCHEMA_NAME,
                requirementParseSchema()
        );
        return parseJsonObject(output);
    }

    private Map<String, Object> llmBuildChatPlan(
            String message,
            AiChatEngineRequest request,
            Map<String, Object> context,
            Map<String, Object> fallbackContext,
            List<String> evidenceIds,
            List<AgentRagEvidenceDraft> evidenceSet,
            AiProfileDefinition buildProfile
    ) {
        LlmResponseResult result = openAiResponsesClient.createStructuredJsonResult(
                BUILD_CHAT_SYSTEM_PROMPT,
                json(MockData.map(
                        "aiProfile", buildProfile.profile().name(),
                        "promptVersion", buildProfile.promptVersion(),
                        "responseLimits", MockData.map(
                                "assistantMessage", "Korean, concise",
                                "recommendationCountMax", 3,
                                "partRecommendationCountMax", 3,
                                "actionCountMax", 3
                        ),
                        "rawMessage", message,
                        "surface", request == null ? null : request.surface(),
                        "selectedCategory", request == null ? null : request.selectedCategory(),
                        "buildId", request == null ? null : request.buildId(),
                        "draftId", request == null ? null : request.draftId(),
                        "context", context,
                        "fallbackNormalizer", fallbackContext,
                        "ragEvidenceSet", evidenceItems(evidenceIds, evidenceSet)
                )),
                BUILD_CHAT_SCHEMA_NAME,
                buildChatPlanSchema(),
                buildProfile.model(),
                buildProfile.reasoningEffort(),
                buildProfile.maxOutputTokens()
        );
        return parseJsonObject(result.text());
    }

    private static Map<String, Object> buildChatPlanSchema() {
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "properties", MockData.map(
                        "intent", MockData.map("type", "string", "enum", List.of(
                                "FULL_BUILD_RECOMMEND",
                                "PART_RECOMMEND",
                                "BUILD_MODIFY",
                                "PRICE_ALERT_HELP",
                                "EXPLAIN",
                                "ASK_FOLLOW_UP"
                        )),
                        "assistantMessage", MockData.map("type", "string"),
                        "selectedCategory", MockData.map("type", List.of("string", "null"), "enum", Arrays.asList(
                                "CPU",
                                "MOTHERBOARD",
                                "RAM",
                                "GPU",
                                "STORAGE",
                                "PSU",
                                "CASE",
                                "COOLER",
                                null
                        )),
                        "parsedContext", requirementParseSchema(),
                        "draftEdit", draftEditSchema()
                ),
                "required", List.of("intent", "assistantMessage", "selectedCategory", "parsedContext", "draftEdit")
        );
    }

    private static Map<String, Object> draftEditSchema() {
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "properties", MockData.map(
                        "operation", MockData.map("type", "string", "enum", List.of("ADD", "REPLACE", "REMOVE", "UPDATE_QUANTITY", "ASK_FOLLOW_UP", "NONE")),
                        "category", MockData.map("type", List.of("string", "null"), "enum", Arrays.asList(
                                "CPU",
                                "MOTHERBOARD",
                                "RAM",
                                "GPU",
                                "STORAGE",
                                "PSU",
                                "CASE",
                                "COOLER",
                                null
                        )),
                        "priceDirection", MockData.map("type", "string", "enum", List.of("CHEAPER", "MORE_EXPENSIVE", "SIMILAR_PRICE", "ANY")),
                        "targetMaxPrice", MockData.map("type", List.of("integer", "null")),
                        "targetQuantity", MockData.map("type", List.of("integer", "null")),
                        "reason", MockData.map("type", List.of("string", "null"))
                ),
                "required", List.of("operation", "category", "priceDirection", "targetMaxPrice", "targetQuantity", "reason")
        );
    }

    private static Map<String, Object> requirementParseSchema() {
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "properties", MockData.map(
                        "budget", MockData.map("type", List.of("integer", "null")),
                        "usageTags", MockData.map("type", "array", "items", MockData.map("type", "string", "enum", List.of("GAMING", "DEVELOPMENT", "VIDEO_EDIT", "AI_DEV", "GENERAL"))),
                        "resolution", MockData.map("type", List.of("string", "null"), "enum", Arrays.asList("FHD", "QHD", "4K", null)),
                        "preferredVendors", MockData.map("type", "array", "items", MockData.map("type", "string", "enum", List.of("NVIDIA", "AMD", "INTEL"))),
                        "priority", MockData.map("type", List.of("string", "null")),
                        "performanceTier", MockData.map("type", "string", "enum", List.of("ENTHUSIAST", "PERFORMANCE", "STANDARD")),
                        "budgetPolicy", MockData.map("type", "string", "enum", List.of("USER_BUDGET", "OPEN_BUDGET", "UNSPECIFIED")),
                        "mustHave", MockData.map("type", "array", "items", MockData.map("type", "string", "enum", List.of("WIFI", "LOW_NOISE"))),
                        "requiredGpuClasses", MockData.map("type", "array", "items", MockData.map("type", "string")),
                        "requiredPartKeywords", MockData.map("type", "array", "items", MockData.map("type", "string")),
                        "hardConstraintPolicy", MockData.map("type", "string", "enum", List.of("MUST_INCLUDE", "NONE")),
                        "confidence", confidenceSchema(),
                        "parseNotes", MockData.map("type", List.of("string", "null"))
                ),
                "required", List.of("budget", "usageTags", "resolution", "preferredVendors", "priority", "performanceTier", "budgetPolicy", "mustHave", "requiredGpuClasses", "requiredPartKeywords", "hardConstraintPolicy", "confidence", "parseNotes")
        );
    }

    private static Map<String, Object> confidenceSchema() {
        Map<String, Object> confidenceValue = MockData.map("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH"));
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "properties", MockData.map(
                        "budget", confidenceValue,
                        "usageTags", confidenceValue,
                        "resolution", confidenceValue,
                        "preferredVendors", confidenceValue,
                        "mustHave", confidenceValue,
                        "requiredGpuClasses", confidenceValue,
                        "requiredPartKeywords", confidenceValue
                ),
                "required", List.of("budget", "usageTags", "resolution", "preferredVendors", "mustHave", "requiredGpuClasses", "requiredPartKeywords")
        );
    }

    private static AiChatIntent normalizeIntent(String value, AiChatIntent fallback) {
        String normalized = text(value);
        if (normalized == null) {
            return fallback;
        }
        try {
            return AiChatIntent.valueOf(normalized.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static AiChatIntent classify(String message, String selectedCategory, Map<String, Object> context) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "왜", "이유", "근거", "설명")) {
            return AiChatIntent.EXPLAIN;
        }
        boolean hasBuildSignal = containsAny(normalized, "컴퓨터", "본체", "pc", "견적", "맞춰");
        boolean hasUsageSignal = containsAny(normalized, "게임", "개발", "영상", "편집", "ai", "cuda", "qhd", "fhd", "4k");
        boolean hasModifySignal = containsAny(normalized, "바꿔", "변경", "교체", "업그레이드", "추가", "빼줘", "낮춰", "올려", "비싸", "저렴", "싼", "가성비", "더 좋은", "상위", "고급", "빠른", "넉넉", "여유", "비슷", "가격대", "유지");
        boolean hasExistingBuildSignal = containsAny(normalized, "견적", "현재 견적", "기존", "구성", "부품");
        boolean asksUpgradeHeadroom = containsAny(normalized, "업그레이드 여유", "추후 업그레이드", "향후 업그레이드");
        if (hasModifySignal && !asksUpgradeHeadroom && (!hasBuildSignal || hasExistingBuildSignal)) {
            return AiChatIntent.BUILD_MODIFY;
        }
        if (containsAny(normalized, "알림", "목표가", "떨어지", "되면 알려", "가격")) {
            return AiChatIntent.PRICE_ALERT_HELP;
        }
        if (categoryFrom(firstText(selectedCategory, message)) != null && !containsAny(normalized, "컴퓨터", "본체", "pc", "견적", "맞춰")) {
            return AiChatIntent.PART_RECOMMEND;
        }
        if (hasBuildSignal || (normalized.contains("추천") && hasUsageSignal)) {
            return AiChatIntent.FULL_BUILD_RECOMMEND;
        }
        if (context != null && context.get("buildId") != null) {
            return AiChatIntent.EXPLAIN;
        }
        return AiChatIntent.ASK_FOLLOW_UP;
    }

    private static Map<String, Object> deterministicParsedContext(Map<String, Object> body, String message) {
        Integer budget = numberValue(body.get("budget"));
        if (budget == null) {
            budget = inferBudget(message);
        }
        List<String> usageTags = usageTags(body.get("usageTags"), message);
        String resolution = firstText(text(body.get("resolution")), inferResolution(message));
        List<String> preferredVendors = preferredVendors(body.get("preferredVendors"), message);
        List<String> mustHave = mustHave(message);
        List<String> requiredGpuClasses = inferRequiredGpuClasses(message);
        String performanceTier = inferPerformanceTier(message, resolution, usageTags, requiredGpuClasses);
        String budgetPolicy = budget == null
                ? ("ENTHUSIAST".equals(performanceTier) || !requiredGpuClasses.isEmpty() ? "OPEN_BUDGET" : "UNSPECIFIED")
                : "USER_BUDGET";
        return MockData.map(
                "usageTags", usageTags,
                "budget", budget,
                "resolution", resolution,
                "preferredVendors", preferredVendors,
                "priority", text(body.get("priority")),
                "performanceTier", performanceTier,
                "budgetPolicy", budgetPolicy,
                "mustHave", mustHave,
                "requiredGpuClasses", requiredGpuClasses,
                "requiredPartKeywords", requiredGpuClasses.stream().map(value -> value.replace("_", " ")).toList(),
                "hardConstraintPolicy", requiredGpuClasses.isEmpty() ? "NONE" : "MUST_INCLUDE",
                "confidence", MockData.map(
                        "usageTags", usageTags.isEmpty() ? "LOW" : "HIGH",
                        "budget", budget == null ? "LOW" : "HIGH",
                        "resolution", resolution == null ? "LOW" : "MEDIUM",
                        "preferredVendors", preferredVendors.isEmpty() ? "LOW" : "MEDIUM",
                        "hardConstraints", requiredGpuClasses.isEmpty() ? "LOW" : "HIGH"
                )
        );
    }

    private static Map<String, Object> withAgentParseMetadata(
            Map<String, Object> context,
            String parseMode,
            String sessionId,
            List<String> evidenceIds,
            List<AgentRagEvidenceDraft> evidenceSet,
            String parseNotes,
            String fallbackReason
    ) {
        AgentRagEvidenceDraft primaryEvidence = evidenceSet == null || evidenceSet.isEmpty() ? null : evidenceSet.get(0);
        Map<String, Object> result = new LinkedHashMap<>(normalizeParsedContext(context, context));
        result.put("parseMode", parseMode);
        result.put("parser", "ai-chat-engine-quote-v1");
        result.put("agentSessionId", sessionId);
        result.put("evidenceIds", evidenceIds);
        result.put("ragGuidance", primaryEvidence == null ? null : primaryEvidence.summary());
        result.put("ragSourceId", primaryEvidence == null ? null : primaryEvidence.sourceId());
        result.put("ragSourceIds", evidenceSet == null ? List.of() : evidenceSet.stream().map(AgentRagEvidenceDraft::sourceId).toList());
        result.put("parseEvidenceSummary", evidenceSummary(evidenceSet));
        result.put("parseNotes", parseNotes);
        if (fallbackReason != null) {
            result.put("fallbackReason", fallbackReason);
        }
        return result;
    }

    private static String evidenceSummary(List<AgentRagEvidenceDraft> evidenceSet) {
        if (evidenceSet == null || evidenceSet.isEmpty()) {
            return null;
        }
        return evidenceSet.stream()
                .map(AgentRagEvidenceDraft::summary)
                .filter(summary -> summary != null && !summary.isBlank())
                .limit(3)
                .reduce((left, right) -> left + " | " + right)
                .orElse(null);
    }

    private static List<Map<String, Object>> evidenceItems(List<String> ids, List<AgentRagEvidenceDraft> evidenceSet) {
        return java.util.stream.IntStream.range(0, evidenceSet.size())
                .mapToObj(index -> {
                    AgentRagEvidenceDraft evidence = evidenceSet.get(index);
                    return MockData.map(
                            "id", ids.get(index),
                            "sourceId", evidence.sourceId(),
                            "summary", evidence.summary(),
                            "chunkText", evidence.chunkText(),
                            "score", evidence.score(),
                            "metadata", evidence.metadata()
                    );
                })
                .toList();
    }

    private static Map<String, Object> normalizeParsedContext(Map<String, Object> source, Map<String, Object> fallback) {
        Integer budget = firstNumber(source.get("budget"), fallback.get("budget"));
        List<String> usageTags = normalizeUsageTags(stringList(source.get("usageTags")));
        if (usageTags.isEmpty()) {
            usageTags = normalizeUsageTags(stringList(fallback.get("usageTags")));
        }
        String resolution = normalizeResolution(firstText(text(source.get("resolution")), text(fallback.get("resolution"))));
        List<String> preferredVendors = normalizeVendors(stringList(source.get("preferredVendors")));
        if (preferredVendors.isEmpty()) {
            preferredVendors = normalizeVendors(stringList(fallback.get("preferredVendors")));
        }
        String performanceTier = normalizePerformanceTier(firstText(text(source.get("performanceTier")), text(fallback.get("performanceTier"))));
        String budgetPolicy = normalizeBudgetPolicy(firstText(text(source.get("budgetPolicy")), text(fallback.get("budgetPolicy"))));
        List<String> mustHave = normalizeMustHave(stringList(source.get("mustHave")));
        if (mustHave.isEmpty()) {
            mustHave = normalizeMustHave(stringList(fallback.get("mustHave")));
        }
        List<String> requiredGpuClasses = normalizeGpuClasses(stringList(source.get("requiredGpuClasses")));
        if (requiredGpuClasses.isEmpty()) {
            requiredGpuClasses = normalizeGpuClasses(stringList(fallback.get("requiredGpuClasses")));
        }
        List<String> requiredPartKeywords = normalizeKeywords(stringList(source.get("requiredPartKeywords")));
        if (requiredPartKeywords.isEmpty()) {
            requiredPartKeywords = normalizeKeywords(stringList(fallback.get("requiredPartKeywords")));
        }
        String hardConstraintPolicy = normalizeHardConstraintPolicy(firstText(text(source.get("hardConstraintPolicy")), text(fallback.get("hardConstraintPolicy"))));
        if (!requiredGpuClasses.isEmpty()) {
            hardConstraintPolicy = "MUST_INCLUDE";
            if (budget == null && "UNSPECIFIED".equals(budgetPolicy)) {
                budgetPolicy = "OPEN_BUDGET";
            }
            if (requiredGpuClasses.contains("RTX_5090") && "STANDARD".equals(performanceTier)) {
                performanceTier = "ENTHUSIAST";
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("usageTags", usageTags.isEmpty() ? List.of("GENERAL") : usageTags);
        result.put("budget", budget);
        result.put("resolution", resolution);
        result.put("preferredVendors", preferredVendors);
        result.put("priority", firstText(text(source.get("priority")), text(fallback.get("priority"))));
        result.put("performanceTier", performanceTier);
        result.put("budgetPolicy", budgetPolicy);
        result.put("mustHave", mustHave);
        result.put("requiredGpuClasses", requiredGpuClasses);
        result.put("requiredPartKeywords", requiredPartKeywords);
        result.put("hardConstraintPolicy", hardConstraintPolicy);
        result.put("confidence", normalizeConfidence(objectMap(source.get("confidence")), objectMap(fallback.get("confidence"))));
        String parseNotes = firstText(text(source.get("parseNotes")), text(fallback.get("parseNotes")));
        if (parseNotes != null) {
            result.put("parseNotes", parseNotes);
        }
        return result;
    }

    private static Map<String, Object> normalizeDraftEdit(
            Map<String, Object> source,
            String message,
            String selectedCategory,
            Map<String, Object> context
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        String inferredOperation = inferDraftOperation(message);
        String operation = !"NONE".equals(inferredOperation)
                ? inferredOperation
                : normalizeDraftOperation(text(source.get("operation")));
        String category = firstText(categoryFrom(firstText(selectedCategory, message)), categoryFrom(text(source.get("category"))));
        if (category == null) {
            category = categoryFrom(text(mostExpensiveDraftItem(context).get("category")));
        }
        String inferredPriceDirection = inferPriceDirection(message);
        String priceDirection = !"ANY".equals(inferredPriceDirection)
                ? inferredPriceDirection
                : normalizePriceDirection(text(source.get("priceDirection")));
        Integer targetMaxPrice = firstNumber(source.get("targetMaxPrice"), inferBudget(message));
        Integer targetQuantity = numberValue(source.get("targetQuantity"));
        result.put("operation", operation);
        result.put("category", category);
        result.put("priceDirection", priceDirection);
        result.put("targetMaxPrice", targetMaxPrice);
        result.put("targetQuantity", targetQuantity);
        result.put("reason", firstText(text(source.get("reason")), null));
        return result;
    }

    private static String normalizeDraftOperation(String value) {
        String normalized = text(value);
        if (normalized == null) return "NONE";
        String upper = normalized.toUpperCase(Locale.ROOT);
        return List.of("ADD", "REPLACE", "REMOVE", "UPDATE_QUANTITY", "ASK_FOLLOW_UP", "NONE").contains(upper) ? upper : "NONE";
    }

    private static String inferDraftOperation(String message) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "빼", "삭제", "제거", "remove", "delete")) return "REMOVE";
        if (containsAny(normalized, "수량", "개로", "장으로", "늘려", "줄여")) return "UPDATE_QUANTITY";
        if (containsAny(normalized, "바꿔", "변경", "교체", "낮춰", "올려", "싼", "저렴", "비싸", "추천", "더 좋은", "상위", "고급", "빠른", "넉넉", "여유", "비슷", "가격대", "유지")) return "REPLACE";
        return "NONE";
    }

    private static String normalizePriceDirection(String value) {
        String normalized = text(value);
        if (normalized == null) return "ANY";
        String upper = normalized.toUpperCase(Locale.ROOT);
        return List.of("CHEAPER", "MORE_EXPENSIVE", "SIMILAR_PRICE", "ANY").contains(upper) ? upper : "ANY";
    }

    private static String inferPriceDirection(String message) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "싼", "저렴", "낮춰", "줄여", "가성비", "예산 안", "이하", "초과", "비싸")) {
            return "CHEAPER";
        }
        if (containsAny(normalized, "좋은", "상위", "업그레이드", "성능", "올려", "비싸도", "고급", "빠른", "넉넉", "여유", "조용", "잘 식히", "큰 그래픽카드")) {
            return "MORE_EXPENSIVE";
        }
        if (containsAny(normalized, "비슷", "유지", "그 가격", "같은 가격")) {
            return "SIMILAR_PRICE";
        }
        return "ANY";
    }

    private static Map<String, Object> currentDraftItem(Map<String, Object> context, String category) {
        if (category == null) {
            return Map.of();
        }
        return draftItems(context).stream()
                .filter(item -> category.equals(text(item.get("category"))))
                .findFirst()
                .orElse(Map.of());
    }

    private static Map<String, Object> draftItem(Map<String, Object> context, String category) {
        return currentDraftItem(context, category);
    }

    private static String attrText(Map<String, Object> item, String key) {
        return text(attributesOf(item).get(key));
    }

    private static Integer attrNumber(Map<String, Object> item, String key) {
        return numberValue(attributesOf(item).get(key));
    }

    private static Map<String, Object> attributesOf(Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return Map.of();
        }
        if (item.containsKey("attributes")) {
            return objectMap(item.get("attributes"));
        }
        return item;
    }

    private static boolean sameRequired(String candidateValue, String requiredValue) {
        return requiredValue == null || candidateValue != null && requiredValue.equalsIgnoreCase(candidateValue);
    }

    private static boolean lessOrEqualRequired(Integer candidateValue, Integer maxValue) {
        return maxValue == null || candidateValue != null && candidateValue <= maxValue;
    }

    private static boolean greaterOrEqualRequired(Integer candidateValue, Integer minValue) {
        return minValue == null || candidateValue != null && candidateValue >= minValue;
    }

    private static Integer firstPositiveNumber(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private static boolean socketSupported(Object socketSupport, String socket) {
        if (socket == null || socketSupport == null) {
            return true;
        }
        if (socketSupport instanceof List<?> list) {
            return list.stream().anyMatch(item -> socket.equalsIgnoreCase(String.valueOf(item)));
        }
        return String.valueOf(socketSupport).toUpperCase(Locale.ROOT).contains(socket.toUpperCase(Locale.ROOT));
    }

    private static boolean socketSupportedRequired(Object socketSupport, String socket) {
        if (socket == null) {
            return true;
        }
        return socketSupport != null && socketSupported(socketSupport, socket);
    }

    private static Map<String, Object> mostExpensiveDraftItem(Map<String, Object> context) {
        return draftItems(context).stream()
                .max((left, right) -> Integer.compare(
                        firstNumber(left.get("currentPrice"), left.get("lineTotal")) == null ? 0 : firstNumber(left.get("currentPrice"), left.get("lineTotal")),
                        firstNumber(right.get("currentPrice"), right.get("lineTotal")) == null ? 0 : firstNumber(right.get("currentPrice"), right.get("lineTotal"))
                ))
                .orElse(Map.of());
    }

    private static List<Map<String, Object>> draftItems(Map<String, Object> context) {
        Map<String, Object> currentQuoteDraft = objectMap(context.get("currentQuoteDraft"));
        return objectMaps(currentQuoteDraft.get("items"));
    }

    private static Integer inferBudget(String message) {
        String normalized = message == null ? "" : message.replace(",", "");
        Matcher manwon = BUDGET_MANWON.matcher(normalized);
        if (manwon.find()) {
            return Integer.parseInt(manwon.group(1)) * 10_000;
        }
        Matcher number = BUDGET_NUMBER.matcher(normalized);
        if (number.find()) {
            return Integer.parseInt(number.group(1));
        }
        return null;
    }

    private static List<String> usageTags(Object value, String message) {
        List<String> explicit = normalizeUsageTags(stringList(value));
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        if (containsAny(normalized, "게임", "배그", "로스트아크", "qhd", "fhd", "4k")) result.add("GAMING");
        if (containsAny(normalized, "개발", "코딩", "ide", "docker", "컴파일")) result.add("DEVELOPMENT");
        if (containsAny(normalized, "영상", "편집", "프리미어", "다빈치", "렌더")) result.add("VIDEO_EDIT");
        if (containsAny(normalized, "ai", "cuda", "llm", "학습")) result.add("AI_DEV");
        return result.isEmpty() ? List.of("GENERAL") : result.stream().distinct().toList();
    }

    private static String inferResolution(String message) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        if (normalized.contains("4k")) return "4K";
        if (normalized.contains("qhd")) return "QHD";
        if (normalized.contains("fhd")) return "FHD";
        return null;
    }

    private static String inferPerformanceTier(
            String message,
            String resolution,
            List<String> usageTags,
            List<String> requiredGpuClasses
    ) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        if (requiredGpuClasses.contains("RTX_5090")
                || containsAny(normalized, "끝판왕", "최고급", "최상급", "하이엔드", "플래그십", "제일 좋은", "가장 좋은", "예산 무관", "예산 상관", "돈 상관")) {
            return "ENTHUSIAST";
        }
        if ("4K".equalsIgnoreCase(resolution)
                || "QHD".equalsIgnoreCase(resolution)
                || containsAny(normalized, "144hz", "240hz", "144프레임", "240프레임", "고주사율", "울트라", "풀옵", "상옵", "고사양")
                || usageTags.contains("AI_DEV")
                || usageTags.contains("VIDEO_EDIT")) {
            return "PERFORMANCE";
        }
        return "STANDARD";
    }

    private static List<String> preferredVendors(Object value, String message) {
        List<String> explicit = normalizeVendors(stringList(value));
        if (!explicit.isEmpty()) {
            return explicit;
        }
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        if (containsAny(normalized, "nvidia", "엔비디아", "rtx", "지포스")) result.add("NVIDIA");
        if (containsAny(normalized, "amd", "라데온", "라이젠")) result.add("AMD");
        if (containsAny(normalized, "intel", "인텔")) result.add("INTEL");
        return result.stream().distinct().toList();
    }

    private static List<String> mustHave(String message) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        if (containsAny(normalized, "와이파이", "wifi", "wi-fi")) result.add("WIFI");
        if (containsAny(normalized, "저소음", "조용", "소음")) result.add("LOW_NOISE");
        return result;
    }

    private static List<String> inferRequiredGpuClasses(String message) {
        String normalized = safe(message);
        List<String> result = new ArrayList<>();
        Matcher matcher = RTX_CLASS.matcher(normalized);
        while (matcher.find()) {
            result.add("RTX_" + matcher.group(1));
        }
        return result.stream().distinct().toList();
    }

    private static String categoryFrom(String value) {
        String normalized = safe(value).toLowerCase(Locale.ROOT);
        String canonical = normalized.toUpperCase(Locale.ROOT).replace("-", "_");
        if (BUILD_CATEGORIES.contains(canonical)) {
            return canonical;
        }
        if (containsAny(normalized, "gpu", "그래픽", "글카", "rtx", "지포스", "라데온")) return "GPU";
        if (containsAny(normalized, "cpu", "프로세서", "라이젠", "인텔")) return "CPU";
        if (containsAny(normalized, "메인보드", "보드", "motherboard")) return "MOTHERBOARD";
        if (containsAny(normalized, "ram", "램", "메모리")) return "RAM";
        if (containsAny(normalized, "ssd", "저장", "스토리지")) return "STORAGE";
        if (containsAny(normalized, "파워", "psu", "전원")) return "PSU";
        if (containsAny(normalized, "케이스", "case")) return "CASE";
        if (containsAny(normalized, "쿨러", "cooler", "수랭", "공랭")) return "COOLER";
        return null;
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "GPU" -> "그래픽카드";
            case "CPU" -> "CPU";
            case "MOTHERBOARD" -> "메인보드";
            case "RAM" -> "RAM";
            case "STORAGE" -> "SSD";
            case "PSU" -> "파워";
            case "CASE" -> "케이스";
            case "COOLER" -> "쿨러";
            default -> category;
        };
    }

    private static String gpuClass(AiChatEngineResponse.PartRecommendation part) {
        if (part == null || part.attributes() == null) {
            return null;
        }
        String gpuClass = text(part.attributes().get("gpuClass"));
        if (gpuClass == null) {
            gpuClass = text(part.attributes().get("hardwareClass"));
        }
        if (gpuClass == null) {
            gpuClass = text(part.name());
        }
        List<String> normalized = normalizeGpuClasses(List.of(gpuClass));
        return normalized.isEmpty() ? null : normalized.get(0);
    }

    private static int defaultQuantity(String category) {
        return "RAM".equals(category) || "STORAGE".equals(category) ? 2 : 1;
    }

    private static double categoryWeight(String category) {
        return switch (category) {
            case "GPU" -> 0.39;
            case "CPU" -> 0.17;
            case "MOTHERBOARD" -> 0.11;
            case "RAM" -> 0.07;
            case "STORAGE" -> 0.07;
            case "PSU" -> 0.08;
            case "CASE" -> 0.06;
            case "COOLER" -> 0.05;
            default -> 0.04;
        };
    }

    private static int inferredBudgetFor(Map<String, Object> parsedContext) {
        String performanceTier = normalizePerformanceTier(text(parsedContext.get("performanceTier")));
        String budgetPolicy = normalizeBudgetPolicy(text(parsedContext.get("budgetPolicy")));
        List<String> requiredGpuClasses = normalizeGpuClasses(stringList(parsedContext.get("requiredGpuClasses")));
        if ("ENTHUSIAST".equals(performanceTier) || "OPEN_BUDGET".equals(budgetPolicy) || !requiredGpuClasses.isEmpty()) {
            return ENTHUSIAST_OPEN_BUDGET;
        }
        if ("PERFORMANCE".equals(performanceTier) || isPerformanceTarget(parsedContext)) {
            return PERFORMANCE_UNSPECIFIED_BUDGET;
        }
        return STANDARD_UNSPECIFIED_BUDGET;
    }

    private static boolean isPerformanceTarget(Map<String, Object> parsedContext) {
        String resolution = text(parsedContext.get("resolution"));
        if ("QHD".equalsIgnoreCase(resolution) || "4K".equalsIgnoreCase(resolution)) {
            return true;
        }
        List<String> usageTags = stringList(parsedContext.get("usageTags"));
        return usageTags.contains("AI_DEV") || usageTags.contains("VIDEO_EDIT");
    }

    private static List<String> normalizeUsageTags(List<String> values) {
        List<String> allowed = List.of("GAMING", "DEVELOPMENT", "VIDEO_EDIT", "AI_DEV", "GENERAL");
        return values.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(allowed::contains)
                .distinct()
                .toList();
    }

    private static String normalizeResolution(String value) {
        String normalized = text(value);
        if (normalized == null) return null;
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (upper.contains("4K")) return "4K";
        if (upper.contains("QHD")) return "QHD";
        if (upper.contains("FHD")) return "FHD";
        return null;
    }

    private static List<String> normalizeVendors(List<String> values) {
        List<String> allowed = List.of("NVIDIA", "AMD", "INTEL");
        return values.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(allowed::contains)
                .distinct()
                .toList();
    }

    private static List<String> normalizeMustHave(List<String> values) {
        List<String> allowed = List.of("WIFI", "LOW_NOISE");
        return values.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(allowed::contains)
                .distinct()
                .toList();
    }

    private static List<String> normalizeGpuClasses(List<String> values) {
        return values.stream()
                .map(value -> value.toUpperCase(Locale.ROOT).replace(" ", "_").replace("-", "_"))
                .map(value -> value.startsWith("RTX_") ? value : ("RTX_" + value.replace("RTX", "").replace("_", "")))
                .filter(value -> value.matches("RTX_[0-9]{4}"))
                .distinct()
                .toList();
    }

    private static List<String> normalizeKeywords(List<String> values) {
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static String normalizeHardConstraintPolicy(String value) {
        String normalized = text(value);
        if (normalized == null) return "NONE";
        String upper = normalized.toUpperCase(Locale.ROOT);
        return List.of("MUST_INCLUDE", "NONE").contains(upper) ? upper : "NONE";
    }

    private static String normalizePerformanceTier(String value) {
        String normalized = text(value);
        if (normalized == null) return "STANDARD";
        String upper = normalized.toUpperCase(Locale.ROOT);
        return List.of("ENTHUSIAST", "PERFORMANCE", "STANDARD").contains(upper) ? upper : "STANDARD";
    }

    private static String normalizeBudgetPolicy(String value) {
        String normalized = text(value);
        if (normalized == null) return "UNSPECIFIED";
        String upper = normalized.toUpperCase(Locale.ROOT);
        return List.of("USER_BUDGET", "OPEN_BUDGET", "UNSPECIFIED").contains(upper) ? upper : "UNSPECIFIED";
    }

    private static Map<String, Object> normalizeConfidence(Map<String, Object> source, Map<String, Object> fallback) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> keys = List.of("usageTags", "budget", "resolution", "preferredVendors");
        for (String key : keys) {
            String value = firstText(text(source.get(key)), text(fallback.get(key)));
            result.put(key, List.of("LOW", "MEDIUM", "HIGH").contains(value) ? value : "LOW");
        }
        return result;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        String text = text(value);
        if (text == null) return List.of();
        return List.of(text.split(",")).stream().map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(DefaultAiChatEngine::objectMap)
                .filter(map -> !map.isEmpty())
                .toList();
    }

    private static String sourceEvidenceId(AgentRagEvidenceDraft draft) {
        if (draft == null || draft.metadata() == null) {
            return null;
        }
        Object value = draft.metadata().get("sourceEvidenceId");
        return text(value);
    }

    private static Integer firstNumber(Object first, Object fallback) {
        Integer value = numberValue(first);
        return value == null ? numberValue(fallback) : value;
    }

    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) return null;
        return Integer.valueOf(text.replace(",", ""));
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = text(value);
        if (text == null) return null;
        return Long.valueOf(text.replace(",", ""));
    }

    private static String requireText(Object value, String message) {
        String text = text(value);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return text;
    }

    private AiProfileDefinition requireBuildChatProfile(String requestedAiProfile) {
        try {
            return aiProfileConfig.buildChatProfile(requestedAiProfile);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> parseJsonObject(String output) {
        try {
            Object parsed = OBJECT_MAPPER.readValue(extractJsonObject(output), Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
            throw new IllegalArgumentException("JSON object가 아닙니다.");
        } catch (Exception error) {
            throw new IllegalArgumentException("LLM JSON 응답을 해석할 수 없습니다.", error);
        }
    }

    private static String extractJsonObject(String output) {
        String text = text(output);
        if (text == null) {
            throw new IllegalArgumentException("빈 LLM 응답입니다.");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("JSON object를 찾을 수 없습니다.");
        }
        return text.substring(start, end + 1);
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 변환에 실패했습니다.", e);
        }
    }

    private static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record BuildPreviewPlan(String name, String recommendedFor, double budgetRatio, String confidence) {
    }
}
