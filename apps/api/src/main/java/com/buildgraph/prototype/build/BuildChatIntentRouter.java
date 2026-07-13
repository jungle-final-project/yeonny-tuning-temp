package com.buildgraph.prototype.build;

import com.buildgraph.prototype.agent.PartRouteResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Build Chat 축소 정책(2026-07 회의)의 intent 라우터.
 * 지원 범위는 예산/그래프 기반 견적 추천, 부품 교체 성능 시뮬레이션, 셀프 견적 보드 위치 강조,
 * 명확화 질문이다. 화면 이동, 장바구니 조작, 단일 부품 추천, 일반 상담은 UNSUPPORTED로 고정 안내한다.
 *
 * 분기 순서가 오탐 방어의 핵심이다:
 * 시뮬레이션 → 견적 완성 → 장바구니 조작 veto → 주변기기 veto → 견적 추천 → 보드 위치 → 명확화 → UNSUPPORTED
 */
@Service
public class BuildChatIntentRouter {
    // 본체(완성 PC)를 가리키는 강한 명사 — 부품 한정 질문과 견적 요청을 구분하는 기준
    private static final String[] CORE_BUILD_NOUNS = {
            "pc", "피시", "컴퓨터", "본체", "데스크탑", "데스크톱", "워크스테이션", "조립컴", "조립pc", "견적", "머신"
    };
    private static final Pattern GPU_MODEL_SIGNAL = Pattern.compile(
            "(?i)(?:rtx|geforce|지포스|rx)?\\s*(?:40[6-9]0|50[6-9]0|6[4-9]\\d0|7[4-9]\\d0|9[0-9]\\d0)(?:\\s*(?:ti|super|xt|xtx))?"
    );
    private static final Pattern CPU_MODEL_SIGNAL = Pattern.compile(
            "(?i)(?:\\d{4,5}x3d|\\d{4,5}(?:x|xt|g)|i[3579]-?\\d{4,5}(?:k|kf|f|ks)?|(?:core)?ultra[3579]?\\d{3}k|\\d{3}k)"
    );
    private static final List<LocationCategoryKeywords> LOCATION_CATEGORIES = List.of(
            new LocationCategoryKeywords("MOTHERBOARD", List.of("메인보드", "마더보드", "motherboard")),
            new LocationCategoryKeywords("COOLER", List.of("쿨러", "cooler", "수랭", "공랭")),
            new LocationCategoryKeywords("STORAGE", List.of("m.2", "m2슬롯", "ssd", "스토리지", "저장장치", "nvme")),
            new LocationCategoryKeywords("PSU", List.of("파워", "psu", "전원공급장치", "전원공급")),
            new LocationCategoryKeywords("CASE", List.of("케이스", "case")),
            new LocationCategoryKeywords("GPU", List.of("그래픽카드", "그래픽 카드", "글카", "지피유", "gpu", "vga")),
            new LocationCategoryKeywords("CPU", List.of("씨피유", "씨퓨", "cpu", "프로세서")),
            new LocationCategoryKeywords("RAM", List.of("dimm", "메모리", "memory", "ram", "램"))
    );

    public BuildChatIntentDecision decide(Map<String, Object> request, String message) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String normalized = normalize(message);
        String category = firstText(text(body.get("selectedCategory")), BuildChatService.detectPartCategory(message), PartRouteResolver.inferCategory(message));
        String partQuery = PartRouteResolver.extractPartQuery(message);
        boolean hasDraftItems = !objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty();

        if (isSimulationIntent(normalized, category)) {
            List<String> reasons = new ArrayList<>();
            if (partQuery == null && category != null) {
                reasons.add("SIMULATION_TARGET_MAY_BE_AMBIGUOUS");
            }
            return decision(BuildChatIntent.SIMULATE_REPLACEMENT, "HIGH", "NONE", category, partQuery, "FAST_SIMULATION", "NONE",
                    semanticSignature(BuildChatIntent.SIMULATE_REPLACEMENT, category, partQuery, null), reasons);
        }

