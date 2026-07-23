package com.buildgraph.prototype.aichat.chat;

import static com.buildgraph.prototype.aichat.chat.AiChatUtil.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.agent.AiProfileConfig;
import com.buildgraph.prototype.agent.AiProfileDefinition;
import com.buildgraph.prototype.agent.LlmResponseResult;
import com.buildgraph.prototype.aichat.chat.dto.AiChatRagQueryDto;
import com.buildgraph.prototype.aichat.chat.dto.AiChatRequestDto;
import com.buildgraph.prototype.aichat.chat.dto.AiChatResponseDto;
import com.buildgraph.prototype.aichat.chat.schema.AiChatOutputSchema;
import com.buildgraph.prototype.aichat.query.AiChatSessionQuery;
import com.buildgraph.prototype.aichat.query.AiChatSessionState;
import com.buildgraph.prototype.quote.QuoteDraftQueryService;
import com.buildgraph.prototype.recommender.matching.PartMatchService;
import com.fasterxml.jackson.databind.ObjectMapper;


@Service
public class AiChatEngine {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BUILD_CHAT_SCHEMA_NAME = "buildgraph_ai_build_chat_plan";

    private final AiChatClient aiChatClient;
    private final AiProfileConfig aiProfileConfig;
    private final AiChatSessionQuery aiChatSessionStore;
    private final PartMatchService partMatchService;
    private final QuoteDraftQueryService quoteDraftQueryService;

    public AiChatEngine(
            AiChatClient aiChatClient,
            AiProfileConfig aiProfileConfig,
            AiChatSessionQuery aiChatSessionStore,
            PartMatchService partMatchService,
            QuoteDraftQueryService quoteDraftQueryService
    ) {
        this.aiChatClient = aiChatClient;
        this.aiProfileConfig = aiProfileConfig;
        this.aiChatSessionStore = aiChatSessionStore;
        this.partMatchService = partMatchService;
        this.quoteDraftQueryService = quoteDraftQueryService;
    }

    /* 프롬프트 및 카테고리 객체 */
    private static final String BUILD_CHAT_SYSTEM_PROMPT = """
        당신은 컴퓨터 견적 상담 챗봇이다.
        사용자의 대화를 듣고 "대화모드(conversationMode)" 혹은 "행동모드" 중 하나를 결정한다.
        기존 context와 현재 rawMessage를 함께 판단한다.
        contextPatch.budget은 현재 추천 대상의 예산이며, rawMessage에 새 예산이 있으면 원 단위 정수로 넣고 없으면 null로 둔다.
        대화모드에서는 action은 null, contextPatch에는 맥락을 쌓는다.
        
        행동모드 전환 조건:
        - FULL_BUILD_RECOMMEND: budget과 usageTags(2개 이상)가 모두 존재해야 한다.
        - PART_RECOMMEND: selectedCategory와 budget, usageTags(1개 이상) 존재해야 한다.
        - BUILD_MODIFY: selectedCategory와 현재 견적이 필요하다.
        조건이 부족하면 conversationMode=true로 추가 질문한다.
        
        행동모드에서는 사용자의 의도에 따라 action.type을 FULL_BUILD_RECOMMEND, PART_RECOMMEND, BUILD_MODIFY 중 하나로 설정한다.
        행동모드에서의 action의 ragQuery에는 performance와 value에 0~1 사이의 값을 넣는다.
        contextPatch를 바탕으로 성능과 가성비를 얼마나 중시하는지 0~1 사이의 값이다.

        현재 견적의 부품 교체 요청이면 BUILD_MODIFY를 선택한다.
        currentDraft.exists가 false이면 먼저 셀프 견적에 부품을 담도록 안내한다
        
        selectedCategory가 불분명하면 행동모드로 전환하지 말고 추가 질문한다.
        replyMessage는 사용자에게 자세한 설명을 해야 하며, 30~100자 사이로 한다.
        """;

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
        AiChatSessionState session = aiChatSessionStore.findOrCreate(request.userInternalId());
        String sessionId = session.sessionId();

