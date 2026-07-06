package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M4 shadow 비교 순수 계산 로직 테스트. */
class ShadowComparisonMetricsTest {

    private static Map<String, Object> features(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    @Test
    void baselineScoreReproducesDeterministicFormula() {
        // 50(benchmark) + 8(fps) + 10(image) + 5(offer) + 8(tool) + 8.333(age 5) - 0.5(price 1M) + 0.07(CPU)
        double score = ShadowComparisonMetrics.baselineScore(features(
                "part_benchmark_score", 50,
                "part_has_fps_coverage", true,
                "part_has_image", true,
                "part_has_offer", true,
                "part_tool_ready", true,
                "part_price_age_days", 5.0,
                "part_price", 1_000_000,
                "category", "CPU"));
        assertThat(score).isCloseTo(88.9033, within(0.01));
    }

    @Test
    void missingPriceAgeStoredAs999AddsZeroAgeTerm() {
        // part_price_age_days=999는 age 항이 0 → 5일 신선한 부품보다 낮게 나와야 한다.
        double fresh = ShadowComparisonMetrics.baselineScore(features("part_benchmark_score", 10, "part_price_age_days", 5.0, "category", "GPU"));
        double stale = ShadowComparisonMetrics.baselineScore(features("part_benchmark_score", 10, "part_price_age_days", 999.0, "category", "GPU"));
        assertThat(fresh).isGreaterThan(stale);
    }

    @Test
    void truthyHandlesBothBooleanAndIntegerForms() {
        // features는 HOME 경로(Boolean)와 훈련 스냅샷(0/1 Integer) 표현이 섞일 수 있다.
        double asBool = ShadowComparisonMetrics.baselineScore(features("part_has_image", true, "category", "CPU", "part_price_age_days", 999.0));
        double asInt = ShadowComparisonMetrics.baselineScore(features("part_has_image", 1, "category", "CPU", "part_price_age_days", 999.0));
        assertThat(asBool).isEqualTo(asInt);
    }

    @Test
    void inversionRateIsZeroForIdenticalOrderAndOneForReversed() {
        assertThat(ShadowComparisonMetrics.inversionRate(new double[]{3, 2, 1}, new double[]{3, 2, 1})).isZero();
        assertThat(ShadowComparisonMetrics.inversionRate(new double[]{3, 2, 1}, new double[]{1, 2, 3})).isEqualTo(1.0);
        // 3쌍 중 1쌍만 역전.
        assertThat(ShadowComparisonMetrics.inversionRate(new double[]{3, 2, 1}, new double[]{3, 1, 2}))
                .isCloseTo(1.0 / 3.0, within(1e-9));
    }

    @Test
    void inversionRateSkipsTiedPairs() {
        // baseline 전부 동점이면 유효 쌍 0 → 역전율 0.
        assertThat(ShadowComparisonMetrics.inversionRate(new double[]{1, 1, 1}, new double[]{3, 2, 1})).isZero();
    }

    @Test
    void top4ReplacementRate() {
        assertThat(ShadowComparisonMetrics.top4ReplacementRate(
                new double[]{10, 9, 8, 7, 6}, new double[]{10, 9, 8, 7, 6})).isZero();
        // model=[6,7,8,9,10] → top4 인덱스 {1,2,3,4}, baseline top4 {0,1,2,3} → 3 유지, 1 교체 → 0.25
        assertThat(ShadowComparisonMetrics.top4ReplacementRate(
                new double[]{10, 9, 8, 7, 6}, new double[]{6, 7, 8, 9, 10})).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void summarizeGroupsAndSkipsSingletons() {
        Map<String, Object> f = features("part_benchmark_score", 10, "category", "CPU", "part_price_age_days", 999.0);
        // g1: 후보 1개(무시), g2: 후보 3개(baseline과 model 순위 반대 → 역전율 1.0)
        List<ShadowComparisonMetrics.ShadowRow> rows = List.of(
                new ShadowComparisonMetrics.ShadowRow("g1", 5.0, f),
                new ShadowComparisonMetrics.ShadowRow("g2", 1.0, features("part_benchmark_score", 30, "category", "CPU", "part_price_age_days", 999.0)),
                new ShadowComparisonMetrics.ShadowRow("g2", 2.0, features("part_benchmark_score", 20, "category", "CPU", "part_price_age_days", 999.0)),
                new ShadowComparisonMetrics.ShadowRow("g2", 3.0, features("part_benchmark_score", 10, "category", "CPU", "part_price_age_days", 999.0))
        );
        Map<String, Object> summary = ShadowComparisonMetrics.summarize(rows, 2);
        assertThat(summary.get("scoredGroups")).isEqualTo(1);   // g1은 크기 1이라 제외
        assertThat(summary.get("scoredCandidates")).isEqualTo(3);
        assertThat(summary.get("totalGroups")).isEqualTo(2);
        // g2: baseline 점수는 benchmark 30>20>10, model 점수는 1<2<3 → 완전 역전
        assertThat((Double) summary.get("avgInversionRate")).isEqualTo(1.0);
    }

    @Test
    void summarizeWithNoValidGroupsReturnsNullRates() {
        Map<String, Object> summary = ShadowComparisonMetrics.summarize(
                List.of(new ShadowComparisonMetrics.ShadowRow("g1", 1.0, features("category", "CPU", "part_price_age_days", 999.0))), 2);
        assertThat(summary.get("scoredGroups")).isEqualTo(0);
        assertThat(summary.get("avgInversionRate")).isNull();
        assertThat(summary.get("avgTop4ReplacementRate")).isNull();
    }
}
