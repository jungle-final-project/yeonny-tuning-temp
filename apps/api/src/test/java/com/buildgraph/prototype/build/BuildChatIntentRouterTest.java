package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildChatIntentRouterTest {
    private final BuildChatIntentRouter router = new BuildChatIntentRouter();

    @Test
    void classifiesMinimalPairMatrixWithReducedIntentSet() {
        List<Case> cases = List.of(
                // 견적 추천 (유지)
                c("5090 들어간 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("300만원 이하 RTX 5090 PC로 맞춰줘", BuildChatIntent.BUILD_RECOMMEND),
                c("300만원 견적 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("3백만원 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("800만원으로 최고급 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("QHD 배그용 컴퓨터 맞춰줘", BuildChatIntent.BUILD_RECOMMEND),
                c("FHD 발로란트 240Hz 목표로 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("영상편집 + Docker + IDE 병행용으로 400만원 안쪽", BuildChatIntent.BUILD_RECOMMEND),
                // 예산·용도·모델 번호가 전혀 없는 동사+명사 요청은 이제 되묻기로 보낸다 (역질문 흐름)
                c("오래 쓸 수 있게 업그레이드 여유 있는 구성", BuildChatIntent.ASK_CLARIFICATION),
                c("컴퓨터 하나 맞춰줘", BuildChatIntent.ASK_CLARIFICATION),
                c("해상도 좋은 피시 맞춰줘", BuildChatIntent.ASK_CLARIFICATION),
                c("PC 견적 짜줘", BuildChatIntent.ASK_CLARIFICATION),
                // 그래프(드래프트) 기반 견적 완성 (유지)
                draft("지금 견적 기준으로 나머지 부품 채워줘", BuildChatIntent.BUILD_RECOMMEND),
                draft("이 그래프 구성 마저 완성해줘", BuildChatIntent.BUILD_RECOMMEND),
                // 성능 시뮬레이션 (유지) — "바꾸면"은 시뮬레이션이다
                draft("CPU를 9700X로 바꾸면?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("RAM 64GB로 바꾸면 성능 어때?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("그래픽카드 5090으로 바꾸면 배그 QHD FPS 어때?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("파워 1000W로 바꾸면 안정적이야?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("쿨러를 수랭으로 바꾸면 온도 차이 어때?", BuildChatIntent.SIMULATE_REPLACEMENT),
                c("GPU 바꾸면 성능 어떻게 돼?", BuildChatIntent.SIMULATE_REPLACEMENT),
                // 견적 추천 — 자연어 방어 스윕에서 발견된 과차단 케이스 (동사/명사/한글숫자/고예산)
                c("650만원 정도로 방송 송출용 스트리밍 컴 맞추고 싶은데 견적 내줘", BuildChatIntent.BUILD_RECOMMEND),
                c("일천삼백만원 예산으로 AI 모델 돌릴 수 있는 최고사양 컴퓨터 견적 뽑아주세요", BuildChatIntent.BUILD_RECOMMEND),
                c("삼백만원 정도로 포토샵 프리미어 돌리는 디자인 작업용 컴 견적 좀 내주라", BuildChatIntent.BUILD_RECOMMEND),
                c("예산은 320만원이고 3D 작업용인데 견적 뽑아주라", BuildChatIntent.BUILD_RECOMMEND),
                c("1600만원 있는데 풀스펙으로 가보자", BuildChatIntent.BUILD_RECOMMEND),
                c("천팔백만원 예산으로 방송용 컴퓨터 조립하고 싶어요", BuildChatIntent.BUILD_RECOMMEND),
                c("2000만원짜리 하이엔드 견적 부탁드립니다", BuildChatIntent.BUILD_RECOMMEND),
                c("3000만원 이하 최강 조합으로 하나 짜주라", BuildChatIntent.BUILD_RECOMMEND),
                c("2천만원 예산인데 뭐가 최선일지 견적 내줘", BuildChatIntent.BUILD_RECOMMEND),
                c("돈은 3천만원까지 괜찮으니 끝판왕 사양으로", BuildChatIntent.BUILD_RECOMMEND),
                c("1400만원쯤 생각 중인데 괜찮은 하이엔드 조합 있음?", BuildChatIntent.BUILD_RECOMMEND),
                c("영상편집용 PC 필요해", BuildChatIntent.BUILD_RECOMMEND),
                c("디아블로4 쾌적하게 돌릴 사양으로 견적 좀", BuildChatIntent.BUILD_RECOMMEND),
                c("블렌더 3D 렌더링 돌릴 컴 하나 뽑아줘", BuildChatIntent.BUILD_RECOMMEND),
                c("스타2랑 롤 정도 돌아가면 되는데 뭐 사면 됨?", BuildChatIntent.BUILD_RECOMMEND),
                c("사무실에서 엑셀 문서작업만 할 컴 추천 좀요", BuildChatIntent.BUILD_RECOMMEND),
                c("개발용으로 도커랑 IDE 여러개 띄울 워크스테이션 필요함", BuildChatIntent.BUILD_RECOMMEND),
                c("몬헌 와일즈 돌아가는 데스크탑 하나 골라줘", BuildChatIntent.BUILD_RECOMMEND),
                c("2500만원 예산 잡았는데 뭐부터 넣어야 돼?", BuildChatIntent.BUILD_RECOMMEND),
                // 시뮬레이션 — 스윕에서 발견된 동사 변형
                draft("램을 64기가로 올리면 체감 돼?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("5090 끼우면?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("지금 견적에서 글카만 4070 Super로 낮추면 성능 많이 깎여?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("그래픽카드 한 단계 올렸을 때랑 지금이랑 비교 좀 해줘", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("램 32에서 48로 늘리면 뭐가 달라지나요", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("CPU를 인텔 울트라7으로 갈아타면 어때?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("글카 5080에서 5070 Ti로 내려도 게임 잘 돌아가?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("지금 구성에서 CPU만 상위 모델로 교체 시 성능 향상 폭이 궁금해요", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("5090 박으면 옵치 프레임 떡상함?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("씨퓨 바꿔 끼우는 게 나아, 글카 바꾸는 게 나아? 성능 비교해줘", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("9950X로 바꿔버리면 멀티코어 성능 확 뛰어?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("램 48GB로 늘리면 작업 속도 빨라질까요", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("그래픽카드 업그레이드하면 4K에서도 쾌적해질까? 5090 기준으로", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("9700X 대신 9900X 꽂으면 멀티코어 얼마나 차이나요", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("5080에서 5090으로 넘어가면 4K에서 몇 프레임 더 나와?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("지피유 한 단계 윗급으로 교체하면 가성비 나올까", BuildChatIntent.SIMULATE_REPLACEMENT),
                // 명확화 (유지 + 모호 구매의향 확대)
                c("게임용인데 모니터는 아직 안 정했어", BuildChatIntent.ASK_CLARIFICATION),
                c("아무거나 좋은 걸로", BuildChatIntent.ASK_CLARIFICATION),
                c("뭐 사면 돼?", BuildChatIntent.ASK_CLARIFICATION),
                c("추천 부탁드립니다", BuildChatIntent.ASK_CLARIFICATION),
                c("싸고 좋은 거 없나", BuildChatIntent.ASK_CLARIFICATION),
                c("요즘 뭐가 잘 나가요?", BuildChatIntent.ASK_CLARIFICATION),
                c("조립컴 처음인데 뭐부터 봐야 하나요", BuildChatIntent.ASK_CLARIFICATION),
                c("뭘 사야 할지 모르겠어요", BuildChatIntent.ASK_CLARIFICATION),
                c("견적 좀", BuildChatIntent.ASK_CLARIFICATION),
                c("요즘 컴퓨터 뭐가 좋아요", BuildChatIntent.ASK_CLARIFICATION),
                c("본체만 새로 하고 싶은데", BuildChatIntent.ASK_CLARIFICATION),
                c("컴맹인데 하나 골라줘", BuildChatIntent.ASK_CLARIFICATION),
                c("사양 어느 정도가 무난함?", BuildChatIntent.ASK_CLARIFICATION),
                c("피시 하나 뽑으려는데 감이 안 와요", BuildChatIntent.ASK_CLARIFICATION),
                c("업그레이드할까 새로 살까 고민중", BuildChatIntent.ASK_CLARIFICATION),
                c("괜찮은 견적 하나만요", BuildChatIntent.ASK_CLARIFICATION),
                // 화면 이동/필터/상세 — 제거된 기능, 고정 안내
                c("GPU 보여줘", BuildChatIntent.UNSUPPORTED),
                c("그래픽카드 화면 열어줘", BuildChatIntent.UNSUPPORTED),
                c("메인보드 목록 보여줘", BuildChatIntent.UNSUPPORTED),
                c("5090 보여줘", BuildChatIntent.UNSUPPORTED),
                c("9950X3D 보여줘", BuildChatIntent.UNSUPPORTED),
                c("리안리 216 케이스 상세페이지 보여줘", BuildChatIntent.UNSUPPORTED),
                c("AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩) 상세페이지로 이동해", BuildChatIntent.UNSUPPORTED),
                c("내 견적함 열어줘", BuildChatIntent.UNSUPPORTED),
                c("구매하기 화면으로 이동", BuildChatIntent.UNSUPPORTED),
                // 단일 부품 추천 — 제거된 기능 (그래프 노드 담당)
                c("5090 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("그래픽카드 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("고성능 GPU 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("SSD 추천해줘", BuildChatIntent.UNSUPPORTED),
                // 장바구니 조작 대화 — 제거된 기능. "바꿔줘"는 시뮬레이션이 아니다
                draft("GPU 빼줘", BuildChatIntent.UNSUPPORTED),
                draft("그래픽카드 삭제", BuildChatIntent.UNSUPPORTED),
                draft("RAM 64GB로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("램 수량 2개로 변경", BuildChatIntent.UNSUPPORTED),
                draft("CPU를 9700X로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("그래픽카드 5090으로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("GPU 더 좋은 걸로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("메인보드 MSI 걸로 맞춰줘", BuildChatIntent.UNSUPPORTED),
                draft("케이스 리안리 216 모델꺼로 맞춰줘", BuildChatIntent.UNSUPPORTED),
                // 일반 설명/상담 — 제거된 기능
                c("이 견적 왜 좋아?", BuildChatIntent.UNSUPPORTED),
                c("지금 견적 호환 괜찮아?", BuildChatIntent.UNSUPPORTED),
                c("예산 없이 끝판왕으로", BuildChatIntent.UNSUPPORTED),
                // 오통과 방어 — 스윕에서 발견된 케이스
                draft("이 구성에서 램만 64기가로 올려줘", BuildChatIntent.UNSUPPORTED),
                c("게임용 모니터 하나만 골라줘", BuildChatIntent.UNSUPPORTED),
                c("게이밍 노트북 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("32기가 램 추천 좀 해주세요", BuildChatIntent.UNSUPPORTED),
                c("30만원대에서 살만한 CPU 하나만 골라줘", BuildChatIntent.UNSUPPORTED),
                c("지금 견적에 병목 없는지 한번 봐줘", BuildChatIntent.UNSUPPORTED)
        );

        assertThat(cases).hasSizeGreaterThanOrEqualTo(40);
        for (Case item : cases) {
            BuildChatIntentDecision decision = router.decide(item.request(), item.message());
            assertThat(decision.intent()).as(item.message()).isEqualTo(item.intent());
            assertThat(decision.sideEffectRisk()).as(item.message()).isEqualTo("NONE");
        }
    }

    @Test
    void semanticCacheEligibilityIsLimitedToStandaloneBuildRecommendations() {
        BuildChatIntentDecision build = router.decide(Map.of("message", "300만원 견적 추천해줘"), "300만원 견적 추천해줘");
        BuildChatIntentDecision partRecommend = router.decide(Map.of("message", "고성능 GPU 추천해줘"), "고성능 GPU 추천해줘");
        BuildChatIntentDecision mutation = router.decide(draftRequest("GPU 빼줘"), "GPU 빼줘");
        BuildChatIntentDecision simulation = router.decide(draftRequest("GPU를 5090으로 바꾸면 FPS 어때?"), "GPU를 5090으로 바꾸면 FPS 어때?");
        BuildChatIntentDecision draftCompletion = router.decide(draftRequest("지금 견적 나머지 채워줘"), "지금 견적 나머지 채워줘");

        assertThat(build.isSemanticCacheEligible()).isTrue();
        assertThat(partRecommend.isSemanticCacheEligible()).isFalse();
        assertThat(mutation.isSemanticCacheEligible()).isFalse();
        assertThat(simulation.isSemanticCacheEligible()).isFalse();
        assertThat(draftCompletion.isSemanticCacheEligible()).isFalse();
    }

    private static Case c(String message, BuildChatIntent intent) {
        return new Case(message, Map.of("message", message), intent);
    }

    private static Case draft(String message, BuildChatIntent intent) {
        return new Case(message, draftRequest(message), intent);
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

    private record Case(String message, Map<String, Object> request, BuildChatIntent intent) {
    }
}