        AiProfileDefinition buildProfile = requireBuildChatProfile(aiProfileConfig, selectedAi);
        Map<String, Object> llmResponse;

        /* 현재 사용자 견적을 조회한 후 문맥에 추가 */
        Map<String, Object> llmContext = new LinkedHashMap<>(session.context());
        Map<String, Object> currentDraft = quoteDraftQueryService.currentByUserId(request.userInternalId());
        llmContext.put("currentDraft", summarizeDraft(currentDraft));

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
            System.out.println("대화모드: " + conversationMode);
            return conversationResponse(replyMessage, newContextPatch, sessionId, request.userInternalId());
        }

        /* 행동 모드에서도 현재 요청에서 추출된 예산과 용도를 세션에 반영 */
        aiChatSessionStore.updateSession(request.userInternalId(), newContextPatch);
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
            case BUILD_MODIFY -> buildModifyResponse(replyMessage, actionMap, currentContext, sessionId, 
                                            request.userInternalId());
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
        LlmResponseResult result = aiChatClient.generateLLMresponse(
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
            String sessionId,
            Long userInternalId) {
        /* Spring에 context를 update */
        aiChatSessionStore.updateSession(userInternalId, newContextPatch);

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

        /* Dto의 응답 객체 생성하기 */
        AiChatResponseDto.BuildRecommendation recommendation =
                AiChatUtil.toBuildRecommendation(
                        "성능·가성비 선호 기반",
                        matchedParts
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

     /* 현재 견적에서 특정 부품만 수정하기: recommender 사용 */
    private AiChatResponseDto buildModifyResponse(
        String message,
        Map<String, Object> actionMap,
        Map<String, Object> context,
        String sessionId,
        Long userInternalId
    ) {
        
        /* 사용자의 '장바구니' 찾기 => 8개 부품 가져오기 */
        Map<String, Object> currentDraft = quoteDraftQueryService.currentByUserId(userInternalId);
        List<Map<String, Object>> currentParts = objectMaps(currentDraft.get("items"));

        /* 이하 동일 */
        String category = text(actionMap.get("selectedCategory"));  
        int budget = ((Number) context.get("budget")).intValue();
        AiChatRagQueryDto ragQuery = OBJECT_MAPPER.convertValue(
                                        actionMap.get("ragQuery"),
                                        AiChatRagQueryDto.class
                                    );

        /* 임시 디버깅 */
        System.out.println("산출된 백터: performance=" + ragQuery.performance() + ", value=" + ragQuery.value());

        /* Recommeder 호출
           : 품목명, 현재 견적, (ragQuery + 예산안) => 부품 하나 반환 */
        Map<String, Object> selectedPart =
                partMatchService.matchReplacement(
                        category,
                        currentParts,
                        Map.of(
                                "performance", ragQuery.performance(),
                                "value", ragQuery.value(),
                                "budget", budget
                        ),
                        budget
                );
        
        /* 수정할 견적 객체: 일단 기존 것 복붙 => 이후 교체 */
        List<Map<String, Object>> modifiedParts = new ArrayList<>(currentParts);  
        modifiedParts.removeIf(part -> category.equals(part.get("category")));
        modifiedParts.add(selectedPart); 
        
        /* 하위 응답객체 생성하기 */
        AiChatResponseDto.BuildRecommendation modified =
                AiChatUtil.toBuildRecommendation(
                        category + " 교체 견적",
                        modifiedParts
                );       

        return new AiChatResponseDto(
                message,
                sessionId,
                AiChatIntent.BUILD_MODIFY.name(),
                List.of(modified),
                List.of()
        );
    }

    /* ==============보조 함수============== */
    /* draft 객체 요약해서 전달 */
        private Map<String, Object> summarizeDraft(Map<String, Object> draft) {
        List<Map<String, Object>> items = objectMaps(draft.get("items"));

        return MockData.map(
                "exists", !items.isEmpty(),
                "items", items.stream()
                        .map(item -> MockData.map(
                                "category", item.get("category"),
                                "name", item.get("name"),
                                "manufacturer", item.get("manufacturer")
                        ))
                        .toList()
        );
}
}
