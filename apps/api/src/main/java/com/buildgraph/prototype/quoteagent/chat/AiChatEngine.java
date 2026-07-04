package com.buildgraph.prototype.quoteagent.chat;

import com.buildgraph.prototype.quoteagent.query.AiChatSessionState;
import com.buildgraph.prototype.quoteagent.query.AiChatSessionStore;
import com.buildgraph.prototype.quoteagent.llm.AiChatClient;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;
import com.buildgraph.prototype.opsagent.profile.*;


import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AiChatEngine {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BUILD_CHAT_SCHEMA_NAME = "buildgraph_ai_build_chat_plan";
    
    /* 프롬프트 및 카테고리 객체 */
    private static final String BUILD_CHAT_SYSTEM_PROMPT = """
            당신은 컴퓨터 견적 상담 챗봇입니다.
            사용자의 대화를 듣고 "대화모드(conversationMode)" 혹은 "행동모드" 중 하나를 결정합니다.
            대화모드는 지속적으로 대화를 하면서 contextPath을 쌓습니다. 
            만약 contextPath가 충분하다면, 행동모드로 넘어가서 개시를 합니다.

            대화모드에서는 action은 null, contextPatch에는 맥락을 쌓습니다.
            반대로 행동모드에서는 action에는 타입에 맞는 행동을 비롯한 내용을 넣습니다. 여기선 contextPath는 null입니다.
            """;
    private static final List<String> BUILD_CATEGORIES = List.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );

    private final JdbcTemplate jdbcTemplate;
    private final AiChatClient openAiResponsesClient;
    private final AiProfileConfig aiProfileConfig;
    private final PartReplacementRanker partReplacementRanker;
    private final AiChatSessionStore aiChatSessionStore;

    /* 오케스트레이션 함수
       사용자 메시지 받음 => 응답 생성 
       request: 사용자 메시지 + 화면 맥락
       selectedAi: 선택된 Ai 모델 */
    public AiChatResponseDto respondLlmRequired(AiChatRequestDto request, String selectedAi) {
        String message = requireText(request == null ? null : request.message(), "챗봇 메시지가 필요합니다.");
        if (!openAiResponsesClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다.");
        }
        
        /* 파싱된 객체들: 수정 필요 */
        AiProfileDefinition buildProfile = requireBuildChatProfile(selectedAi);
        Map<String, Object> context = Map.of();
        Map<String, Object> fallbackContext = deterministicParsedContext(context, message);

        /* LLM 응답 객체 */
        Map<String, Object> llmResponse;

        /* LLM으로 요청: 실제 모델에 접근 시행 */
        try {     
            llmResponse = getLLMResponse(request, buildProfile);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 응답 JSON을 처리할 수 없습니다.", error);
        }

        /* LLM 응답 결과 파싱 */
        boolean conversationMode = Boolean.TRUE.equals(llmResponse.get("conversationMode"));
        String replyMessage = text(llmResponse.get("replyMessage"));
        
        /* 행동 모드 진입 여부 결정하기 */  
        if(conversationMode){
            Map<String, Object> contextPatch = objectMap(llmResponse.get("contextPatch"));
            return conversationResponse(replyMessage, contextPatch);
        }
        
        /* 요청 분석을 통해 응답 분기를 결정 */
        Map<String, Object> actionMap = objectMap(llmResponse.get("action"));
        AiChatIntent action = AiChatIntent.valueOf(text(actionMap.get("type")));
        
        AiChatResponseDto base = switch (action) {
            case FULL_BUILD_RECOMMEND -> fullBuildResponse(replyMessage, actionMap);
            case PART_RECOMMEND -> partRecommendResponse(replyMessage, actionMap);
            case BUILD_MODIFY -> buildModifyResponse(replyMessage, actionMap);
            case PRICE_ALERT_HELP -> priceAlertResponse(replyMessage, actionMap);
        };

        return null;
    }

    /* 풀 견적 생성 */
    private AiChatResponseDto fullBuildResponse(String message, Map<String, Object> parsedContext) {
        /* 견적 생성 함수 호출 */
        List<AiChatResponseDto.BuildRecommendation> recommendations = buildRecommendations(message, parsedContext);
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

    /* 일부 장비 추천 */
    private AiChatResponseDto partRecommendResponse(String message, String selectedCategory) {
        String category = categoryFrom(firstText(selectedCategory, message));

        List<AiChatResponseDto.PartRecommendation> parts = partRecommendations(category, 3);
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

    private AiChatResponseDto buildModifyResponse(String message, String selectedCategory, Map<String, Object> context, Map<String, Object> draftEdit) {
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
        List<AiChatResponseDto.PartRecommendation> candidates = selection.parts();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", effectiveCategory);
        payload.put("quantity", defaultQuantity(effectiveCategory));
        payload.put("source", "AI_CHAT_ENGINE");
        if (currentItem.get("partId") != null) {
            payload.put("currentPartId", text(currentItem.get("partId")));
        }
        payload.put("priceDirection", priceDirection);
        if (!candidates.isEmpty()) {
            AiChatResponseDto.PartRecommendation firstCandidate = candidates.get(0);
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

    private AiChatResponseDto priceAlertResponse(String message, String selectedCategory) {
        String category = categoryFrom(firstText(selectedCategory, message));
        /* 추후 수정 필요 */
        Integer targetPrice = null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", category);
        payload.put("targetPrice", targetPrice);
        payload.put("source", "AI_CHAT_ENGINE");
        return response(
                targetPrice == null
                        ? "목표가 알림을 만들려면 원하는 가격을 함께 알려주세요."
                        : "목표가 알림에 필요한 가격 조건을 추출했습니다.",
                targetPrice == null ? AiChatIntent.CONVERSATION : AiChatIntent.PRICE_ALERT_HELP,
                List.of(new AiChatAction(targetPrice == null ? AiChatActionType.ASK_FOLLOW_UP : AiChatActionType.CREATE_PRICE_ALERT, "목표가 알림 설정", payload)),
                List.of(),
                category == null ? List.of() : partRecommendations(category, 3),
                MockData.map("category", category, "targetPrice", targetPrice)
        );
    }

    private AiChatResponseDto conversationResponse(String replyMessage, Map<String, Object> contextPatch) {
        return response(
                "추천 근거는 예산, 내부 자산 현재가, 주요 부품 스펙, RAG 근거, Tool 검증 결과를 기준으로 설명합니다.",
                AiChatIntent.CONVERSATION,
                List.of(new AiChatAction(AiChatActionType.OPEN_SELF_QUOTE, "견적에서 근거 보기", Map.of("route", "/self-quote"))),
                List.of(),
                List.of(),
                MockData.map("question", message)
        );
    }

    private AiChatResponseDto response(
            String assistantMessage,
            AiChatIntent intent,
            List<AiChatAction> actions,
            List<AiChatResponseDto.BuildRecommendation> recommendations,
            List<AiChatResponseDto.PartRecommendation> partRecommendations,
            Map<String, Object> parsedContext
    ) {
        return new AiChatResponseDto(
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

    private List<AiChatResponseDto.BuildRecommendation> buildRecommendations(String message, Map<String, Object> parsedContext) {
        /* 추후 구현 필요 */
        int effectiveBudget = 0;
        List<BuildPreviewPlan> plans = previewPlansFor(message);
        return plans.stream()
                .map(plan -> {
                    List<AiChatResponseDto.PartRecommendation> items = BUILD_CATEGORIES.stream()
                            .map(category -> choosePart(category, Math.max(50_000, (int) (effectiveBudget * plan.budgetRatio() * categoryWeight(category))), parsedContext))
                            .filter(part -> part != null)
                            .toList();
                    int total = items.stream()
                            .mapToInt(item -> item.price() * defaultQuantity(item.category()))
                            .sum();
                    return new AiChatResponseDto.BuildRecommendation(
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

    private AiChatResponseDto.PartRecommendation choosePart(String category, int targetPrice, Map<String, Object> parsedContext) {
        List<AiChatResponseDto.PartRecommendation> parts = partRecommendations(category, 50);
        if ("GPU".equals(category)) {
            List<String> requiredGpuClasses = normalizeGpuClasses(stringList(parsedContext.get("requiredGpuClasses")));
            if (!requiredGpuClasses.isEmpty()) {
                List<AiChatResponseDto.PartRecommendation> matching = parts.stream()
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

    /* DB에서 부품 조회: sql 접근 쿼리.. 따로 빼야 함 */
    private List<AiChatResponseDto.PartRecommendation> partRecommendations(String category, int limit) {
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
                    return new AiChatResponseDto.PartRecommendation(
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
        List<AiChatResponseDto.PartRecommendation> parts = compatibleReplacementParts(category, context, partRecommendations(category, 50));
        return partReplacementRanker.select(category, currentItem, priceDirection, targetMaxPrice, parts, limit);
    }

    private static List<AiChatResponseDto.PartRecommendation> compatibleReplacementParts(
            String category,
            Map<String, Object> context,
            List<AiChatResponseDto.PartRecommendation> parts
    ) {
        if (parts.isEmpty()) {
            return parts;
        }
        List<AiChatResponseDto.PartRecommendation> filtered = switch (category) {
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

    private static List<AiChatResponseDto.PartRecommendation> filterByCpuSocket(
            Map<String, Object> context,
            List<AiChatResponseDto.PartRecommendation> parts
    ) {
        String motherboardSocket = attrText(draftItem(context, "MOTHERBOARD"), "socket");
        if (motherboardSocket == null) {
            return parts;
        }
        return parts.stream()
                .filter(part -> sameRequired(attrText(part.attributes(), "socket"), motherboardSocket))
                .toList();
    }

    private static List<AiChatResponseDto.PartRecommendation> filterByMotherboardPlatform(
            Map<String, Object> context,
            List<AiChatResponseDto.PartRecommendation> parts
    ) {
        String cpuSocket = attrText(draftItem(context, "CPU"), "socket");
        String memoryType = attrText(draftItem(context, "RAM"), "memoryType");
        return parts.stream()
                .filter(part -> sameRequired(attrText(part.attributes(), "socket"), cpuSocket))
                .filter(part -> sameRequired(attrText(part.attributes(), "memoryType"), memoryType))
                .toList();
    }

    private static List<AiChatResponseDto.PartRecommendation> filterByMemoryType(
            Map<String, Object> context,
            List<AiChatResponseDto.PartRecommendation> parts
    ) {
        String motherboardMemoryType = attrText(draftItem(context, "MOTHERBOARD"), "memoryType");
        if (motherboardMemoryType == null) {
            return parts;
        }
        return parts.stream()
                .filter(part -> sameRequired(attrText(part.attributes(), "memoryType"), motherboardMemoryType))
                .toList();
    }

    private static List<AiChatResponseDto.PartRecommendation> filterByGpuFit(
            Map<String, Object> context,
            List<AiChatResponseDto.PartRecommendation> parts
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

    private static List<AiChatResponseDto.PartRecommendation> filterByPsuCapacity(
            Map<String, Object> context,
            List<AiChatResponseDto.PartRecommendation> parts
    ) {
        Integer requiredPsuW = attrNumber(draftItem(context, "GPU"), "requiredSystemPowerW");
        if (requiredPsuW == null) {
            return parts;
        }
        return parts.stream()
                .filter(part -> greaterOrEqualRequired(firstPositiveNumber(attrNumber(part.attributes(), "capacityW"), attrNumber(part.attributes(), "wattage")), requiredPsuW))
                .toList();
    }

    private static List<AiChatResponseDto.PartRecommendation> filterByCaseFit(
            Map<String, Object> context,
            List<AiChatResponseDto.PartRecommendation> parts
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

    private static List<AiChatResponseDto.PartRecommendation> filterByCoolerFit(
            Map<String, Object> context,
            List<AiChatResponseDto.PartRecommendation> parts
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
            List<AiChatResponseDto.PartRecommendation> candidates
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


    /* LLM 모델과 접합부: 
       Dto, 
       rule 기반 서버가 파싱한 데이터,
       사용할 LLM 모델 */
    private Map<String, Object> getLLMResponse(
            AiChatRequestDto request,
            AiProfileDefinition buildProfile
    ) {
        /* request에서 파싱 => 순수 메시지*/
        String message = requireText(request.message(), "메시지가 필요합니다.");
        
        /* sessionId로 맥락 찾아오기 진행 */
        AiChatSessionState session = aiChatSessionStore.findOrCreate(request.sessionId());
        Map<String, Object> context = session.context();

        /* 여기서 실제 LLM을 호출한다(실제 접합부):
           1. 시스템 지시문
           2. 입력 문자열(userPrompt)
           3. 출력 스키마 명
           4. 출력 스키마 형태
           5. 사용할 모델
           6. 이성 수준 설정
           7. 출력 토큰 제한 */
        LlmResponseResult result = openAiResponsesClient.createStructuredJsonResult(
                BUILD_CHAT_SYSTEM_PROMPT,
                json(MockData.map(
                    "rawMessage", message,
                    "context", context
                )),
                BUILD_CHAT_SCHEMA_NAME,
                llmOutputSchema(),
                buildProfile.model(),
                buildProfile.reasoningEffort(),
                buildProfile.maxOutputTokens()
        );
        
        return parseJsonObject(result.text());
    }

    /* 출력 스키마 형태 구성 */
    private static Map<String, Object> llmOutputSchema() {
        return MockData.map(
                    "type", "object",
                    "additionalProperties", false,
                    "properties", MockData.map(
                        "conversationMode", MockData.map("type", "boolean"),
                        "replyMessage", MockData.map("type", "string"),
                        "action", MockData.map(
                            "type", List.of("object", "null"), "additionalProperties", false,
                            "properties", MockData.map(
                                "type", MockData.map("type", "string", "enum", List.of(
                                    "FULL_BUILD_RECOMMEND",
                                    "PART_RECOMMEND",
                                    "BUILD_MODIFY",
                                    "PRICE_ALERT_HELP"
                                )),
                                "ragQuery", MockData.map("type", "object","additionalProperties", true)
                            ),
                            "required", List.of("type", "ragQuery")
                        ),
                        "contextPatch", MockData.map(
                            "type", "object",
                            "additionalProperties", false,
                            "properties", MockData.map(
                                "budget", MockData.map("type", List.of("integer", "null")),
                                "usageTags", MockData.map("type", "array","items", MockData.map("type", "string")),
                                "missingSlots", MockData.map("type", "array","items", MockData.map("type", "string"))
                            ),
                            "required", List.of("budget", "usageTags", "missingSlots")
                    )),
                    "required", List.of("conversationMode", "replyMessage", "action", "contextPatch")
        );
    }

    private static Map<String, Object> deterministicParsedContext(Map<String, Object> body, String message) {
        Integer budget = numberValue(body.get("budget"));
        if (budget == null) {
            budget = null;
        }
        List<String> usageTags = usageTags(body.get("usageTags"), message);
        String resolution = firstText(text(body.get("resolution")), inferResolution(message));
        List<String> preferredVendors = preferredVendors(body.get("preferredVendors"), message);
        List<String> mustHave = mustHave(message);
        List<String> requiredGpuClasses = List.of();
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
        /* 추후 반영 필요 */
        Integer targetMaxPrice = null;
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

    private static String gpuClass(AiChatResponseDto.PartRecommendation part) {
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

    private static List<String> normalizeUsageTags(List<String> values) {
        List<String> allowed = List.of("GAMING", "DEVELOPMENT", "VIDEO_EDIT", "AI_DEV", "GENERAL");
        return values.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(allowed::contains)
                .distinct()
                .toList();
    }

    private static List<String> normalizeVendors(List<String> values) {
        List<String> allowed = List.of("NVIDIA", "AMD", "INTEL");
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
                .map(AiChatEngine::objectMap)
                .filter(map -> !map.isEmpty())
                .toList();
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

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 직렬화에 실패했습니다.", error);
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

    private record BuildPreviewPlan(String name, String recommendedFor, double budgetRatio, String confidence) {
    }
}
