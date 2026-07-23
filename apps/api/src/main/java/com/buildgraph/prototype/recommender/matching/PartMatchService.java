package com.buildgraph.prototype.recommender.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;


@Service
public class PartMatchService {

    private final PartMatchQuery partMatchQuery;
    private final PartMatchGreedyBuilder partMatchGreedyBuilder;

    public PartMatchService(PartMatchQuery partMatchQuery, PartMatchGreedyBuilder partMatchGreedyBuilder) {
        this.partMatchQuery = partMatchQuery;
        this.partMatchGreedyBuilder = partMatchGreedyBuilder;
    }

    /* Ai Agent로부터 호출되는 "풀 견적" 함수 */
    public List<Map<String, Object>> matchFullBuild(
        Map<String, Object> preferences
    ){
        /* 순서는 다음과 같다:
           1. 사용자의 선호도 파싱하기
           2. 전체 부품 백터 가져오기
           3. 전체 부품의 선호도 기반 내적 계산
           4. greedy 기반 부품 선택 */
        double[] preferenceVector = parsePreferences(preferences);
        List<Map<String, Object>> partVectors = partMatchQuery.findAllPartVectors();
        List<Map<String, Object>> scoredParts = scoreAllParts(partVectors, preferenceVector);

        int budget = ((Number) preferences.get("budget")).intValue();

        return partMatchGreedyBuilder.greedyBuildOrchestrator(scoredParts, budget);
    }

    /* 단일 카테고리 부품 추천.. 순서는 다음과 같다:
        1. 사용자 선호 벡터 파싱
        2. 특정 품목 벡터 가져오기
        3. 내적 점수 계산
        4. 반환 */
    public List<Map<String, Object>> matchParts(
            String category, Map<String, Object> preferences, int limit
        ) {
        double[] preferenceVector = parsePreferences(preferences);
        int budget = ((Number) preferences.get("budget")).intValue();
        List<Map<String, Object>> partVectors =
                partMatchQuery.findPartVectorsByCategory(category, budget);
        List<Map<String, Object>> scoredParts = scoreAllParts(partVectors, preferenceVector);

        /* 내적 점수가 높은 부품 반환 */
        return scoredParts.stream()
            .sorted(
                Comparator.comparingDouble(
                    (Map<String, Object> part) ->
                        ((Number) part.get("match_score"))
                                .doubleValue()
                ).reversed()
            )
            .limit(limit)
            .toList();
    }

    /* 기존 견적에서 특정 부품 교체 => 검증하기 수행 */
    public Map<String, Object> matchReplacement(
            String category,
            List<Map<String, Object>> currentItems,
            Map<String, Object> preferences,
            int budget
    ) {
        /* 벡터 기준 내림차순 정렬된 후보군들: greedy */
        List<Map<String, Object>> candidates =
                matchParts(category, preferences, 20);

        /* 이를 순차적으로 검증 수행 => 통과 발생 시: 즉시 반환 */
        return partMatchGreedyBuilder.selectReplacement(
                category,
                currentItems,
                candidates,
                budget
        );
    }    

    /* ==== 여기서 부터 보조 함수 ==== */
    /* 성능·가성비 선호도 파싱 */
    private double[] parsePreferences(
            Map<String, Object> preferences
    ) {
        return new double[]{
                ((Number) preferences.get("performance")).doubleValue(),
                ((Number) preferences.get("value")).doubleValue()
        };
    }

    /* 모든 부품의 내적 점수 계산 */
    private List<Map<String, Object>> scoreAllParts(
            List<Map<String, Object>> partVectors,
            double[] preferenceVector
    ) {
        /* 내적된 점수를 저장할 객체
           객체 형태는 다음과 같다:
            {
                "part_id": 15L,
                "id": "부품-public-id",
                "category": "GPU",
                "performance_score": 0.85,
                "value_score": 0.72,
                "match_score": 0.81
            } */
        List<Map<String, Object>> scoredParts = new ArrayList<>();

        /* 각 파트를 순회하면서 내적 점수를 구한다 */
        for (Map<String, Object> part : partVectors) {
            /* 각 부품의 점수를 가져온다 */
            double performanceScore = ((Number) part.get("performance_score")).doubleValue();
            double valueScore = ((Number) part.get("value_score")).doubleValue();

            /* 내적 점수 구하는 식 */
            double matchScore =
                    preferenceVector[0] * performanceScore
                    + preferenceVector[1] * valueScore;

            Map<String, Object> scoredPart = new LinkedHashMap<>(part);

            /* 내적 점수 Map형태로 넣고, 이를 List로 add 한다 */
            scoredPart.put("match_score", matchScore);
            scoredParts.add(scoredPart);
        }

        return scoredParts;
    }
}
