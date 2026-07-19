package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HomePartSummaryMapperTest {
    @Test
    void categoryPartsKeepOnlyHomeDisplayFields() {
        Map<String, Object> categoryParts = Map.of("CPU", List.of(fullPart("cpu-1", "CPU")));

        Map<String, Object> result = HomePartSummaryMapper.categoryParts(categoryParts);

        Map<String, Object> part = castList(result.get("CPU")).get(0);
        assertThat(part)
                .containsEntry("id", "cpu-1")
                .containsEntry("category", "CPU")
                .containsEntry("name", "CPU part")
                .containsEntry("manufacturer", "BuildGraph")
                .containsEntry("price", 100000)
                .containsEntry("status", "ACTIVE")
                .doesNotContainKeys("benchmarkSummary", "latestPriceSource", "latestPriceCollectedAt");
        assertThat(castMap(part.get("attributes")))
                .containsOnly(
                        Map.entry("imageUrl", "https://example.com/cpu-1-attr.png"),
                        Map.entry("shortSpec", "home spec")
                );
        assertThat(castMap(part.get("externalOffer")))
                .containsOnly(Map.entry("imageUrl", "https://example.com/cpu-1.png"));
    }

    @Test
    void recommendedPartsKeepRecommendationMetadataAndSlimPart() {
        Map<String, Object> recommendedParts = Map.of(
                "items", List.of(Map.of(
                        "recommendationId", "home-part-gpu-1",
                        "rankPosition", 1,
                        "part", fullPart("gpu-1", "GPU"),
                        "scoreSource", "FALLBACK",
                        "modelVersion", "fallback-v1",
                        "reasonTags", List.of("popular", "freshPrice")
                )),
                "generatedAt", "2026-07-17T00:00:00Z",
                "fallbackUsed", true
        );

        Map<String, Object> result = HomePartSummaryMapper.recommendedParts(recommendedParts);

        assertThat(result.get("generatedAt")).isEqualTo("2026-07-17T00:00:00Z");
        assertThat(result.get("fallbackUsed")).isEqualTo(true);
        Map<String, Object> item = castList(result.get("items")).get(0);
        assertThat(item)
                .containsEntry("recommendationId", "home-part-gpu-1")
                .containsEntry("rankPosition", 1)
                .containsEntry("scoreSource", "FALLBACK");
        assertThat(castMap(item.get("part")))
                .containsEntry("id", "gpu-1")
                .doesNotContainKeys("benchmarkSummary", "latestPriceSource", "latestPriceCollectedAt");
    }

    private static Map<String, Object> fullPart(String id, String category) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("category", category),
                Map.entry("name", category + " part"),
                Map.entry("manufacturer", "BuildGraph"),
                Map.entry("price", 100000),
                Map.entry("status", "ACTIVE"),
                Map.entry("attributes", Map.of(
                        "imageUrl", "https://example.com/" + id + "-attr.png",
                        "shortSpec", "home spec",
                        "toolReady", true,
                        "socket", "AM5"
                )),
                Map.entry("benchmarkSummary", Map.of("summary", "fast", "score", 95)),
                Map.entry("latestPriceSource", "NAVER"),
                Map.entry("latestPriceCollectedAt", "2026-07-17T00:00:00Z"),
                Map.entry("externalOffer", Map.of(
                        "imageUrl", "https://example.com/" + id + ".png",
                        "offerUrl", "https://example.com/" + id,
                        "supplierName", "Demo Shop",
                        "source", "NAVER"
                ))
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
