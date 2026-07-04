package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildChatIntentRouterTest {
    private final BuildChatIntentRouter router = new BuildChatIntentRouter();

    @Test
    void classifiesMinimalPairMatrixWithoutCrossingSideEffectBoundaries() {
        List<Case> cases = List.of(
                c("GPU 보여줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("그래픽카드 화면 열어줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("CPU 부품 보여줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("메인보드 목록 보여줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("RAM 화면으로 이동", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("SSD 부품 화면", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("파워 보여줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("케이스 보여줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("쿨러 보여줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("케이스 제품 페이지 열어줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("PSU 상품 페이지 보여줘", BuildChatIntent.NAVIGATE_CATEGORY, "NONE"),
                c("내 견적함 열어줘", BuildChatIntent.NAVIGATE_STATIC, "NONE"),
                c("셀프 견적 장바구니 열어줘", BuildChatIntent.NAVIGATE_STATIC, "NONE"),
                c("AS 접수하러 가자", BuildChatIntent.NAVIGATE_STATIC, "NONE"),
                c("AS 챗봇 열어줘", BuildChatIntent.NAVIGATE_STATIC, "NONE"),
                c("구매하기 화면으로 이동", BuildChatIntent.NAVIGATE_STATIC, "NONE"),
                c("5090 보여줘", BuildChatIntent.FILTER_PART_SEARCH, "NONE"),
                c("RTX 5090 보여줘", BuildChatIntent.FILTER_PART_SEARCH, "NONE"),
                c("9950X3D 보여줘", BuildChatIntent.FILTER_PART_SEARCH, "NONE"),
                c("9700X 보여줘", BuildChatIntent.FILTER_PART_SEARCH, "NONE"),
                c("MSI 보드 보여줘", BuildChatIntent.FILTER_PART_SEARCH, "NONE"),
                c("NVIDIA GPU 상세 보여줘", BuildChatIntent.FILTER_PART_SEARCH, "NONE"),
                c("수랭 쿨러 보여줘", BuildChatIntent.FILTER_PART_SEARCH, "NONE"),
                c("ASUS ROG Astral 5090 상세 열어줘", BuildChatIntent.NAVIGATE_PART_DETAIL, "NONE"),
                c("AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩) 상세페이지로 이동해", BuildChatIntent.NAVIGATE_PART_DETAIL, "NONE"),
                c("리안리 216 케이스 상세페이지 보여줘", BuildChatIntent.NAVIGATE_PART_DETAIL, "NONE"),
                c("5090 추천해줘", BuildChatIntent.PART_RECOMMEND, "NONE"),
                c("그래픽카드 추천해줘", BuildChatIntent.PART_RECOMMEND, "NONE"),
                c("고성능 GPU 추천해줘", BuildChatIntent.PART_RECOMMEND, "NONE"),
                c("SSD 추천해줘", BuildChatIntent.PART_RECOMMEND, "NONE"),
                c("쿨러 추천해줘", BuildChatIntent.PART_RECOMMEND, "NONE"),
                c("5090 들어간 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("300만원 이하 RTX 5090 PC로 맞춰줘", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("300만원 견적 추천해줘", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("3백만원 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("800만원으로 최고급 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("QHD 배그용 컴퓨터 맞춰줘", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("FHD 발로란트 240Hz 목표로 추천해줘", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("영상편집 + Docker + IDE 병행용으로 400만원 안쪽", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("오래 쓸 수 있게 업그레이드 여유 있는 구성", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("예산 없이 끝판왕으로", BuildChatIntent.LLM_FULL, "NONE"),
                c("이 견적 왜 좋아?", BuildChatIntent.EXPLAIN_CURRENT, "NONE"),
                c("지금 견적 호환 괜찮아?", BuildChatIntent.EXPLAIN_CURRENT, "NONE"),
                c("컴퓨터 하나 맞춰줘", BuildChatIntent.BUILD_RECOMMEND, "NONE"),
                c("게임용인데 모니터는 아직 안 정했어", BuildChatIntent.ASK_CLARIFICATION, "NONE"),
                c("메인보드 MSI 걸로 맞춰줘", BuildChatIntent.MUTATE_DRAFT_RECOMMEND, "MEDIUM"),
                c("케이스 리안리 216 모델꺼로 맞춰줘", BuildChatIntent.MUTATE_DRAFT_RECOMMEND, "MEDIUM"),
                c("RAM 64GB로 바꿔줘", BuildChatIntent.MUTATE_DRAFT_RECOMMEND, "MEDIUM"),
                draft("GPU 빼줘", BuildChatIntent.MUTATE_DRAFT_REMOVE, "LOW"),
                draft("그래픽카드 삭제", BuildChatIntent.MUTATE_DRAFT_REMOVE, "LOW"),
                draft("RAM 64GB로 바꿔줘", BuildChatIntent.MUTATE_DRAFT_QUANTITY, "LOW"),
                draft("램 수량 2개로 변경", BuildChatIntent.MUTATE_DRAFT_QUANTITY, "LOW"),
                draft("SSD 2개로 바꿔줘", BuildChatIntent.MUTATE_DRAFT_QUANTITY, "LOW"),
                draft("CPU를 9700X로 바꿔줘", BuildChatIntent.MUTATE_DRAFT_REPLACE_EXACT, "MEDIUM"),
                draft("그래픽카드 5090으로 바꿔줘", BuildChatIntent.MUTATE_DRAFT_REPLACE_EXACT, "MEDIUM"),
                draft("GPU 더 좋은 걸로 바꿔줘", BuildChatIntent.MUTATE_DRAFT_RECOMMEND, "MEDIUM"),
                draft("그래픽카드 더 싼데 성능 너무 떨어지지 않게", BuildChatIntent.MUTATE_DRAFT_RECOMMEND, "MEDIUM"),
                draft("전체 견적을 250만원 안으로 낮춰줘", BuildChatIntent.MUTATE_DRAFT_RECOMMEND, "MEDIUM"),
                draft("메인보드 MSI 걸로 맞춰줘", BuildChatIntent.MUTATE_DRAFT_REPLACE_EXACT, "MEDIUM"),
                draft("케이스 더 작은 걸로 바꿔줘", BuildChatIntent.MUTATE_DRAFT_RECOMMEND, "MEDIUM"),
                draft("케이스 리안리 216 모델꺼로 맞춰줘", BuildChatIntent.MUTATE_DRAFT_REPLACE_EXACT, "MEDIUM"),
                draft("CPU를 9700X로 바꾸면?", BuildChatIntent.SIMULATE_REPLACEMENT, "NONE"),
                draft("RAM 64GB로 바꾸면 성능 어때?", BuildChatIntent.SIMULATE_REPLACEMENT, "NONE"),
                draft("그래픽카드 5090으로 바꾸면 배그 QHD FPS 어때?", BuildChatIntent.SIMULATE_REPLACEMENT, "NONE"),
                draft("파워 1000W로 바꾸면 안정적이야?", BuildChatIntent.SIMULATE_REPLACEMENT, "NONE"),
                draft("쿨러를 수랭으로 바꾸면 온도 차이 어때?", BuildChatIntent.SIMULATE_REPLACEMENT, "NONE")
        );

        assertThat(cases).hasSizeGreaterThanOrEqualTo(50);
        for (Case item : cases) {
            BuildChatIntentDecision decision = router.decide(item.request(), item.message());
            assertThat(decision.intent()).as(item.message()).isEqualTo(item.intent());
            assertThat(decision.sideEffectRisk()).as(item.message()).isEqualTo(item.sideEffectRisk());
            if (item.intent() == BuildChatIntent.SIMULATE_REPLACEMENT) {
                assertThat(decision.isMutation()).as(item.message()).isFalse();
            }
        }
    }

    @Test
    void semanticCacheEligibilityIsLimitedToStandaloneReadOnlyRecommendations() {
        BuildChatIntentDecision build = router.decide(Map.of("message", "300만원 견적 추천해줘"), "300만원 견적 추천해줘");
        BuildChatIntentDecision part = router.decide(Map.of("message", "고성능 GPU 추천해줘"), "고성능 GPU 추천해줘");
        BuildChatIntentDecision mutation = router.decide(draftRequest("GPU 빼줘"), "GPU 빼줘");
        BuildChatIntentDecision simulation = router.decide(draftRequest("GPU를 5090으로 바꾸면 FPS 어때?"), "GPU를 5090으로 바꾸면 FPS 어때?");

        assertThat(build.isSemanticCacheEligible()).isTrue();
        assertThat(part.isSemanticCacheEligible()).isTrue();
        assertThat(mutation.isSemanticCacheEligible()).isFalse();
        assertThat(simulation.isSemanticCacheEligible()).isFalse();
    }

    private static Case c(String message, BuildChatIntent intent, String sideEffectRisk) {
        return new Case(message, Map.of("message", message), intent, sideEffectRisk);
    }

    private static Case draft(String message, BuildChatIntent intent, String sideEffectRisk) {
        return new Case(message, draftRequest(message), intent, sideEffectRisk);
    }

    private static Map<String, Object> draftRequest(String message) {
        return Map.of(
                "message", message,
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "gpu-1", "category", "GPU", "name", "RTX 5080", "quantity", 1),
                        Map.of("partId", "cpu-1", "category", "CPU", "name", "Ryzen 7", "quantity", 1),
                        Map.of("partId", "ram-1", "category", "RAM", "name", "DDR5 32GB", "quantity", 1),
                        Map.of("partId", "ssd-1", "category", "STORAGE", "name", "NVMe 1TB", "quantity", 1),
                        Map.of("partId", "psu-1", "category", "PSU", "name", "850W", "quantity", 1),
                        Map.of("partId", "case-1", "category", "CASE", "name", "Mid Tower", "quantity", 1),
                        Map.of("partId", "cooler-1", "category", "COOLER", "name", "Air Cooler", "quantity", 1)
                ))
        );
    }

    private record Case(String message, Map<String, Object> request, BuildChatIntent intent, String sideEffectRisk) {
    }
}
