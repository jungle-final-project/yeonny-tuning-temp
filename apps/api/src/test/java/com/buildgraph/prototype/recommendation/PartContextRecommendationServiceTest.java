package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.buildgraph.prototype.common.MockData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PartContextRecommendationServiceTest {
    private final PartContextRecommendationService service = new PartContextRecommendationService();

    @Test
    void recommendsAtMostThreePassCandidatesAndKeepsWarnExcluded() {
        List<Map<String, Object>> rows = List.of(
                candidate("balanced", "PASS", 800_000, 82, 80, List.of("CPU")),
                candidate("value", "PASS", 760_000, 82, 75, List.of("CPU")),
                candidate("premium", "PASS", 900_000, 82, 88, List.of("CPU")),
                candidate("unbalanced", "PASS", 1_500_000, 82, 42, List.of("CPU")),
                candidate("warn", "WARN", 790_000, 82, 82, List.of("CPU"))
        );

        List<Map<String, Object>> result = service.annotate("GPU", rows);

        List<Map<String, Object>> recommended = result.stream()
                .filter(row -> row.containsKey("recommendation"))
                .toList();
        assertThat(recommended).hasSize(3);
        assertThat(recommended).extracting(row -> row.get("id"))
                .contains("balanced", "value", "premium")
                .doesNotContain("warn", "unbalanced");
        assertThat(recommendation(result, "balanced").get("rank")).isEqualTo(1);
    }

    @Test
    void doesNotRecommendWithoutASelectedRelatedCategory() {
        List<Map<String, Object>> rows = List.of(
                candidate("gpu", "PASS", 800_000, 82, 80, List.of("RAM"))
        );

        assertThat(service.annotate("GPU", rows).get(0)).doesNotContainKey("recommendation");
    }

    private static Map<String, Object> candidate(
            String id,
            String status,
            int price,
            int cpuScore,
            int gpuScore,
            List<String> selectedCategories
    ) {
        Map<String, Object> row = new LinkedHashMap<>(MockData.map(
                "id", id,
                "category", "GPU",
                "name", id,
                "price", price,
                "attributes", Map.of("vramGb", 16, "toolReady", true),
                "benchmarkSummary", MockData.map("summary", "benchmark", "score", gpuScore),
                "latestPriceSource", "NAVER_SHOPPING_SEARCH",
                "compatibility", MockData.map(
                        "status", status,
                        "statusLabel", status,
                        "summary", "candidate result",
                        "checkedTools", List.of("performance", "power")
                )
        ));
        List<Map<String, Object>> toolResults = new ArrayList<>();
        toolResults.add(MockData.map(
                "tool", "performance",
                "status", status,
                "details", MockData.map(
                        "cpuBenchmarkScore", cpuScore,
                        "gpuBenchmarkScore", gpuScore
                )
        ));
        toolResults.add(MockData.map(
                "tool", "power",
                "status", status,
                "details", MockData.map("ratedLoadPercent", 70)
        ));
        row.put("_candidateToolResults", toolResults);
        row.put("_recommendationContext", Map.of("selectedCategories", selectedCategories));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recommendation(List<Map<String, Object>> rows, String id) {
        return (Map<String, Object>) rows.stream()
                .filter(row -> id.equals(row.get("id")))
                .findFirst()
                .orElseThrow()
                .get("recommendation");
    }
}
