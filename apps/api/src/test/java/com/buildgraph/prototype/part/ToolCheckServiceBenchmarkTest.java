package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.buildgraph.prototype.parts.tool.ToolBuildPart;
import com.buildgraph.prototype.parts.tool.ToolQuery;
import com.buildgraph.prototype.parts.tool.ToolRepository;
import com.buildgraph.prototype.parts.tool.ToolService;
import com.buildgraph.prototype.parts.util.PerformaceRule;

class ToolCheckServiceBenchmarkTest {
        
    @Test
    void performanceToolUsesLatestBenchmarkSummariesWhenAvailable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("FROM benchmark_summaries"), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of("part_id", 1L, "score", 86.5, "summary", "CPU category-local normalized score"),
                        Map.of("part_id", 2L, "score", 92.0, "summary", "GPU category-local normalized score")
        ));
        ToolRepository toolRepository = mock(ToolRepository.class);
        ToolQuery toolQuery = mock(ToolQuery.class);
        PerformaceRule performaceRule = new PerformaceRule(jdbcTemplate);
        ToolService service = new ToolService(jdbcTemplate, toolRepository, performaceRule, toolQuery);

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

}
