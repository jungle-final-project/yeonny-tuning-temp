package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** M2 자동 재훈련 조건 판정(RecommendationTrainingService.autoRetrainDecision) 단위 테스트. */
class RecommendationAutoRetrainDecisionTest {

    private static RecommendationTrainingService.AutoRetrainDecision decide(
            long events, long positives, Integer daysSince, int failures) {
        return RecommendationTrainingService.autoRetrainDecision(events, positives, daysSince, failures, 100, 10, 7);
    }

    @Test
    void proceedsWhenAllConditionsMet() {
        var decision = decide(150, 20, 10, 0);
        assertThat(decision.proceed()).isTrue();
    }

    @Test
    void proceedsWhenNeverTrainedBefore() {
        // daysSinceLastSuccess=null(최초 훈련) + 데이터 충족 → 진행.
        var decision = decide(150, 20, null, 0);
        assertThat(decision.proceed()).isTrue();
    }

    @Test
    void stopsAfterTwoConsecutiveFailures() {
        var decision = decide(150, 20, 10, 2);
        assertThat(decision.proceed()).isFalse();
        assertThat(decision.reason()).contains("연속").contains("중단");
    }

    @Test
    void holdsAfterOneFailure() {
        var decision = decide(150, 20, 10, 1);
        assertThat(decision.proceed()).isFalse();
        assertThat(decision.reason()).contains("직전 자동 재훈련이 실패");
    }

    @Test
    void skipsWhenTooFewNewEvents() {
        var decision = decide(50, 20, 10, 0);
        assertThat(decision.proceed()).isFalse();
        assertThat(decision.reason()).contains("새 이벤트");
    }

    @Test
    void skipsWhenTooFewPositives() {
        // 홈 방문 IMPRESSION(라벨 0)만 쌓여 이벤트는 많아도 양성이 부족한 경우 — 라벨 없는 재훈련 방지.
        var decision = decide(150, 3, 10, 0);
        assertThat(decision.proceed()).isFalse();
        assertThat(decision.reason()).contains("새 양성 라벨");
    }

    @Test
    void skipsWhenIntervalNotElapsed() {
        var decision = decide(150, 20, 2, 0);
        assertThat(decision.proceed()).isFalse();
        assertThat(decision.reason()).contains("일 < 7일");
    }

    @Test
    void failureBackoffTakesPrecedenceOverDataConditions() {
        // 실패 백오프가 데이터 조건보다 먼저 — 데이터가 충분해도 실패 상태면 진행 안 함.
        var decision = decide(1000, 500, 30, 2);
        assertThat(decision.proceed()).isFalse();
        assertThat(decision.reason()).contains("중단");
    }
}