        if (isDraftCompletionIntent(normalized, hasDraftItems)) {
            return decision(BuildChatIntent.BUILD_RECOMMEND, "HIGH", "NONE", category, partQuery, "LLM_OR_DETERMINISTIC",
                    "EXACT_ONLY",
                    semanticSignature(BuildChatIntent.BUILD_RECOMMEND, category, partQuery, budgetSignature(message)), List.of());
        }

        // 장바구니 조작 명령은 그래프 UI 담당 — "이 구성에서 램만 올려줘" 같은 문장이
        // "구성" 명사 때문에 견적 추천으로 새는 것을 막기 위해 견적 분기보다 먼저 확인한다
        if (isMutationCommand(normalized)) {
            return unsupported(category, partQuery);
        }

        // 모니터/노트북 등 취급하지 않는 완제품·주변기기 요청 (모니터 미정 맥락은 명확화로 보냄)
        if (isPeripheralRequest(normalized) && !isMissingMonitorContext(normalized)) {
            return unsupported(category, partQuery);
        }

        if (isBuildRecommend(normalized, message, category)) {
            return decision(BuildChatIntent.BUILD_RECOMMEND, "HIGH", "NONE", category, partQuery, "LLM_OR_DETERMINISTIC",
                    standaloneContext(body) ? "SEMANTIC_READ_ONLY" : "EXACT_ONLY",
                    semanticSignature(BuildChatIntent.BUILD_RECOMMEND, category, partQuery, budgetSignature(message)), List.of());
        }

        BuildChatIntentDecision boardLocation = boardLocationDecision(body, normalized, partQuery);
        if (boardLocation != null) {
            return boardLocation;
        }

        if (isBuildScoreExplanation(body, message, normalized, category)) {
            return decision(
                    BuildChatIntent.EXPLAIN_BUILD_SCORE,
                    "HIGH",
                    "NONE",
                    category,
                    partQuery,
                    "LIVE_BUILD_ASSESSMENT",
                    "NONE",
                    null,
                    List.of()
            );
        }

        // 화면 이동/탐색과 설명 요청은 축소 정책상 미지원 — 모호 구매의향(명확화)으로 흡수되지 않게 먼저 자른다
        if (isNavigationCommand(normalized) || isExplanationQuestion(normalized)) {
            return unsupported(category, partQuery);
        }

        if (normalized.isBlank()
                || containsAny(normalized, "컴퓨터하나", "아무거나", "뭐사지")
                || isMissingMonitorContext(normalized)
                || isVaguePurchaseIntent(message, normalized, category)) {
            List<String> clarificationReasons = new ArrayList<>(List.of("LOW_INFORMATION"));
            // "해상도 좋은"처럼 해상도 언급만 있고 FHD/QHD/4K가 특정되지 않은 요청은 해상도 되묻기로 특화한다
            if (containsAny(normalized, "해상도", "화질")) {
                clarificationReasons.add("RESOLUTION_CONTEXT");
            }
            if (hasBuildUseCaseSignal(normalized) && BuildChatService.parseBudgetWon(message) == null) {
                clarificationReasons.add("USAGE_ONLY");
            }
            return decision(BuildChatIntent.ASK_CLARIFICATION, "LOW", "NONE", category, partQuery, "FAST_CLARIFICATION", "NONE", null,
                    clarificationReasons);
        }

