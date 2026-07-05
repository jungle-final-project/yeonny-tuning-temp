package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ToolCheckServiceBenchmarkTest {
    @Test
    void performanceToolUsesLatestBenchmarkSummariesWhenAvailable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("FROM benchmark_summaries"), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of("part_id", 1L, "score", 86.5, "summary", "CPU category-local normalized score"),
                        Map.of("part_id", 2L, "score", 92.0, "summary", "GPU category-local normalized score")
                ));
        ToolCheckService service = new ToolCheckService(jdbcTemplate);

        List<Map<String, Object>> results = service.checkBuild(List.of(
                new ToolBuildPart(1L, "cpu-public-id", "CPU", "Ryzen 9", "AMD", 800000, Map.of("socket", "AM5")),
                new ToolBuildPart(2L, "gpu-public-id", "GPU", "RTX 5080", "NVIDIA", 2300000, Map.of("vramGb", 16, "lengthMm", 340, "requiredSystemPowerW", 850)),
                new ToolBuildPart(3L, "psu-public-id", "PSU", "850W Gold", "FSP", 150000, Map.of("capacityW", 1000)),
                new ToolBuildPart(4L, "case-public-id", "CASE", "Airflow Case", "Fractal", 200000, Map.of("maxGpuLengthMm", 380, "maxCpuCoolerHeightMm", 180))
        ), 4000000);

        Map<String, Object> performance = results.stream()
                .filter(result -> "performance".equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) performance.get("details");

        assertThat(performance.get("status")).isEqualTo("PASS");
        assertThat(performance.get("confidence")).isEqualTo("HIGH");
        assertThat(details.get("benchmarkSource")).isEqualTo("benchmark_summaries");
        assertThat(details.get("cpuBenchmarkScore")).isEqualTo(86.5);
        assertThat(details.get("gpuBenchmarkScore")).isEqualTo(92.0);
        assertThat(details.get("guaranteePolicy")).isEqualTo("NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE");
    }

    @Test
    void performanceToolIncludesGameFpsEvidenceWhenGameContextMatches() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("FROM benchmark_summaries"), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of("part_id", 1L, "score", 86.5, "summary", "CPU category-local normalized score"),
                        Map.of("part_id", 2L, "score", 92.0, "summary", "GPU category-local normalized score")
                ));
        when(jdbcTemplate.queryForList(contains("FROM parts"), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of(
                                "internal_id", 1L,
                                "id", "cpu-public-id",
                                "category", "CPU",
                                "name", "AMD Ryzen 9 9950X3D",
                                "manufacturer", "AMD",
                                "price", 800000,
                                "attributes", Map.of("socket", "AM5")
                        ),
                        Map.of(
                                "internal_id", 2L,
                                "id", "gpu-public-id",
                                "category", "GPU",
                                "name", "RTX 5060 Ti",
                                "manufacturer", "NVIDIA",
                                "price", 700000,
                                "attributes", Map.of("vramGb", 8, "lengthMm", 300, "requiredSystemPowerW", 650)
                        )
                ));
        when(jdbcTemplate.queryForList(contains("FROM game_fps_benchmarks"), any(Object[].class)))
                .thenReturn(List.of(Map.ofEntries(
                        entry("id", "fps-row-id"),
                        entry("game_title", "PlayerUnknown's Battlegrounds"),
                        entry("game_key", "pubg"),
                        entry("resolution", "FHD"),
                        entry("graphics_preset", "ULTRA"),
                        entry("avg_fps", 213.0),
                        entry("one_percent_low_fps", 116.0),
                        entry("source_name", "HowManyFPS public GPU comparison"),
                        entry("source_url", "https://howmanyfps.com/graphics-cards/comparisons/nvidia-geforce-rtx-5080-mobile-vs-nvidia-geforce-rtx-5060-ti"),
                        entry("source_checked_at", "2026-07-01"),
                        entry("confidence", "MEDIUM"),
                        entry("metadata", """
                                {
                                  "gpuClass": "RTX_5060_TI",
                                  "cpuClass": "RYZEN_9_9950X3D",
                                  "hardwareScope": "GPU_COMPARISON_WITH_SELECTED_CPU",
                                  "guaranteePolicy": "NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE",
                                  "notes": "public reference"
                                }
                                """)
                )));
        ToolCheckService service = new ToolCheckService(jdbcTemplate);

        Map<String, Object> result = service.checkTool("performance", Map.of(
                "partIds", List.of("cpu-public-id", "gpu-public-id"),
                "context", Map.of("message", "QHD 배그 144Hz 목표")
        ));

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gameFpsEvidence = (List<Map<String, Object>>) details.get("gameFpsEvidence");

        assertThat(gameFpsEvidence).hasSize(1);
        assertThat(details.get("gameFpsEvidenceStatus")).isEqualTo("RESOLUTION_FALLBACK");
        assertThat(gameFpsEvidence.get(0).get("gameKey")).isEqualTo("pubg");
        assertThat(gameFpsEvidence.get(0).get("avgFps")).isEqualTo(213.0);
        assertThat(gameFpsEvidence.get(0).get("guaranteePolicy")).isEqualTo("NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE");
        @SuppressWarnings("unchecked")
        Map<String, Object> match = (Map<String, Object>) gameFpsEvidence.get(0).get("match");
        assertThat(match.get("gameMatched")).isEqualTo(true);
        assertThat(match.get("resolutionMatched")).isEqualTo(false);
        assertThat(match.get("gpuClassMatched")).isEqualTo(true);
        assertThat(match.get("cpuClassMatched")).isEqualTo(true);
        assertThat(match.get("evidenceExactness")).isEqualTo("GPU_CLASS_RESOLUTION_FALLBACK");
    }

    @Test
    void sizeToolFailsWhenCaseClearanceIsExceeded() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService service = new ToolCheckService(jdbcTemplate);

        List<Map<String, Object>> results = service.checkBuild(List.of(
                new ToolBuildPart(1L, "gpu-public-id", "GPU", "Oversized GPU", "BuildGraph", 2_000_000, Map.of("lengthMm", 360)),
                new ToolBuildPart(2L, "case-public-id", "CASE", "Compact Case", "BuildGraph", 150_000, Map.of(
                        "maxGpuLengthMm", 330,
                        "maxCpuCoolerHeightMm", 160
                )),
                new ToolBuildPart(3L, "cooler-public-id", "COOLER", "Tall Cooler", "BuildGraph", 120_000, Map.of("heightMm", 170))
        ), 3_000_000);

        Map<String, Object> size = results.stream()
                .filter(result -> "size".equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) size.get("details");

        assertThat(size.get("status")).isEqualTo("FAIL");
        assertThat(size.get("summary")).isEqualTo("케이스 장착 한계를 초과해 해당 조합은 장착할 수 없습니다.");
        assertThat(details.get("gpuHeadroomMm")).isEqualTo(-30);
        assertThat(details.get("coolerHeadroomMm")).isEqualTo(-10);
    }

    @Test
    void powerToolWarnsInsteadOfFailingWhenPsuMeetsVendorRecommendation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService service = new ToolCheckService(jdbcTemplate);

        // GPU 노드에 "권장 파워 750W"로 표시되는 값과 정확히 같은 750W PSU를 담은 구성.
        // 내부 추정 부하(690W)+120=810W에는 못 미치고 headroom(60W)<80이라 예전에는 FAIL(빨강)이 떴다.
        // 표시된 권장 파워를 충족했으므로 이제 FAIL이 아니라 WARN이어야 한다.
        List<Map<String, Object>> results = service.checkBuild(List.of(
                new ToolBuildPart(1L, "cpu-public-id", "CPU", "Ryzen 7", "AMD", 400_000, Map.of("socket", "AM5", "wattage", 200)),
                new ToolBuildPart(2L, "gpu-public-id", "GPU", "RTX 5070", "NVIDIA", 900_000, Map.of("wattage", 480, "requiredSystemPowerW", 750, "lengthMm", 300)),
                new ToolBuildPart(3L, "psu-public-id", "PSU", "750W Gold", "FSP", 150_000, Map.of("capacityW", 750)),
                new ToolBuildPart(4L, "case-public-id", "CASE", "Airflow Case", "Fractal", 160_000, Map.of("maxGpuLengthMm", 360, "maxCpuCoolerHeightMm", 180))
        ), 3_000_000);

        Map<String, Object> power = results.stream()
                .filter(result -> "power".equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) power.get("details");

        // 표시된 권장 파워(750W)와 담은 PSU 정격(750W)이 같은 상황: 예전에는 FAIL(빨강)이었으나 이제 WARN이어야 한다.
        assertThat(power.get("status")).isEqualTo("WARN");
        assertThat(details.get("vendorRecommendedPsuW")).isEqualTo(750);
        assertThat(details.get("psuRatedCapacityW")).isEqualTo(750);
    }

    private static Entry<String, Object> entry(String key, Object value) {
        return Map.entry(key, value);
    }
}
