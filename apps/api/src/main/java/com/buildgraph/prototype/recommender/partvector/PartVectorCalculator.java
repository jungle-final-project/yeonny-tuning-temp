package com.buildgraph.prototype.recommender.partvector;

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PartVectorCalculator {

    private final PartVectorQuery partVectorQuery;

    @Transactional
    public Integer recalculateAll() {
        /* DB에서 쿼리로 모든 부품 가져오기 */
        List<Map<String, Object>> parts = partVectorQuery.findAllVectorSources();

        /* 가져온 부품을 품목별로 분류하기 */
        Map<String, List<Map<String, Object>>> partsByCategory =
                parts.stream()
                        .collect(Collectors.groupingBy(
                                part -> (String) part.get("category")
                        ));
                        
        /* 계산될 백터를 저장할 객체 생성 */
        List<Map<String, Object>> vectors = new ArrayList<>();

        /* 각 품목을 순회하면서 이에 맞게 최소/최대값 산출하기 */
        for (List<Map<String, Object>> categoryParts : partsByCategory.values()) {

            /* 퍼포먼스와 가성비 수치를 계산하기 위해 뽑아옴 */
            ToDoubleFunction<Map<String, Object>> performance =
                    part -> ((Number) part.get("benchmark_score"))
                            .doubleValue();

            ToDoubleFunction<Map<String, Object>> value =
                    part -> performance.applyAsDouble(part)
                            / ((Number) part.get("price"))
                            .doubleValue();

            /* 각각 순차적으로 바라보면서 최소/최대값 구하기 */
            double[] performanceRange = range(categoryParts, performance);
            double[] valueRange = range(categoryParts, value);

            /* 순회를 하면서 해당 품목 별로 부품의 백터값 산출하기 */
            for (Map<String, Object> part : categoryParts) {
                /* 정규화된 퍼포먼스 점수 */
                double performanceScore = normalize(
                        performance.applyAsDouble(part),
                        performanceRange[0],
                        performanceRange[1]
                );

                /* 정규화된 가성비 점수 */
                double valueScore = normalize(
                        value.applyAsDouble(part),
                        valueRange[0],
                        valueRange[1]
                );

                /* 소 단위 백터 객체 생성 => 집어넣기 */
                Map<String, Object> vector = new HashMap<>();
                vector.put("part_id", part.get("part_id"));
                vector.put("performance_score", performanceScore);
                vector.put("value_score", valueScore);

                vectors.add(vector);
            }
        }

        /* 제외된 상품의 과거 벡터가 남지 않도록 같은 트랜잭션에서 전체 교체한다 */
        partVectorQuery.deleteAll();
        return partVectorQuery.saveAll(vectors);
    }

    /* 해당 품목 list에서 최소/최대값을 구하는 함수 */
    private double[] range(
        List<Map<String, Object>> parts,
        ToDoubleFunction<Map<String, Object>> scoreFunction
    ) {
        DoubleSummaryStatistics statistics = parts.stream()
                .mapToDouble(scoreFunction)
                .summaryStatistics();

        if (statistics.getCount() == 0) {
            return new double[]{0.0, 0.0};
        }

        return new double[]{
                statistics.getMin(),
                statistics.getMax()
        };
    }

    /* 최소 최댓값을 기준으로 해당 점수 정규화 하기 */
    private double normalize(
            double value,
            double min,
            double max
    ) {
        if (Double.compare(min, max) == 0) {
            return 0.5;
        }

        return (value - min) / (max - min);
    }
}
