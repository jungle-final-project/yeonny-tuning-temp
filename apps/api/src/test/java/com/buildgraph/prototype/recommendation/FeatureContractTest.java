package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 학습-서빙 피처 계약(M6)의 정적 정합 테스트. 스코어러(tools/reranker_service.py FEATURES)와
 * 홈 서빙 빌더(HomePartRecommendationService.features)가 배출하는 피처 키가 어긋나면(감사 B2
 * price_age_days 스큐 부류) 이 테스트가 깨지도록 계약을 코드로 고정한다.
 *
 * 런타임 활성화 게이트(RecommendationTrainingService.assertServingSchemaCompatible)는 모델의
 * feature_schema와 스코어러 FEATURES를 대조하지만, 그것만으로는 "스코어러 FEATURES ↔ Java 서빙
 * 빌더"의 정적 드리프트를 못 잡는다. 이 테스트가 그 3번째 축을 담당한다.
 */
class FeatureContractTest {
    // tools/reranker_service.py FEATURES(16키)의 미러. 스코어러 계약을 바꾸면 이 목록도 함께 바꾸도록
    // 강제해, 워커와 API가 조용히 어긋나는 것을 막는다.
    private static final List<String> EXPECTED_SCORER_FEATURES = List.of(
            "part_price",
            "build_total_price",
            "part_benchmark_score",
            "part_tool_ready",
            "part_has_image",
            "part_has_offer",
            "part_price_age_days",
            "part_has_fps_coverage",
            "category_CPU",
            "category_GPU",
            "category_RAM",
            "category_MOTHERBOARD",
            "category_STORAGE",
            "category_PSU",
            "category_CASE",
            "category_COOLER");

    // 홈 서빙 빌더가 의도적으로 배출하지 않는 스코어러 피처(알려진 갭). 홈 부품 카드는 단일 부품
    // 맥락이라 빌드 총액이 없어 build_total_price가 학습 스냅샷·서빙 양쪽에서 항상 0이다 —
    // 예측을 왜곡하지 않는 무해한 갭이지만, 계약상 명시적으로 허용 목록에 둔다.
    // 새 피처가 이 갭에 조용히 추가되면(=서빙이 스코어러 피처를 빠뜨리면) 테스트가 깨진다.
    private static final Set<String> KNOWN_SERVING_GAPS = Set.of("build_total_price");

    private static List<String> servingFeatureKeys() {
        List<String> keys = new ArrayList<>(HomePartRecommendationService.SERVING_PART_FEATURE_KEYS);
        for (String category : HomePartRecommendationService.SERVING_CATEGORIES) {
            keys.add("category_" + category);
        }
        return keys;
    }

    @Test
    void servingKeysAreAllRealScorerFeatures() {
        // 서빙이 배출하는 모든 키는 스코어러가 아는 피처여야 한다(유령 피처 방지).
        assertThat(servingFeatureKeys()).allSatisfy(key ->
                assertThat(EXPECTED_SCORER_FEATURES).contains(key));
    }

    @Test
    void servingKeysPlusKnownGapsCoverTheScorerContract() {
        // 서빙 키 ∪ 알려진 갭 == 스코어러 FEATURES. 어느 한쪽이 바뀌면(피처 추가/삭제, 갭 미갱신)
        // 이 등식이 깨져 학습-서빙 스큐를 CI에서 즉시 드러낸다.
        List<String> covered = new ArrayList<>(servingFeatureKeys());
        covered.addAll(KNOWN_SERVING_GAPS);
        assertThat(covered).containsExactlyInAnyOrderElementsOf(EXPECTED_SCORER_FEATURES);
    }

    @Test
    void knownGapsAreNotAccidentallyServed() {
        // 알려진 갭은 실제로 서빙되지 않아야 한다(갭 목록이 현실과 일치하는지 검증).
        assertThat(servingFeatureKeys()).doesNotContainAnyElementsOf(KNOWN_SERVING_GAPS);
    }
}
