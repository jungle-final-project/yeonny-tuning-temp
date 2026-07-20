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
                c("RTX 5090 말고 가성비 GPU로 견적 추천해줘", BuildChatIntent.ASK_CLARIFICATION),
                c("300만원 이하 RTX 5090 PC로 맞춰줘", BuildChatIntent.BUILD_RECOMMEND),
                c("300만원 견적 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("3백만원 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("800만원으로 최고급 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("최소 견적으로 맞춰줘", BuildChatIntent.BUILD_RECOMMEND),
                c("배그용으로 가능한 가장 싼 견적을 짜줘", BuildChatIntent.BUILD_RECOMMEND),
                c("현재 자산에서 최저 비용 구성으로 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("QHD 배그용 컴퓨터 맞춰줘", BuildChatIntent.ASK_CLARIFICATION),
                c("FHD 발로란트 240Hz 목표로 추천해줘", BuildChatIntent.ASK_CLARIFICATION),
                c("영상편집 + Docker + IDE 병행용으로 400만원 안쪽", BuildChatIntent.BUILD_RECOMMEND),
                c("ASUS 보드와 AMD CPU로 400만원", BuildChatIntent.BUILD_RECOMMEND),
                c("2TB SSD와 1000W 파워 포함 500만원", BuildChatIntent.BUILD_RECOMMEND),
                c("MSI 메인보드로 350만원 게임용", BuildChatIntent.BUILD_RECOMMEND),
                c("DDR5 64GB 포함해서 300만원 개발용", BuildChatIntent.BUILD_RECOMMEND),
                c("30만원 게임용 CPU 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("60만원대 게임 CPU", BuildChatIntent.UNSUPPORTED),
                c("게임용 PC 필요해", BuildChatIntent.ASK_CLARIFICATION),
                c("배그 같은 게임이 잘 돌아갔으면 좋겠어요", BuildChatIntent.ASK_CLARIFICATION),
                c("중학교 3학년 조카가 처음 PC를 맞추려고 합니다. 배틀그라운드가 잘 돌아가는 컴퓨터를 원합니다", BuildChatIntent.ASK_CLARIFICATION),
                c("개발용으로 하나 봐줘", BuildChatIntent.ASK_CLARIFICATION),
                c("게임도 되는 컴퓨터", BuildChatIntent.ASK_CLARIFICATION),
                c("돈 상관없이 끝판왕 게임용 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("예산 무관 최고급 영상 편집 컴퓨터 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("최상급 CPU와 GPU로 추천", BuildChatIntent.BUILD_RECOMMEND),
                // 예산·용도·모델 번호가 전혀 없는 동사+명사 요청은 이제 되묻기로 보낸다 (역질문 흐름)
                c("오래 쓸 수 있게 업그레이드 여유 있는 구성", BuildChatIntent.ASK_CLARIFICATION),
                c("중3 아들 피시 맞출건데 추천해줘", BuildChatIntent.ASK_CLARIFICATION),
                c("대학 입학하는 조카에게 컴퓨터를 선물하려고 해", BuildChatIntent.ASK_CLARIFICATION),
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
                c("영상편집용 PC 필요해", BuildChatIntent.ASK_CLARIFICATION),
                c("디아블로4 쾌적하게 돌릴 사양으로 견적 좀", BuildChatIntent.ASK_CLARIFICATION),
                c("블렌더 3D 렌더링 돌릴 컴 하나 뽑아줘", BuildChatIntent.ASK_CLARIFICATION),
                c("스타2랑 롤 정도 돌아가면 되는데 뭐 사면 됨?", BuildChatIntent.ASK_CLARIFICATION),
                c("사무실에서 엑셀 문서작업만 할 컴 추천 좀요", BuildChatIntent.ASK_CLARIFICATION),
                c("개발용으로 도커랑 IDE 여러개 띄울 워크스테이션 필요함", BuildChatIntent.ASK_CLARIFICATION),
                c("몬헌 와일즈 돌아가는 데스크탑 하나 골라줘", BuildChatIntent.ASK_CLARIFICATION),
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
                // 접수 전 PC 증상 안내 — 원인 진단은 하지 않고 Agent/AS 연결만 제공한다
                c("게임하다 화면이 멈춰", BuildChatIntent.SUPPORT_GUIDANCE),
                c("나 게임이 좀 멈춰", BuildChatIntent.SUPPORT_GUIDANCE),
                c("검은 화면이 자꾸 떠", BuildChatIntent.SUPPORT_GUIDANCE),
                c("컴퓨터가 갑자기 재부팅돼", BuildChatIntent.SUPPORT_GUIDANCE),
                c("부팅이 안돼", BuildChatIntent.SUPPORT_GUIDANCE),
                c("게임 프레임이 갑자기 뚝뚝 끊겨", BuildChatIntent.SUPPORT_GUIDANCE),
                c("화면이 끊기고 그래픽 오류가 반복돼", BuildChatIntent.SUPPORT_GUIDANCE),
                c("팬 소리가 커지고 너무 뜨거워", BuildChatIntent.SUPPORT_GUIDANCE),
                c("SSD 디스크가 계속 100퍼센트야", BuildChatIntent.SUPPORT_GUIDANCE),
                c("인터넷이 자꾸 끊겨", BuildChatIntent.SUPPORT_GUIDANCE),
                c("컴퓨터에서 소리가 안 나", BuildChatIntent.SUPPORT_GUIDANCE),
                // 증상 단어와 비슷해도 쇼핑·시뮬레이션 요청이면 장애 안내로 가로채지 않는다
                c("게임용 PC 추천해줘", BuildChatIntent.ASK_CLARIFICATION),
                c("검은색 케이스 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("안 멈추는 게임용 PC 추천해줘", BuildChatIntent.ASK_CLARIFICATION),
                draft("GPU를 바꾸면 게임 멈춤이 줄어?", BuildChatIntent.SIMULATE_REPLACEMENT),
                // 셀프 견적 구성도 위치 강조 — 명시된 단일/복수 카테고리는 fast path
                board("램 위치가 어디 있어?", BuildChatIntent.LOCATE_BOARD_PART),
                board("메모리 꽂는 곳 표시해줘", BuildChatIntent.LOCATE_BOARD_PART),
                board("M.2 슬롯 어디야?", BuildChatIntent.LOCATE_BOARD_PART),
                board("파워 장착 위치 보여줘", BuildChatIntent.LOCATE_BOARD_PART),
                board("CPU랑 RAM 위치 보여줘", BuildChatIntent.LOCATE_BOARD_PART),
                board("메인보드랑 램 위치가 어딜까", BuildChatIntent.LOCATE_BOARD_PART),
                // 위치와 비슷한 어휘가 있어도 구매·추천·변경·성능이면 강조하지 않는다
                board("램 어디서 사?", BuildChatIntent.UNSUPPORTED),
                board("RAM 추천해줘", BuildChatIntent.UNSUPPORTED),
                boardDraft("RAM 64GB로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                boardDraft("CPU를 9700X로 바꾸면?", BuildChatIntent.SIMULATE_REPLACEMENT),
                // 명확화 (유지 + 모호 구매의향 확대)
                c("게임용인데 모니터는 아직 안 정했어", BuildChatIntent.ASK_CLARIFICATION),
                c("아무거나 좋은 걸로", BuildChatIntent.ASK_CLARIFICATION),
                c("뭐 사면 돼?", BuildChatIntent.ASK_CLARIFICATION),
                c("추천 부탁드립니다", BuildChatIntent.ASK_CLARIFICATION),
                // 경계 어휘("싸고좋은")는 즉답 되묻기에서 제외 — UNSUPPORTED로 흘려 LLM 강등이 맥락에 맞게 답한다
                c("싸고 좋은 거 없나", BuildChatIntent.UNSUPPORTED),
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
                // "나머지 빼줘"는 완성 요청이 아니라 제거 요청 — 완성 분기가 '나머지'만으로 가로채지 않는다
                draft("이 견적 나머지는 다 빼줘", BuildChatIntent.UNSUPPORTED),
                draft("그래픽카드 삭제", BuildChatIntent.UNSUPPORTED),
                draft("RAM 64GB로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("램 수량 2개로 변경", BuildChatIntent.UNSUPPORTED),
                draft("CPU를 9700X로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("그래픽카드 5090으로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("GPU 더 좋은 걸로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("메인보드 MSI 걸로 맞춰줘", BuildChatIntent.UNSUPPORTED),
                draft("케이스 리안리 216 모델꺼로 맞춰줘", BuildChatIntent.UNSUPPORTED),
                // 현재 견적 종합 점수·약점 설명 — 서버 Tool 재평가 경로
                c("이 견적 왜 좋아?", BuildChatIntent.EXPLAIN_BUILD_SCORE),
                c("지금 견적 호환 괜찮아?", BuildChatIntent.EXPLAIN_BUILD_SCORE),
                c("왜 종합 점수가 낮아?", BuildChatIntent.EXPLAIN_BUILD_SCORE),
                c("이 견적의 약점이 뭐야?", BuildChatIntent.EXPLAIN_BUILD_SCORE),
                c("뭐부터 업그레이드해야 해?", BuildChatIntent.EXPLAIN_BUILD_SCORE),
                c("5090 자체 점수 알려줘", BuildChatIntent.UNSUPPORTED),
                c("예산 없이 끝판왕으로", BuildChatIntent.BUILD_RECOMMEND),
                // 오통과 방어 — 스윕에서 발견된 케이스
                draft("이 구성에서 램만 64기가로 올려줘", BuildChatIntent.UNSUPPORTED),
                c("게임용 모니터 하나만 골라줘", BuildChatIntent.UNSUPPORTED),
                c("게이밍 노트북 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("32기가 램 추천 좀 해주세요", BuildChatIntent.UNSUPPORTED),
                c("30만원대에서 살만한 CPU 하나만 골라줘", BuildChatIntent.UNSUPPORTED),
                c("지금 견적에 병목 없는지 한번 봐줘", BuildChatIntent.EXPLAIN_BUILD_SCORE)
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

    @Test
    void removalRequestIsNotTreatedAsDraftCompletion() {
        // "나머지"라는 명사가 들어 있어도 삭제 동사("빼줘")가 있으면 견적 완성(빈 카테고리 채우기)이
        // 아니라 변경(mutation) 요청으로 라우팅되어야 한다.
        BuildChatIntentDecision removal = router.decide(draftRequest("이 견적 나머지는 다 빼줘"), "이 견적 나머지는 다 빼줘");
        assertThat(removal.intent()).isEqualTo(BuildChatIntent.UNSUPPORTED);
        assertThat(removal.intent()).isNotEqualTo(BuildChatIntent.BUILD_RECOMMEND);

        // 회귀 방어: 순수 완성 요청("나머지 채워줘")은 그대로 BUILD_RECOMMEND로 유지된다.
        BuildChatIntentDecision completion = router.decide(draftRequest("지금 견적 나머지 채워줘"), "지금 견적 나머지 채워줘");
        assertThat(completion.intent()).isEqualTo(BuildChatIntent.BUILD_RECOMMEND);
    }

    @Test
    void relationshipRecommendationsUseTheCategoryNearestTheRecommendationVerb() {
        assertThat(BuildChatService.detectRecommendationTargetCategory("현재 메인보드에 맞는 CPU 추천해줘"))
                .isEqualTo("CPU");
        assertThat(BuildChatService.detectRecommendationTargetCategory("이 CPU에 맞는 메인보드 후보 보여줘"))
                .isEqualTo("MOTHERBOARD");
        assertThat(BuildChatService.detectRecommendationTargetCategory("현재 견적과 호환되는 고성능 GPU 추천해줘"))
                .isEqualTo("GPU");
        assertThat(BuildChatService.detectPartCategory("M.2 SSD 추천해줘"))
                .isEqualTo("STORAGE");

        BuildChatIntentDecision categoryRecommendation = router.decide(
                draftRequest("현재 견적과 호환되는 고성능 GPU 추천해줘"),
                "현재 견적과 호환되는 고성능 GPU 추천해줘"
        );
        assertThat(categoryRecommendation.intent()).isNotEqualTo(BuildChatIntent.EXPLAIN_BUILD_SCORE);
        assertThat(categoryRecommendation.targetCategory()).isEqualTo("GPU");

        BuildChatIntentDecision scoreImprovement = router.decide(
                draftRequest("현재 견적 점수를 실제로 높일 부품을 추천해줘"),
                "현재 견적 점수를 실제로 높일 부품을 추천해줘"
        );
        assertThat(scoreImprovement.intent()).isEqualTo(BuildChatIntent.EXPLAIN_BUILD_SCORE);
    }

    @Test
    void storageCandidateCompatibilityReviewIsNotMisclassifiedAsPcSymptom() {
        String message = "지금 견적에 2TB NVMe SSD 추천해줘 첫 번째 후보를 적용하면 현재 구성에서 문제가 없는지 설명해줘";

        BuildChatIntentDecision decision = router.decide(draftRequest(message), message);

        assertThat(decision.intent()).isNotEqualTo(BuildChatIntent.SUPPORT_GUIDANCE);
        assertThat(decision.targetCategory()).isEqualTo("STORAGE");
    }

    @Test
    void boardFocusRequiresCapabilityAndUsesFastPathForExplicitCategories() {
        BuildChatIntentDecision single = router.decide(
                boardRequest("램 위치가 어디 있어?"),
                "램 위치가 어디 있어?"
        );
        assertThat(single.intent()).isEqualTo(BuildChatIntent.LOCATE_BOARD_PART);
        assertThat(single.confidence()).isEqualTo("HIGH");
        assertThat(single.preferredPath()).isEqualTo("FAST_BOARD_FOCUS");
        assertThat(single.targetCategories()).containsExactly("RAM");

        BuildChatIntentDecision multiple = router.decide(
                boardRequest("CPU랑 RAM 위치 보여줘"),
                "CPU랑 RAM 위치 보여줘"
        );
        assertThat(multiple.intent()).isEqualTo(BuildChatIntent.LOCATE_BOARD_PART);
        assertThat(multiple.confidence()).isEqualTo("HIGH");
        assertThat(multiple.preferredPath()).isEqualTo("FAST_BOARD_FOCUS");
        assertThat(multiple.targetCategories()).containsExactly("CPU", "RAM");

        BuildChatIntentDecision userPhrase = router.decide(
                boardRequest("메인보드랑 램 위치가 어딜까"),
                "메인보드랑 램 위치가 어딜까"
        );
        assertThat(userPhrase.confidence()).isEqualTo("HIGH");
        assertThat(userPhrase.preferredPath()).isEqualTo("FAST_BOARD_FOCUS");
        assertThat(userPhrase.targetCategories()).containsExactly("MOTHERBOARD", "RAM");

        BuildChatIntentDecision findOnBoard = router.decide(
                boardRequest("구성도에서 CPU 찾아줘"),
                "구성도에서 CPU 찾아줘"
        );
        assertThat(findOnBoard.intent()).isEqualTo(BuildChatIntent.LOCATE_BOARD_PART);
        assertThat(findOnBoard.preferredPath()).isEqualTo("FAST_BOARD_FOCUS");
        assertThat(findOnBoard.targetCategories()).containsExactly("CPU");

        BuildChatIntentDecision compoundPartName = router.decide(
                boardRequest("CPU 쿨러 위치가 어딜까?"),
                "CPU 쿨러 위치가 어딜까?"
        );
        assertThat(compoundPartName.targetCategories()).containsExactly("COOLER");

        BuildChatIntentDecision requestedOrder = router.decide(
                boardRequest("CPU와 그래픽카드 위치 보여줘"),
                "CPU와 그래픽카드 위치 보여줘"
        );
        assertThat(requestedOrder.targetCategories()).containsExactly("CPU", "GPU");

        BuildChatIntentDecision separateCpuAndCooler = router.decide(
                boardRequest("CPU랑 쿨러 위치 보여줘"),
                "CPU랑 쿨러 위치 보여줘"
        );
        assertThat(separateCpuAndCooler.targetCategories()).containsExactly("CPU", "COOLER");

        BuildChatIntentDecision withoutCapability = router.decide(
                Map.of("message", "램 위치가 어디 있어?"),
                "램 위치가 어디 있어?"
        );
        assertThat(withoutCapability.intent()).isEqualTo(BuildChatIntent.UNSUPPORTED);
    }

    @Test
    void marksExplicitRecipientContextForContextualClarification() {
        BuildChatIntentDecision decision = router.decide(
                Map.of("message", "중3 아들 피시 맞출건데 추천해줘"),
                "중3 아들 피시 맞출건데 추천해줘"
        );

        assertThat(decision.intent()).isEqualTo(BuildChatIntent.ASK_CLARIFICATION);
        assertThat(decision.ambiguityReasons())
                .contains("LOW_INFORMATION", "RECIPIENT_CONTEXT")
                .doesNotContain("USAGE_ONLY");

        BuildChatIntentDecision recipientWithUseCase = router.decide(
                Map.of("message", "중3 아들이 롤 할 피시 추천해줘"),
                "중3 아들이 롤 할 피시 추천해줘"
        );
        assertThat(recipientWithUseCase.ambiguityReasons())
                .contains("RECIPIENT_CONTEXT", "USAGE_ONLY");

        BuildChatIntentDecision balanceRequest = router.decide(
                Map.of("message", "균형 있는 PC 추천해줘"),
                "균형 있는 PC 추천해줘"
        );
        assertThat(balanceRequest.ambiguityReasons()).doesNotContain("RECIPIENT_CONTEXT");
    }

    // 감사 재현(BG-AUDIT-004): "개발과 게임 균형 CPU"가 현재 견적 점수 설명으로 빠졌다.
    // "균형"은 그 자체로 평가 요청이 아니다 — 무엇을 평가할지가 함께 있어야 점수 설명이다.
    @Test
    void balanceWordAloneIsNotAScoreExplanationSignal() {
        for (String message : List.of("개발과 게임 균형 CPU", "밸런스 좋은 CPU 추천해줘", "균형 잡힌 램 골라줘")) {
            BuildChatIntentDecision decision = router.decide(draftRequest(message), message);
            assertThat(decision.intent()).as(message).isNotEqualTo(BuildChatIntent.EXPLAIN_BUILD_SCORE);
        }
    }

    @Test
    void balanceWordStaysAScoreExplanationSignalWhenItPointsAtTheCurrentBuild() {
        for (String message : List.of(
                "지금 견적 균형이 맞아?",
                "이 견적 밸런스 어때?",
                "종합점수 기준으로 균형이 안 맞는 부분 알려줘")) {
            BuildChatIntentDecision decision = router.decide(draftRequest(message), message);
            assertThat(decision.intent()).as(message).isEqualTo(BuildChatIntent.EXPLAIN_BUILD_SCORE);
        }
    }

    // CPU와 GPU를 나란히 놓고 균형을 묻는 문장은 별도 신호(cpuGpuContrast)가 계속 잡는다.
    @Test
    void cpuGpuBalanceQuestionStillRoutesToScoreExplanation() {
        String message = "CPU랑 GPU 균형이 왜 이래?";
        BuildChatIntentDecision decision = router.decide(draftRequest(message), message);
        assertThat(decision.intent()).isEqualTo(BuildChatIntent.EXPLAIN_BUILD_SCORE);
    }

    private static Case c(String message, BuildChatIntent intent) {
        return new Case(message, Map.of("message", message), intent);
    }

    private static Case draft(String message, BuildChatIntent intent) {
        return new Case(message, draftRequest(message), intent);
    }

    private static Case board(String message, BuildChatIntent intent) {
        return new Case(message, boardRequest(message), intent);
    }

    private static Case boardDraft(String message, BuildChatIntent intent) {
        Map<String, Object> request = new java.util.LinkedHashMap<>(draftRequest(message));
        request.put("uiContext", boardUiContext());
        return new Case(message, request, intent);
    }

    private static Map<String, Object> boardRequest(String message) {
        return Map.of("message", message, "uiContext", boardUiContext());
    }

    private static Map<String, Object> boardUiContext() {
        return Map.of("surface", "SELF_QUOTE", "capabilities", List.of("BOARD_PART_FOCUS"));
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