        return unsupported(category, partQuery);
    }

    private static boolean isNavigationCommand(String normalized) {
        return containsAny(normalized, "열어", "보여", "이동", "화면", "페이지", "목록", "상세");
    }

    private static boolean isExplanationQuestion(String normalized) {
        return containsAny(normalized, "왜", "이유", "설명", "호환", "괜찮아", "병목");
    }

    private static boolean isBuildScoreExplanation(
            Map<String, Object> body,
            String message,
            String normalized,
            String category
    ) {
        Map<String, Object> assessmentContext = objectMap(body.get("assessmentContext"));
        boolean explicitAssessmentContext = "QUOTE_DRAFT_CURRENT".equalsIgnoreCase(text(assessmentContext.get("source")))
                && ("SCORE".equalsIgnoreCase(text(assessmentContext.get("focusType")))
                        || "ISSUE".equalsIgnoreCase(text(assessmentContext.get("focusType"))));
        if (explicitAssessmentContext) {
            return true;
        }

        boolean currentBuildSignal = containsAny(normalized,
                "현재견적", "이견적", "담긴견적", "내견적", "지금견적", "현재구성", "이구성", "지금구성");
        boolean scoreSignal = containsAny(normalized,
                "종합점수", "총점", "이점수", "점수가", "점수왜", "점수설명", "점수낮", "점수높");
        boolean weaknessSignal = containsAny(normalized,
                "병목", "약점", "부족한부분", "아쉬운부분", "문제점", "균형", "밸런스");
        boolean prioritySignal = containsAny(normalized,
                "뭐부터업그레이드", "무엇부터업그레이드", "뭘먼저업그레이드", "업그레이드우선", "먼저바꿀");
        boolean cpuGpuContrast = containsAny(normalized, "cpu", "씨피유", "프로세서")
                && containsAny(normalized, "gpu", "그래픽카드", "글카")
                && containsAny(normalized, "왜", "이유", "낮", "높", "차이", "균형", "밸런스");

        if (!(scoreSignal || weaknessSignal || prioritySignal || (currentBuildSignal && isExplanationQuestion(normalized)) || cpuGpuContrast)) {
            return false;
        }
        // 단일 상품의 독립 점수 질문은 현재 견적 1000점 설명으로 가져오지 않는다.
        return currentBuildSignal
                || scoreSignal && !hasSpecificPartSignal(message)
                || weaknessSignal
                || prioritySignal
                || cpuGpuContrast
                || category == null;
    }

    // 구매 의향/견적 관심은 있지만 예산·용도가 없는 요청 — 차단 대신 되묻기로 대화를 잇는다.
    // 부품 카테고리가 특정되거나(단일 부품 추천은 미지원) 모델 번호가 있으면 제외한다.
    private static boolean isVaguePurchaseIntent(String message, String normalized, String category) {
        if (category != null || hasSpecificPartSignal(message)) {
            return false;
        }
        return isRecommendationVerb(normalized)
                || hasBuildNoun(normalized)
                || isLowInfoPurchaseIntent(normalized);
    }

    private static BuildChatIntentDecision unsupported(String category, String partQuery) {
        return decision(BuildChatIntent.UNSUPPORTED, "HIGH", "NONE", category, partQuery, "FAST_UNSUPPORTED", "NONE", null,
                List.of());
    }

    private static BuildChatIntentDecision decision(
            BuildChatIntent intent,
            String confidence,
            String sideEffectRisk,
            String category,
            String partQuery,
            String preferredPath,
            String cachePolicy,
            String semanticConstraintSignature,
            List<String> ambiguityReasons
    ) {
        return new BuildChatIntentDecision(intent, confidence, sideEffectRisk, category, partQuery, preferredPath, cachePolicy,
                semanticConstraintSignature, ambiguityReasons == null ? List.of() : ambiguityReasons);
    }

    private static BuildChatIntentDecision decision(
            BuildChatIntent intent,
            String confidence,
            String sideEffectRisk,
            List<String> categories,
            String partQuery,
            String preferredPath,
            String cachePolicy,
            String semanticConstraintSignature,
            List<String> ambiguityReasons
    ) {
        List<String> safeCategories = categories == null ? List.of() : List.copyOf(categories);
        String category = safeCategories.isEmpty() ? null : safeCategories.get(0);
        return new BuildChatIntentDecision(
                intent,
                confidence,
                sideEffectRisk,
                category,
                partQuery,
                preferredPath,
                cachePolicy,
                semanticConstraintSignature,
                ambiguityReasons,
                safeCategories
        );
    }

    private static BuildChatIntentDecision boardLocationDecision(
            Map<String, Object> body,
            String normalized,
            String partQuery
    ) {
        if (!supportsBoardPartFocus(body) || !hasBoardLocationSignal(normalized) || hasBoardLocationVeto(normalized)) {
            return null;
        }
        List<String> categories = locationCategories(normalized);
        if (!categories.isEmpty()) {
            return decision(
                    BuildChatIntent.LOCATE_BOARD_PART,
                    "HIGH",
                    "NONE",
                    categories,
                    partQuery,
                    "FAST_BOARD_FOCUS",
                    "NONE",
                    null,
                    List.of()
            );
        }
        return decision(
                BuildChatIntent.LOCATE_BOARD_PART,
                "MEDIUM",
                "NONE",
                categories,
                partQuery,
                "LLM_BOARD_FOCUS",
                "EXACT_ONLY",
                null,
                List.of("BOARD_FOCUS_CATEGORY_MISSING")
        );
    }

    private static boolean supportsBoardPartFocus(Map<String, Object> body) {
        Map<String, Object> uiContext = objectMap(body.get("uiContext"));
        if (!"SELF_QUOTE".equalsIgnoreCase(firstText(text(uiContext.get("surface")), ""))) {
            return false;
        }
        return stringList(uiContext.get("capabilities")).stream()
                .anyMatch(capability -> "BOARD_PART_FOCUS".equalsIgnoreCase(capability));
    }

    private static boolean hasBoardLocationSignal(String normalized) {
        return containsAny(normalized,
                "위치", "어디", "어딜", "어느곳", "어디쯤", "자리", "슬롯", "꽂는곳", "꽂을곳", "장착하는곳", "장착할곳",
                "가리켜", "표시해", "강조해", "찾아", "짚어", "어느부분", "어느쪽", "어디에달려", "어디에장착");
    }

    private static boolean hasBoardLocationVeto(String normalized) {
        return containsAny(normalized,
                "추천", "후보", "최저가", "가격", "구매", "판매", "어디서사", "어디서구해", "쇼핑",
                "바꿔", "교체", "빼줘", "삭제", "제거", "담아", "넣어줘", "추가해", "수량",
                "프레임", "fps", "성능", "벤치", "비교", "업그레이드", "다운그레이드",
                "상세페이지", "상품페이지", "제품페이지", "목록");
    }

    private static List<String> locationCategories(String normalized) {
        List<LocationCategoryMatch> matches = new ArrayList<>();
        for (LocationCategoryKeywords check : LOCATION_CATEGORIES) {
            String searchText = locationCategorySearchText(normalized, check.category());
            int firstIndex = check.keywords().stream()
                    .map(BuildChatIntentRouter::normalize)
                    .mapToInt(searchText::indexOf)
                    .filter(index -> index >= 0)
                    .min()
                    .orElse(-1);
            if (firstIndex >= 0) {
                matches.add(new LocationCategoryMatch(check.category(), firstIndex));
            }
        }
        if (matches.isEmpty() && normalized.contains("보드")) {
            matches.add(new LocationCategoryMatch("MOTHERBOARD", normalized.indexOf("보드")));
        }
        return matches.stream()
                .sorted(java.util.Comparator.comparingInt(LocationCategoryMatch::index))
                .map(LocationCategoryMatch::category)
                .distinct()
                .toList();
    }

    private static String locationCategorySearchText(String normalized, String category) {
        if (!"CPU".equals(category)) {
            return normalized;
        }
        return normalized
                .replace("cpu쿨러", "쿨러")
                .replace("cpucooler", "cooler")
                .replace("씨피유쿨러", "쿨러")
                .replace("씨퓨쿨러", "쿨러")
                .replace("프로세서쿨러", "쿨러")
                .replace("cpu수랭", "수랭")
                .replace("cpu공랭", "공랭");
    }

    private static boolean isSimulationIntent(String normalized, String category) {
        boolean whatIf = containsAny(normalized,
                "바꾸면", "바꿨을때", "바꿔버리면", "교체하면", "교체시", "교체했을때", "교체할때",
                "넣으면", "달면", "끼우면", "끼면", "박으면", "꽂으면", "올리면", "올렸을때", "내리면", "내려도", "낮추면",
                "늘리면", "줄이면", "갈아타면", "갈아끼우면", "넘어가면", "업그레이드하면", "다운그레이드하면", "으로가면", "로가면");
        boolean impact = containsAny(normalized,
                "프레임", "fps", "성능", "벤치", "얼마나", "어떻게되", "어떻게", "어떨", "차이", "향상",
                "체감", "달라지", "깎여", "깎이", "떡상", "떡락", "어때", "나아", "잘돌아가", "뛰어", "올라가",
                "빨라", "느려", "쾌적", "개선", "가성비");
        boolean shortWhatIfQuestion = whatIf && (normalized.endsWith("?") || normalized.endsWith("면") || containsAny(normalized, "좋을까", "나을까", "나올까"));
        if (whatIf && (impact || shortWhatIfQuestion)) {
            return true;
        }
        // 가정형 동사 없이 "비교" 요청으로 들어오는 교체 성능 질문 ("그래픽카드 교체 성능 비교 좀")
        boolean comparisonAsk = normalized.contains("비교")
                && (category != null || containsAny(normalized, "교체", "바꾸", "바꿔", "업그레이드", "다운그레이드", "단계"));
        return comparisonAsk;
    }

    // 장바구니(드래프트) 조작 명령 — 챗봇이 아니라 그래프 UI 담당.
    // "뭐부터 넣어야 돼?" 같은 시작 질문은 조작 명령이 아니므로 제외한다.
    private static boolean isMutationCommand(String normalized) {
        if (containsAny(normalized, "뭐부터", "뭘부터", "무엇부터")) {
            return false;
        }
        return containsAny(normalized,
                "바꿔줘", "바꿔주", "바꿔놔", "교체해줘", "교체해주", "빼줘", "빼주", "빼버려", "삭제", "제거",
                "담아줘", "담아", "넣어줘", "넣어", "추가해", "올려줘", "올려주", "내려줘", "낮춰줘", "줄여줘", "늘려줘", "수량");
    }

    // 모니터/노트북 등 부품 DB 취급 범위 밖 완제품·주변기기
    private static boolean isPeripheralRequest(String normalized) {
        return containsAny(normalized, "모니터", "노트북", "랩탑", "키보드", "마우스", "헤드셋", "스피커")
                && !containsAny(normalized, CORE_BUILD_NOUNS);
    }

    private static boolean isRecommendationVerb(String normalized) {
        // "구성"은 mutation veto("이 구성에서 램만 올려줘")가 먼저 걸러주기 때문에 동사로 안전하게 쓸 수 있다
        return containsAny(normalized,
                "추천", "맞춰", "맞추", "짜줘", "짜주", "짜봐", "구성", "골라", "뽑아", "조립해", "조립하",
                "만들어", "구해줘", "세팅", "내줘", "부탁", "필요", "알려줘", "사고싶", "사면", "원해", "가보자",
                "봐줘", "봐주", "알아봐", "찾아줘", "찾아주", "살펴줘", "살펴주");
    }

    private static boolean hasBuildNoun(String normalized) {
        return containsAny(normalized, CORE_BUILD_NOUNS) || containsAny(normalized, "사양", "조합", "구성");
    }

    private static boolean isBuildRecommend(String normalized, String message, String category) {
        Integer budget = BuildChatService.parseBudgetWon(message);
        boolean budgetWithCompositionConstraint = budget != null && hasBuildCompositionConstraint(normalized);
        boolean budgetWithUseCase = budget != null && hasBuildUseCaseSignal(normalized);
        boolean recommendVerb = isRecommendationVerb(normalized);
        boolean openBudget = hasOpenBudgetSignal(normalized);
        boolean openBudgetMultiPartRecommend = openBudget && recommendVerb && hasMultiplePartCategories(normalized);
        boolean categoryBoundBuildUseCase = budgetWithUseCase && budget >= 1_000_000 && !recommendVerb;
        // 부품 카테고리가 특정되고 본체 신호가 없으면 부품 한정 질문이다
        // ("쿨러 추천해줘", "30만원대 CPU 골라줘" — 예산이 있어도 단일 부품 추천은 미지원)
        if (category != null
                && !containsAny(normalized, CORE_BUILD_NOUNS)
                && !budgetWithCompositionConstraint
                && !openBudgetMultiPartRecommend
                && !categoryBoundBuildUseCase) {
            return false;
        }
        // 동사+본체 명사만으로는("피시 맞춰줘") 견적을 세울 근거가 없다 — 용도/예산/구체 모델 번호 중
        // 예산 또는 구체 모델 번호가 있어야 추천으로 보내고, 용도만 있으면 예산을 되묻는다.
        // 단, 예산 무관/끝판왕처럼 open budget을 명시한 경우는 즉시 추천한다.
        boolean specificPartSignal = hasSpecificPartSignal(message);
        boolean explicitRecommend = recommendVerb
                && (budget != null || specificPartSignal || openBudget);
        boolean budgetWithBuildNoun = budget != null && hasBuildNoun(normalized);
        boolean openBudgetWithBuildNoun = openBudget && hasBuildNoun(normalized);
        boolean openBudgetWithUseCase = openBudget && hasBuildUseCaseSignal(normalized);
        // "2500만원 잡았는데 뭐부터 넣어야 돼?" — 예산이 명확하면 모호한 의향도 견적 추천으로 흡수한다
        boolean budgetWithPurchaseIntent = budget != null && isLowInfoPurchaseIntent(normalized);
        return explicitRecommend || budgetWithUseCase || budgetWithBuildNoun || openBudgetWithBuildNoun
                || openBudgetWithUseCase || openBudgetMultiPartRecommend
                || budgetWithCompositionConstraint || budgetWithPurchaseIntent;
    }

    private static boolean hasBuildCompositionConstraint(String normalized) {
        if (containsAny(normalized, "포함", "들어간", "들어가는", "장착", "조합")) {
            return true;
        }
        return hasMultiplePartCategories(normalized);
    }

    private static boolean hasMultiplePartCategories(String normalized) {
        int categories = 0;
        categories += containsAny(normalized, "cpu", "씨피유", "라이젠", "ryzen", "인텔코어", "coreultra") ? 1 : 0;
        categories += containsAny(normalized, "메인보드", "마더보드", "보드") ? 1 : 0;
        categories += containsAny(normalized, "ram", "램", "메모리") ? 1 : 0;
        categories += containsAny(normalized, "gpu", "그래픽카드", "글카", "지포스", "rtx", "라데온") ? 1 : 0;
        categories += containsAny(normalized, "ssd", "저장장치", "nvme") ? 1 : 0;
        categories += containsAny(normalized, "psu", "파워", "전원") ? 1 : 0;
        categories += containsAny(normalized, "케이스", "case") ? 1 : 0;
        categories += containsAny(normalized, "쿨러", "cooler", "수랭", "공랭") ? 1 : 0;
        return categories >= 2;
    }

    private static boolean hasOpenBudgetSignal(String normalized) {
        return containsAny(normalized,
                "예산무관", "예산상관없이", "돈상관없이", "가격제한없이", "가격상관없이", "가격보다성능",
                "끝판왕", "최고사양", "최고급", "최상급", "하이엔드", "풀스펙", "최강", "괴물");
    }

    private static boolean hasSpecificPartSignal(String message) {
        String compact = message == null ? "" : message.replaceAll("\\s+", "");
        return GPU_MODEL_SIGNAL.matcher(compact).find() || CPU_MODEL_SIGNAL.matcher(compact).find();
    }

    private static boolean isDraftCompletionIntent(String normalized, boolean hasDraftItems) {
        // "이 견적 나머지는 다 빼줘"처럼 제거 요청이 '나머지'만으로 완성 의도로 오인되지 않도록,
        // 삭제/제거 동사가 있으면 완성으로 보지 않고 아래 mutation veto(그래프 UI 담당)로 넘긴다.
        if (containsAny(normalized, "빼", "삭제", "제거", "없애", "지워", "remove", "delete")) {
            return false;
        }
        return hasDraftItems
                && containsAny(normalized, "채워", "완성", "나머지", "마저")
                && containsAny(normalized, "견적", "조합", "구성", "부품", "pc", "컴퓨터", "그래프");
    }

    private static boolean hasBuildUseCaseSignal(String normalized) {
        return containsAny(normalized,
                "영상", "편집", "프리미어", "블렌더", "렌더", "렌더팜", "개발", "docker", "도커", "ide", "코딩",
                "게임", "게이밍", "qhd", "fhd", "4k", "hz", "배그", "발로란트", "발로", "오버워치", "옵치",
                "사이버펑크", "로스트아크", "디아블로", "몬헌", "몬스터헌터", "배틀필드", "스타크래프트", "스타2",
                "롤", "리그오브레전드", "풀옵",
                "ai", "cuda", "로컬ai", "학습", "저소음", "조용", "컴팩트", "사무", "문서작업", "엑셀",
                "방송", "스트리밍", "송출", "스트리머", "유튜브", "포토샵", "디자인", "3d",
                "풀스펙", "최고사양", "하이엔드", "최상급", "끝판왕", "최강", "서버급", "괴물");
    }

    // 구매 의향은 있지만 예산/용도가 없는 모호한 요청 — 차단이 아니라 되묻기로 대화를 잇는다
    private static boolean isLowInfoPurchaseIntent(String normalized) {
        // 명백 저정보(무엇을 살지 모르는 신호)만 즉답 되묻기로 남긴다. 경계 어휘(고민/필요해/필요한데/추천좀/
        // 추천부탁/알아보/괜찮은거/적당한걸로/싸고좋은 등)는 되묻기로 가두지 않고 UNSUPPORTED로 흘려
        // LLM 강등(respondLlmRequired)이 맥락에 맞게 답하게 한다.
        return containsAny(normalized,
                "사고싶", "사려", "사야", "살까", "뭐사", "뭘사", "장만",
                "바꿀때", "뭐부터", "뭐가잘나가", "컴맹", "뭐가좋");
    }

    private static boolean isMissingMonitorContext(String normalized) {
        return containsAny(normalized, "모니터")
                && containsAny(normalized, "아직안", "안정", "못정", "미정", "모름");
    }

    private static boolean standaloneContext(Map<String, Object> body) {
        return objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()
                && objectMaps(body.get("currentBuilds")).isEmpty()
                && text(body.get("selectedCategory")) == null;
    }

    private static String semanticSignature(BuildChatIntent intent, String category, String partQuery, String budget) {
        return intent.name()
                + "|category=" + firstText(category, "ANY")
                + "|part=" + firstText(normalize(partQuery), "ANY")
                + "|budget=" + firstText(budget, "ANY");
    }

    private static String budgetSignature(String message) {
        BuildChatService.BudgetIntent budget = BuildChatService.budgetIntent(message);
        if (budget.budget() == null || budget.mode() == null) {
            return null;
        }
        return budget.mode() + ":" + budget.budget();
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static boolean containsAny(String normalized, String... needles) {
        for (String needle : needles) {
            if (normalized.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            String text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(BuildChatIntentRouter::text).filter(java.util.Objects::nonNull).toList();
        }
        return List.of();
    }

    private record LocationCategoryKeywords(String category, List<String> keywords) {
    }

    private record LocationCategoryMatch(String category, int index) {
    }
}
