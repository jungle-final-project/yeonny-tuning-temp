package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * M1 승급 게이트(evaluatePromotionVerdict)와 M6 스큐 게이트(servingSchemaMismatch)의 순수 판정
 * 로직 단위 테스트. 컨트롤러 테스트는 RecommendationTrainingService를 통째로 mock하므로 이 게이트
 * 로직을 실제로 실행하지 않는다 — 여기서 분기별로 고정한다.
 */
class RecommendationPromotionGateTest {

    private static Map<String, Object> comparison(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    // ---- M1 evaluatePromotionVerdict ----

    @Test
    void championBetterWithFairComparisonAgainstCurrentActiveIsBlocked() {
        Map<String, Object> cmp = comparison(
                "verdict", "CHAMPION_BETTER",
                "champion", "home-parts-active-v1",
                "holdoutOverlapWithChampion", 0.0,
                "verdictReason", "spearman 차 CI < 0");
        assertThatThrownBy(() ->
                RecommendationTrainingService.evaluatePromotionVerdict(cmp, "home-parts-active-v1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error ->
                        assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void championBetterAgainstStaleChampionIsAllowedWithWarning() {
        // 비교 대상 챔피언이 현재 ACTIVE와 다르면(그새 챔피언 교체) 승급을 막지 않고 경고만.
        Map<String, Object> cmp = comparison(
                "verdict", "CHAMPION_BETTER",
                "champion", "home-parts-old-v0",
                "holdoutOverlapWithChampion", 0.0);
        String warning = RecommendationTrainingService.evaluatePromotionVerdict(cmp, "home-parts-active-v1");
        assertThat(warning).isNotNull().contains("공정 비교 조건");
    }

    @Test
    void championBetterWithHoldoutOverlapIsAllowedWithWarning() {
        // holdout이 챔피언 학습 구간과 겹쳐(불공정) overlap>0이면 강제 차단하지 않는다.
        Map<String, Object> cmp = comparison(
                "verdict", "CHAMPION_BETTER",
                "champion", "home-parts-active-v1",
                "holdoutOverlapWithChampion", 0.42);
        assertThat(RecommendationTrainingService.evaluatePromotionVerdict(cmp, "home-parts-active-v1"))
                .isNotNull();
    }

    @Test
    void championBetterWithMissingOverlapKeyIsAllowed() {
        // overlap 키 부재(구 워커)면 공정 비교 여부 불명 → 차단하지 않음(안전측).
        Map<String, Object> cmp = comparison(
                "verdict", "CHAMPION_BETTER",
                "champion", "home-parts-active-v1");
        assertThat(RecommendationTrainingService.evaluatePromotionVerdict(cmp, "home-parts-active-v1"))
                .isNotNull();
    }

    @Test
    void challengerBetterHasNoWarning() {
        Map<String, Object> cmp = comparison("verdict", "CHALLENGER_BETTER", "champion", "home-parts-active-v1");
        assertThat(RecommendationTrainingService.evaluatePromotionVerdict(cmp, "home-parts-active-v1")).isNull();
    }

    @Test
    void absentVerdictHasNoGate() {
        // 구모델(비교 미기록): 게이트 없음.
        assertThat(RecommendationTrainingService.evaluatePromotionVerdict(comparison(), "home-parts-active-v1"))
                .isNull();
    }

    @Test
    void insufficientDataAndInconclusiveAreAllowedWithWarning() {
        assertThat(RecommendationTrainingService.evaluatePromotionVerdict(
                comparison("verdict", "INSUFFICIENT_DATA"), "home-parts-active-v1"))
                .isNotNull().contains("verdict=INSUFFICIENT_DATA");
        assertThat(RecommendationTrainingService.evaluatePromotionVerdict(
                comparison("verdict", "INCONCLUSIVE"), null))
                .isNotNull().contains("verdict=INCONCLUSIVE");
    }

    // ---- M6 servingSchemaMismatch ----

    @Test
    void identicalSchemasAreCompatible() {
        assertThat(RecommendationTrainingService.servingSchemaMismatch(
                List.of("a", "b", "c"), List.of("a", "b", "c"))).isFalse();
    }

    @Test
    void differentSchemasMismatch() {
        assertThat(RecommendationTrainingService.servingSchemaMismatch(
                List.of("a", "b", "c"), List.of("a", "b", "d"))).isTrue();
    }

    @Test
    void reorderedSchemaMismatches() {
        // FEATURES 순서가 모델 벡터 순서라 순서가 다르면 스큐로 본다.
        assertThat(RecommendationTrainingService.servingSchemaMismatch(
                List.of("a", "b"), List.of("b", "a"))).isTrue();
    }

    @Test
    void missingEitherSchemaIsNotBlocked() {
        // 어느 한쪽 스키마를 확정할 수 없으면 판정 불능 → 차단하지 않는다(안전측).
        assertThat(RecommendationTrainingService.servingSchemaMismatch(List.of(), List.of("a"))).isFalse();
        assertThat(RecommendationTrainingService.servingSchemaMismatch(List.of("a"), List.of())).isFalse();
    }
}
