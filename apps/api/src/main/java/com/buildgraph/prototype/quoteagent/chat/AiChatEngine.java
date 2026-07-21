package com.buildgraph.prototype.quoteagent.chat;

import static com.buildgraph.prototype.quoteagent.chat.AiChatUtil.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.opsagent.profile.AiProfileConfig;
import com.buildgraph.prototype.opsagent.profile.AiProfileDefinition;
import com.buildgraph.prototype.opsagent.profile.LLMresponseDto;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatRagQueryDto;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatRequestDto;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatResponseDto;
import com.buildgraph.prototype.quoteagent.llm.AiChatClient;
import com.buildgraph.prototype.quoteagent.query.AiChatSessionQuery;
import com.buildgraph.prototype.quoteagent.query.AiChatSessionState;
import com.buildgraph.prototype.quoteagent.schema.AiChatOutputSchema;
import com.buildgraph.prototype.quoteagent.tools.PartReplacementRanker;
import com.buildgraph.prototype.recommender.matching.PartMatchService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AiChatEngine {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BUILD_CHAT_SCHEMA_NAME = "buildgraph_ai_build_chat_plan";


    private final AiChatClient aiChatClient;
    private final AiProfileConfig aiProfileConfig;
    private final PartReplacementRanker partReplacementRanker;
    private final AiChatSessionQuery aiChatSessionStore;
    private final PartMatchService partMatchService;

    /* 프롬프트 및 카테고리 객체 */
    private static final String BUILD_CHAT_SYSTEM_PROMPT = """
        당신은 컴퓨터 견적 상담 챗봇이다.
        사용자의 대화를 듣고 "대화모드(conversationMode)" 혹은 "행동모드" 중 하나를 결정한다.
        기존 context와 현재 rawMessage를 함께 판단한다.
        contextPatch.budget은 현재 추천 대상의 예산이며, rawMessage에 새 예산이 있으면 원 단위 정수로 넣고 없으면 null로 둔다.
        전체 견적 추천 요청은 budget이 null이 아니고 usageTags에 구체적인 용도가 하나 이상 존재하면 조건이 충분한 것으로 판단한다.
        현재 rawMessage에서 전체 견적에 필요한 예산과 용도가 모두 확인되었다면 추가 질문을 하지 말고 즉시 행동모드로 전환한다.
        행동모드에서는 사용자의 의도에 따라 action.type을 FULL_BUILD_RECOMMEND, PART_RECOMMEND, BUILD_MODIFY 중 하나로 설정한다.

        대화모드에서는 action은 null, contextPatch에는 맥락을 쌓는다.
        행동모드에서는 action에 type에는 사용자의 의도를 파악하여 행동형태문구를 넣는다.
        행동모드에서의 action의 ragQuery에는 performance와 value에 0~1 사이의 값을 넣는다.
        contextPatch를 바탕으로 성능과 가성비를 얼마나 중시하는지 0~1 사이의 값이다.

        PART_RECOMMEND와 BUILD_MODIFY에서는 action내에 selectedCategory를 넣는다.
        PART_RECOMMEND에서는 부품 하나, BUILD_MODIFY에선 하나 이상이다.
        selectedCategory가 불분명하면 행동모드로 전환하지 말고 추가 질문한다.

        replyMessage는 사용자에게 자세한 설명을 해야 하며, 30~100자 사이로 한다.
        """;
    private static final List<String> BUILD_CATEGORIES = List.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );

    /* 오케스트레이션 함수
       사용자 메시지 받음 => 응답 생성 
       request: 사용자 메시지 + 화면 맥락
       selectedAi: 선택된 Ai 모델 */
    public AiChatResponseDto LLMorchestrator(AiChatRequestDto request, String selectedAi) {
        if (!aiChatClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다.");
        }

        /* 1. sessionId 관리 
           2. llm 응답 준비 */
        AiChatSessionState session = aiChatSessionStore.findOrCreate(request.sessionId());
        String sessionId = session.sessionId();

        AiProfileDefinition buildProfile = requireBuildChatProfile(aiProfileConfig, selectedAi);
        Map<String, Object> llmResponse;

        
        /* LLM으로 요청: 실제 모델에 접근 시행 */
        try {     
            llmResponse = getLLMResponse(request, buildProfile, session.context());
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 런타임 에러: ", error);
        }

        
        /* LLM 응답 결과 파싱 */
        boolean conversationMode = Boolean.TRUE.equals(llmResponse.get("conversationMode"));
        String replyMessage = text(llmResponse.get("replyMessage"));
        
        /* 행동 모드 진입 여부 결정하기 => 내부는 대화 모드 진행 */  
        Map<String, Object> newContextPatch = objectMap(llmResponse.get("contextPatch"));
        if(conversationMode){
            return conversationResponse(replyMessage, newContextPatch, sessionId);
        }

        
        /* 행동 모드에서도 현재 요청에서 추출된 예산과 용도를 세션에 반영 */
        aiChatSessionStore.updateSession(sessionId, newContextPatch);
        Map<String, Object> currentContext = new LinkedHashMap<>(session.context());
        newContextPatch.forEach((key, value) -> {
            if (value != null) {
                currentContext.put(key, value);
            }
        });
        
        /* 요청 분석을 통해 응답 분기를 결정 */
        Map<String, Object> actionMap = objectMap(llmResponse.get("action"));
        AiChatIntent action = AiChatIntent.valueOf(text(actionMap.get("type")));
        /* 임시 디버깅 */
        System.out.println("선택된 행동: " + action);
        return switch (action) {
            case FULL_BUILD_RECOMMEND -> fullBuildResponse(replyMessage, actionMap, currentContext, sessionId);
            case PART_RECOMMEND -> partRecommendResponse(replyMessage, actionMap, currentContext, sessionId);
            case BUILD_MODIFY -> buildModifyResponse(replyMessage, text(actionMap.get("selectedCategory")), Map.of(), actionMap, sessionId);
        };
    }

    /* LLM 모델과 접합부: 
        Dto, 
        rule 기반 서버가 파싱한 데이터,
        사용할 LLM 모델 */
    private Map<String, Object> getLLMResponse(
            AiChatRequestDto request,
            AiProfileDefinition buildProfile,
            Map<String, Object> context
    ) {
        /* request에서 파싱 => 순수 메시지*/
        String message = requireText(request.message(), "메시지가 필요합니다.");
        
        /* sessionId로 맥락 찾아오기 진행 */

        /* 여기서 실제 LLM을 호출한다(실제 접합부):
            1. 시스템 지시문
            2. 입력 문자열(userPrompt)
            3. 출력 스키마 명
            4. 출력 스키마 형태
            5. 사용할 모델
            6. 이성 수준 설정
            7. 출력 토큰 제한 */
        LLMresponseDto result = aiChatClient.generateLLMresponse(
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

    /* 출력 스키마 형태 구성(강제된 형태) */
    private static Map<String, Object> llmOutputSchema() {
        return AiChatOutputSchema.schema();
    }

    /* 대화 모드에서의 객체 생성에 관여 */
    private AiChatResponseDto conversationResponse(
            String replyMessage, 
            Map<String, Object> newContextPatch, 
            String sessionId) {
        /* Spring에 context를 update */
        aiChatSessionStore.updateSession(sessionId, newContextPatch);

        /* Dto로 감싸서 반환 */
        return new AiChatResponseDto(
            replyMessage,
            sessionId,
            "CONVERSATION",
            List.of(),
            List.of()
        );    
    }

    /* 풀 견적 생성: recommender 사용 */
    private AiChatResponseDto fullBuildResponse(
            String message,
            Map<String, Object> actionMap,
            Map<String, Object> context,
            String sessionId
    ) {
        int budget = ((Number) context.get("budget")).intValue(); 
        AiChatRagQueryDto ragQuery = OBJECT_MAPPER.convertValue(
                                        actionMap.get("ragQuery"),
                                        AiChatRagQueryDto.class
                                    );
        
        /* 임시 출력 문구 */
        System.out.println("산출된 백터: performance=" + ragQuery.performance() + ", value=" + ragQuery.value());

        /* recommender 호출 하여 추천 품목 리스트(견적 한 개) 가져오기 */
        List<Map<String, Object>> matchedParts =
                partMatchService.matchFullBuild(
                        Map.of(
                                "performance", ragQuery.performance(),
                                "value", ragQuery.value(),
                                "budget", budget
                        )
                );

        /* 추천 불가는 500 오류가 아니라 사용자에게 조건 변경을 요청하는 메시지로 반환한다 */
        if (matchedParts.isEmpty()) {
            return new AiChatResponseDto(
                    String.format(
                            Locale.KOREA,
                            "현재 예산 %,d원으로 Tool 검증 가능한 전체 견적을 찾지 못했습니다. 예산을 높여 다시 요청해 주세요.",
                            budget
                    ),
                    sessionId,
                    "CONVERSATION",
                    List.of(),
                    List.of()
            );
        }

        /* Dto의 Part 품목 응답 객체에 집어넣기 */
        List<AiChatResponseDto.PartRecommendation> items =
            matchedParts.stream()
                    .map(part -> new AiChatResponseDto.PartRecommendation(
                            text(part.get("id")),
                            text(part.get("category")),
                            text(part.get("name")),
                            text(part.get("manufacturer")),
                            ((Number) part.get("price")).intValue(),
                            Map.of(
                                    "performanceScore",
                                    part.get("performance_score"),
                                    "valueScore",
                                    part.get("value_score"),
                                    "matchScore",
                                    part.get("match_score")
                            )
                    ))
                    .toList();

        /* 총 가격 산출하기 */
        int estimatedTotalPrice = items.stream()
            .mapToInt(AiChatResponseDto.PartRecommendation::price)
            .sum();

        /* BuildRecommendation 하위 응답 객체 생성하기 */
        AiChatResponseDto.BuildRecommendation recommendation =
            new AiChatResponseDto.BuildRecommendation(
                    "성능·가성비 선호 기반",
                    estimatedTotalPrice,
                    items
            );

        return new AiChatResponseDto(
                message,
                sessionId,
                AiChatIntent.FULL_BUILD_RECOMMEND.name(),
                List.of(recommendation),
                List.of()
        );
    }

    /* 특정 부품만 추천하기: recommender 사용 */
    private AiChatResponseDto partRecommendResponse(
            String message,
            Map<String, Object> actionMap,
            Map<String, Object> context,
            String sessionId
    ) {
        /* 품목명 찾기 + 예산 가져오기 + ragQuery 가져오기 */
        String category = text(actionMap.get("selectedCategory"));  
        int budget = ((Number) context.get("budget")).intValue();      
        AiChatRagQueryDto ragQuery = OBJECT_MAPPER.convertValue(
                                        actionMap.get("ragQuery"),
                                        AiChatRagQueryDto.class
                                    );
        
        /* 임시 디버깅 */
        System.out.println("산출된 백터: performance=" + ragQuery.performance() + ", value=" + ragQuery.value());

        /* Recommeder 호출: 단일 품목에서 찾아내기 */
        List<Map<String, Object>> matchedParts =
                partMatchService.matchParts(
                        category,
                        Map.of(
                                "performance", ragQuery.performance(),
                                "value", ragQuery.value(),
                                "budget", budget
                        ),
                        2
                );

        /* 하위 응답 객체 생성하기 */
        List<AiChatResponseDto.PartRecommendation> recommendations =
                matchedParts.stream()
                        .map(part ->
                                new AiChatResponseDto.PartRecommendation(
                                        text(part.get("id")),
                                        text(part.get("category")),
                                        text(part.get("name")),
                                        text(part.get("manufacturer")),
                                        ((Number) part.get("price")).intValue(),
                                        Map.of(
                                                "performanceScore",
                                                part.get("performance_score"),
                                                "valueScore",
                                                part.get("value_score"),
                                                "matchScore",
                                                part.get("match_score")
                                        )
                                )
                        )
                        .toList();

        return new AiChatResponseDto(
                message,
                sessionId,
                AiChatIntent.PART_RECOMMEND.name(),
                List.of(),
                recommendations
        );
    }

    private AiChatResponseDto buildModifyResponse(String message, String selectedCategory, Map<String, Object> context, Map<String, Object> draftEdit, String sessionId) {
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
        return new AiChatResponseDto(
                buildModifyMessage(effectiveCategory, priceDirection, currentItem, candidates),
                sessionId,
                AiChatIntent.BUILD_MODIFY.name(),
                List.of(),
                candidates
        );
    }

    private PartReplacementRanker.SelectionResult draftEditPartRecommendations(
            String category,
            Map<String, Object> currentItem,
            Map<String, Object> context,
            String priceDirection,
            Integer targetMaxPrice,
            int limit
    ) {
        List<AiChatResponseDto.PartRecommendation> parts = null;
        return partReplacementRanker.select(category, currentItem, priceDirection, targetMaxPrice, parts, limit);
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

    private static int defaultQuantity(String category) {
        return "RAM".equals(category) || "STORAGE".equals(category) ? 2 : 1;
    }
}
