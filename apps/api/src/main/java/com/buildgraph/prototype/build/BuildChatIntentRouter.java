package com.buildgraph.prototype.build;

import com.buildgraph.prototype.agent.PartRouteResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class BuildChatIntentRouter {
    public BuildChatIntentDecision decide(Map<String, Object> request, String message) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String normalized = normalize(message);
        String category = firstText(text(body.get("selectedCategory")), BuildChatService.detectPartCategory(message), PartRouteResolver.inferCategory(message));
        String partQuery = PartRouteResolver.extractPartQuery(message);
        boolean hasDraftItems = !objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty();
        boolean routeLike = isProductRouteIntent(normalized)
                && !isMutationIntent(normalized)
                && !isRecommendationIntent(normalized);

        if (routeLike && category != null && !hasProductQuerySignal(partQuery)) {
            BuildChatIntent intent = hasFilterDescriptor(partQuery) ? BuildChatIntent.FILTER_PART_SEARCH : BuildChatIntent.NAVIGATE_CATEGORY;
            String signature = intent == BuildChatIntent.FILTER_PART_SEARCH
                    ? semanticSignature(intent, category, partQuery, null)
                    : routeSignature(intent, category);
            return decision(intent, "HIGH", "NONE", category, intent == BuildChatIntent.FILTER_PART_SEARCH ? partQuery : null,
                    intent == BuildChatIntent.FILTER_PART_SEARCH ? "FAST_FILTER_ROUTE" : "FAST_ROUTE",
                    "NONE", signature, List.of());
        }

        if (routeLike && hasProductQuerySignal(partQuery)) {
            BuildChatIntent intent = isAmbiguousProductQuery(partQuery) ? BuildChatIntent.FILTER_PART_SEARCH : BuildChatIntent.NAVIGATE_PART_DETAIL;
            return decision(intent, "HIGH", "NONE", category, partQuery, "FAST_PART_ROUTE", "NONE", routeSignature(intent, category), List.of());
        }

        if (isStaticRoute(normalized)) {
            BuildChatIntent intent = category != null && hasRouteVerb(normalized)
                    ? BuildChatIntent.NAVIGATE_CATEGORY
                    : BuildChatIntent.NAVIGATE_STATIC;
            return decision(intent, "HIGH", "NONE", category, null, "FAST_ROUTE", "NONE", routeSignature(intent, category), List.of());
        }

        if (isSimulationIntent(normalized)) {
            List<String> reasons = new ArrayList<>();
            if (partQuery == null && category != null) {
                reasons.add("SIMULATION_TARGET_MAY_BE_AMBIGUOUS");
            }
            return decision(BuildChatIntent.SIMULATE_REPLACEMENT, "HIGH", "NONE", category, partQuery, "FAST_SIMULATION", "NONE",
                    semanticSignature(BuildChatIntent.SIMULATE_REPLACEMENT, category, partQuery, null), reasons);
        }

        if (hasDraftItems && isRemoveIntent(normalized)) {
            return decision(BuildChatIntent.MUTATE_DRAFT_REMOVE, "HIGH", "LOW", category, partQuery, "FAST_DRAFT_ACTION", "NONE",
                    null, List.of());
        }
        if (hasDraftItems && isQuantityIntent(normalized)) {
            return decision(BuildChatIntent.MUTATE_DRAFT_QUANTITY, "HIGH", "LOW", category, partQuery, "FAST_DRAFT_ACTION", "NONE",
                    null, List.of());
        }
        if (hasDraftItems && (isMutationIntent(normalized)
                || isReplacementPreferenceIntent(normalized)
                || isDraftCategoryReplacementIntent(normalized, category))) {
            BuildChatIntent intent = hasProductQuerySignal(partQuery) && !isBudgetAdjustmentIntent(normalized)
                    ? BuildChatIntent.MUTATE_DRAFT_REPLACE_EXACT
                    : BuildChatIntent.MUTATE_DRAFT_RECOMMEND;
            return decision(intent, "MEDIUM", "MEDIUM", category, partQuery, "FAST_DRAFT_ACTION", "NONE", null, List.of());
        }

        if (!hasDraftItems
                && category != null
                && !isBuildRecommend(normalized, message)
                && (isMutationIntent(normalized) || isDraftCategoryReplacementIntent(normalized, category))) {
            return decision(BuildChatIntent.MUTATE_DRAFT_RECOMMEND, "MEDIUM", "MEDIUM", category, partQuery,
                    "FAST_DRAFT_ACTION", "NONE", null, List.of());
        }

        if (isExplainIntent(normalized)) {
            return decision(BuildChatIntent.EXPLAIN_CURRENT, "MEDIUM", "NONE", category, partQuery, "LLM_FULL", "NONE",
                    semanticSignature(BuildChatIntent.EXPLAIN_CURRENT, category, partQuery, null), List.of());
        }

        if (isPartRecommend(normalized, category)) {
            return decision(BuildChatIntent.PART_RECOMMEND, "HIGH", "NONE", category, partQuery, "LLM_OR_DETERMINISTIC",
                    standaloneContext(body) ? "SEMANTIC_READ_ONLY" : "EXACT_ONLY",
                    semanticSignature(BuildChatIntent.PART_RECOMMEND, category, partQuery, budgetSignature(message)), List.of());
        }

        if (isBuildRecommend(normalized, message)) {
            return decision(BuildChatIntent.BUILD_RECOMMEND, "HIGH", "NONE", category, partQuery, "LLM_OR_DETERMINISTIC",
                    standaloneContext(body) ? "SEMANTIC_READ_ONLY" : "EXACT_ONLY",
                    semanticSignature(BuildChatIntent.BUILD_RECOMMEND, category, partQuery, budgetSignature(message)), List.of());
        }

        if (normalized.isBlank()
                || containsAny(normalized, "컴퓨터하나", "아무거나", "뭐사지")
                || isMissingMonitorContext(normalized)) {
            return decision(BuildChatIntent.ASK_CLARIFICATION, "LOW", "NONE", category, partQuery, "LLM_FULL", "NONE", null,
                    List.of("LOW_INFORMATION"));
        }

        return decision(BuildChatIntent.LLM_FULL, "MEDIUM", "NONE", category, partQuery, "LLM_FULL", "NONE", null, List.of());
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

    private static boolean isStaticRoute(String normalized) {
        if (hasCategoryOnlyRoute(normalized)) {
            return true;
        }
        return containsAny(normalized,
                "셀프견적", "수동견적", "견적장바구니", "내견적함", "견적함", "저장된견적", "견적목록",
                "ai견적", "자연어견적", "요구사항", "견적입력", "as접수", "수리접수", "지원접수", "고장접수",
                "as챗봇", "asai", "수리챗봇", "지원챗봇", "구매하기", "결제", "checkout"
        ) && !isMutationIntent(normalized);
    }

    private static boolean hasCategoryOnlyRoute(String normalized) {
        return hasRouteVerb(normalized)
                && !isProductDetailIntent(normalized)
                && !isRecommendationIntent(normalized)
                && !isMutationIntent(normalized)
                && containsAny(normalized,
                "gpu", "그래픽카드", "그래픽", "글카", "vga", "cpu", "씨피유", "프로세서",
                "메인보드", "마더보드", "보드", "ram", "램", "메모리", "ssd", "스토리지",
                "저장장치", "파워", "psu", "케이스", "쿨러", "cooler");
    }

    private static boolean isProductRouteIntent(String normalized) {
        return hasRouteVerb(normalized) || isProductDetailIntent(normalized);
    }

    private static boolean hasRouteVerb(String normalized) {
        return containsAny(normalized, "보여줘", "열어줘", "이동", "가줘", "어디", "화면", "페이지");
    }

    private static boolean isProductDetailIntent(String normalized) {
        return containsAny(normalized, "상세", "상세페이지", "상품페이지", "제품페이지");
    }

    private static boolean isSimulationIntent(String normalized) {
        boolean whatIf = containsAny(normalized, "바꾸면", "교체하면", "넣으면", "달면", "변경하면", "업그레이드하면", "으로가면", "로가면");
        boolean impact = containsAny(normalized, "프레임", "fps", "성능", "벤치", "얼마나", "어떻게되", "어떻게", "어떨", "차이", "향상");
        boolean shortWhatIfQuestion = whatIf && (normalized.endsWith("?") || normalized.endsWith("면") || containsAny(normalized, "좋을까", "나을까"));
        return whatIf && (impact || shortWhatIfQuestion);
    }

    private static boolean isMutationIntent(String normalized) {
        return containsAny(normalized, "바꿔", "교체", "빼줘", "빼", "삭제", "제거", "담아", "넣어", "추가", "적용", "수량", "변경해줘");
    }

    private static boolean isReplacementPreferenceIntent(String normalized) {
        return containsAny(normalized,
                "더좋은", "상위", "업그레이드", "고급", "더싼", "저렴", "예산낮", "가격낮", "비슷한가격",
                "더빠른", "더여유", "여유있", "더작은", "작은걸", "더큰", "큰것도", "더조용", "조용한",
                "성능너무떨어지지않", "너무구리면안", "낮춰", "줄여", "안으로", "이하", "비싸");
    }

    private static boolean isBudgetAdjustmentIntent(String normalized) {
        return containsAny(normalized, "예산", "낮춰", "줄여", "안으로", "이하", "초과", "비싸", "만원", "원");
    }

    private static boolean isRemoveIntent(String normalized) {
        return containsAny(normalized, "빼줘", "빼", "삭제", "제거");
    }

    private static boolean isQuantityIntent(String normalized) {
        return containsAny(normalized, "수량", "개로", "장으로", "64gb", "64기가", "32gb", "32기가")
                && containsAny(normalized, "ram", "램", "메모리", "ssd", "스토리지", "저장장치", "바꿔", "변경");
    }

    private static boolean isRecommendationIntent(String normalized) {
        return containsAny(normalized, "추천", "맞춰", "짜줘", "구성", "골라");
    }

    private static boolean isPartRecommend(String normalized, String category) {
        return category != null && isRecommendationIntent(normalized) && !containsAny(normalized, "pc", "컴퓨터", "본체", "견적");
    }

    private static boolean isBuildRecommend(String normalized, String message) {
        boolean explicitRecommend = isRecommendationIntent(normalized)
                && (containsAny(normalized,
                "pc", "컴퓨터", "본체", "견적", "조립컴", "조립pc", "구성",
                "목표", "게임", "게이밍", "qhd", "fhd", "4k", "hz", "배그", "발로란트", "오버워치", "사이버펑크", "로스트아크")
                || BuildChatService.parseBudgetWon(message) != null);
        boolean budgetWithUseCase = BuildChatService.parseBudgetWon(message) != null
                && hasBuildUseCaseSignal(normalized);
        return explicitRecommend || budgetWithUseCase;
    }

    private static boolean isDraftCategoryReplacementIntent(String normalized, String category) {
        return category != null
                && containsAny(normalized, "걸로", "로맞춰", "으로맞춰", "모델꺼", "모델걸", "브랜드");
    }

    private static boolean hasBuildUseCaseSignal(String normalized) {
        return containsAny(normalized,
                "영상", "편집", "프리미어", "블렌더", "렌더", "개발", "docker", "도커", "ide",
                "게임", "게이밍", "qhd", "fhd", "4k", "hz", "배그", "발로란트", "오버워치", "사이버펑크", "로스트아크",
                "ai", "cuda", "로컬ai", "학습", "저소음", "조용", "컴팩트", "사무");
    }

    private static boolean isExplainIntent(String normalized) {
        return containsAny(normalized, "왜좋", "이유", "설명", "호환괜찮", "괜찮아");
    }

    private static boolean isMissingMonitorContext(String normalized) {
        return containsAny(normalized, "모니터")
                && containsAny(normalized, "아직안", "안정", "못정", "미정", "모름");
    }

    private static boolean isAmbiguousProductQuery(String partQuery) {
        String normalized = normalize(partQuery);
        if (normalized.isBlank()) {
            return true;
        }
        if (hasKnownBrand(normalized) && !containsModelSignal(normalized)) {
            return true;
        }
        if (hasKnownBrand(normalized) && containsModelSignal(normalized) && normalized.length() >= 8) {
            return false;
        }
        if (normalized.length() >= 18 && hasKnownBrand(normalized)) {
            return false;
        }
        return normalized.matches(".*(rtx)?50[6-9]0.*")
                || normalized.matches(".*(rtx)?40[6-9]0.*")
                || normalized.matches(".*9950x3d.*")
                || normalized.matches(".*9700x.*")
                || normalized.length() < 8;
    }

    private static boolean hasProductQuerySignal(String partQuery) {
        String normalized = normalize(partQuery);
        if (normalized.isBlank()) {
            return false;
        }
        String withoutCategoryTerms = normalized
                .replace("gpu", "")
                .replace("그래픽카드", "")
                .replace("그래픽", "")
                .replace("글카", "")
                .replace("vga", "")
                .replace("cpu", "")
                .replace("씨피유", "")
                .replace("프로세서", "")
                .replace("메인보드", "")
                .replace("마더보드", "")
                .replace("보드", "")
                .replace("ram", "")
                .replace("램", "")
                .replace("메모리", "")
                .replace("ssd", "")
                .replace("스토리지", "")
                .replace("저장장치", "")
                .replace("파워", "")
                .replace("psu", "")
                .replace("케이스", "")
                .replace("쿨러", "")
                .replace("cooler", "")
                .replace("부품", "")
                .replace("목록", "");
        return hasKnownBrand(normalized)
                || containsModelSignal(normalized)
                || withoutCategoryTerms.matches(".*\\d.*");
    }

    private static boolean hasFilterDescriptor(String partQuery) {
        String normalized = normalize(partQuery);
        return containsAny(normalized,
                "nvidia", "엔비디아", "amd", "intel", "인텔", "라데온",
                "ddr5", "ddr4", "nvme", "수랭", "공랭", "aio",
                "화이트", "흰색", "저소음", "조용", "rgb");
    }

    private static boolean hasKnownBrand(String normalized) {
        return containsAny(normalized,
                "asus", "msi", "gigabyte", "기가바이트", "lianli", "리안리", "samsung", "삼성", "corsair", "커세어",
                "amd", "intel", "인텔", "라이젠", "정품", "멀티팩", "대원", "코리아", "zotac", "조텍", "lian li");
    }

    private static boolean containsModelSignal(String normalized) {
        return normalized.matches(".*\\d.*")
                || normalized.contains("x3d")
                || normalized.contains("rtx")
                || normalized.contains("geforce")
                || normalized.contains("ddr")
                || normalized.contains("nvme");
    }

    private static boolean standaloneContext(Map<String, Object> body) {
        return objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()
                && objectMaps(body.get("currentBuilds")).isEmpty()
                && objectMaps(body.get("appliedPartPreferences")).isEmpty()
                && text(body.get("selectedCategory")) == null;
    }

    private static String semanticSignature(BuildChatIntent intent, String category, String partQuery, String budget) {
        return intent.name()
                + "|category=" + firstText(category, "ANY")
                + "|part=" + firstText(normalize(partQuery), "ANY")
                + "|budget=" + firstText(budget, "ANY");
    }

    private static String routeSignature(BuildChatIntent intent, String category) {
        return intent.name() + "|category=" + firstText(category, "ANY");
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
}
