package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PartQualityReportServiceTest {
    @Test
    void requiredSpecMissingUsesToolCheckFieldsAndAlternatives() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(argThat(sql -> sql != null && sql.contains("SELECT public_id::text AS id") && sql.contains("FROM parts"))))
                .thenReturn(List.of(
                        part("cpu-1", "CPU", "CPU", Map.of("socket", "AM5", "tdpW", 120, "toolReady", true)),
                        part("gpu-1", "GPU", "GPU", Map.of("gpuClass", "RTX_5070_TI", "lengthMm", 310, "wattage", 285, "vramGb", 16, "toolReady", true)),
                        part("storage-1", "STORAGE", "SSD", Map.of("toolReady", true))
                ));
        when(jdbcTemplate.queryForList(argThat(sql -> sql != null && sql.contains("JOIN benchmark_summaries"))))
                .thenReturn(List.of(
                        Map.of("part_id", "cpu-1"),
                        Map.of("part_id", "gpu-1"),
                        Map.of("part_id", "storage-1")
                ));
        when(jdbcTemplate.queryForList(argThat(sql -> sql != null && sql.contains("FROM part_alias_review_items") && sql.contains("GROUP BY"))))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(argThat(sql -> sql != null && sql.contains("game_fps_coverage_gaps")), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForList(argThat(sql -> sql != null && sql.contains("FROM part_alias_review_items") && sql.contains("LIMIT ?")), anyInt()))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(argThat(sql -> sql != null && sql.contains("FROM game_fps_coverage_gaps")), anyInt()))
                .thenReturn(List.of());

        Map<String, Object> report = new PartQualityReportService(jdbcTemplate).qualityReport();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories = (List<Map<String, Object>>) report.get("categories");
        Map<String, Object> gpu = category(categories, "GPU");
        Map<String, Object> storage = category(categories, "STORAGE");
        assertThat(gpu).containsEntry("requiredSpecMissing", 1);
        assertThat(storage).containsEntry("requiredSpecMissing", 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actionItems = (List<Map<String, Object>>) report.get("actionItems");
        assertThat(actionItems)
                .anySatisfy(item -> {
                    assertThat(item).containsEntry("type", "MISSING_REQUIRED_SPEC");
                    assertThat(String.valueOf(item.get("message"))).contains("requiredSystemPowerW");
                })
                .noneSatisfy(item -> assertThat(String.valueOf(item.get("message"))).contains("readMbps"));
    }

    private static Map<String, Object> part(String id, String category, String name, Map<String, Object> attributes) {
        return Map.of(
                "id", id,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "attributes", attributes
        );
    }

    private static Map<String, Object> category(List<Map<String, Object>> categories, String category) {
        return categories.stream()
                .filter(item -> category.equals(item.get("category")))
                .findFirst()
                .orElseThrow();
    }
}
