package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 감사 재현(BG-AUDIT-003): "최상급 CPU와 GPU로 추천"이 GPU 후보 3개만 돌려줬다.
 * 단일 부품 추천 경로는 추천 동사에 가장 가까운 카테고리 하나만 고르는데, 접속으로 묶인 문장에서는
 * 그 규칙이 앞 부품을 조용히 지운다. 그런 턴은 단일 부품으로 답할 수 없으니 견적 경로에 넘겨야 한다.
 */
class BuildChatMultiPartRequestTest {
    @Test
    void conjunctionBetweenTwoPartsIsTreatedAsAMultiPartRequest() {
        List<String> messages = List.of(
                "최상급 CPU와 GPU로 추천",
                "CPU랑 그래픽카드 추천해줘",
                "램이랑 SSD 추천해줘",
                "CPU, GPU 추천해줘",
                "파워와 케이스 골라줘"
        );
        for (String message : messages) {
            assertThat(BuildChatService.requestsMultiplePartCategories(message)).as(message).isTrue();
        }
    }

    @Test
    void relationalSentenceKeepsASingleRecommendationTarget() {
        // "A에 맞는 B"는 A가 기준이고 대상은 B 하나다 — 접속이 없으므로 걸리지 않는다.
        List<String> messages = List.of(
                "현재 메인보드에 맞는 CPU 추천",
                "CPU에 맞는 메인보드 후보 보여줘",
                "이 케이스에 들어가는 쿨러 골라줘",
                "지금 파워로 감당되는 그래픽카드 추천"
        );
        for (String message : messages) {
            assertThat(BuildChatService.requestsMultiplePartCategories(message)).as(message).isFalse();
        }
    }

    @Test
    void singlePartRequestsAreUnaffected() {
        List<String> messages = List.of(
                "고성능 GPU 추천해줘",
                "가성비 CPU 골라줘",
                "32기가 램 추천 좀 해주세요",
                // "CPU 쿨러"는 두 낱말이지만 접속이 없다 — 한 부품(쿨러) 요청이다.
                "CPU 쿨러 추천해줘"
        );
        for (String message : messages) {
            assertThat(BuildChatService.requestsMultiplePartCategories(message)).as(message).isFalse();
        }
    }

    @Test
    void nullAndBlankMessagesAreNotMultiPartRequests() {
        assertThat(BuildChatService.requestsMultiplePartCategories(null)).isFalse();
        assertThat(BuildChatService.requestsMultiplePartCategories("")).isFalse();
        assertThat(BuildChatService.requestsMultiplePartCategories("   ")).isFalse();
    }
}
