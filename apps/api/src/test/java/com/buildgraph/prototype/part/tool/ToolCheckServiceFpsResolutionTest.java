package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.part.benchmark.BenchmarkQueryCached;
import com.buildgraph.prototype.part.query.PartQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FPS 근거 조회에서 해상도는 선택 파라미터다.
 * 미지정일 때 타입 없는 NULL이 SQL 파라미터로 나가면 Postgres가 타입을 추론하지 못해 500이 났다
 * (라이브 재현: resolution 누락/빈값 → INTERNAL_ERROR, 값이 있으면 200).
 */
class ToolCheckServiceFpsResolutionTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PartQuery partQuery = mock(PartQuery.class);
    private final BenchmarkQueryCached benchmarkQuery = mock(BenchmarkQueryCached.class);
    private final ToolCheckService service = new ToolCheckService(jdbcTemplate, partQuery, benchmarkQuery);

    @Test
    void omittedResolutionSendsNoNullParameterAndKeepsPlaceholderCountAligned() {
        FpsQuery query = runPerformanceCheck(Map.of("game", "pubg"));

        // 해상도 미지정: 순위식이 상수로 대체되고 NULL 파라미터는 하나도 나가지 않는다.
        assertThat(query.sql()).contains("0 AS resolution_rank");
        assertThat(query.params()).doesNotContainNull();
        assertThat(countPlaceholders(query.sql())).isEqualTo(query.params().length);
    }

    @Test
    void blankResolutionIsTreatedAsOmittedInsteadOfFailing() {
        FpsQuery query = runPerformanceCheck(Map.of("game", "pubg", "resolution", "   "));

        assertThat(query.sql()).contains("0 AS resolution_rank");
        assertThat(query.params()).doesNotContainNull();
        assertThat(countPlaceholders(query.sql())).isEqualTo(query.params().length);
    }

    @Test
    void explicitResolutionKeepsRankingExpressionAndAlignedParameters() {
        FpsQuery query = runPerformanceCheck(Map.of("game", "pubg", "resolution", "4k"));

        assertThat(query.sql()).contains("resolution_rank");
        assertThat(query.sql()).doesNotContain("0 AS resolution_rank");
        assertThat(query.params()).contains("4K");
        assertThat(query.params()).doesNotContainNull();
        assertThat(countPlaceholders(query.sql())).isEqualTo(query.params().length);
    }

    private FpsQuery runPerformanceCheck(Map<String, Object> context) {
        when(partQuery.partsByPublicIds(anyList())).thenReturn(List.of(cpu(), gpu()));
        when(benchmarkQuery.latestBenchmarkInfos(anyList())).thenReturn(Map.of());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForList(sql.capture(), params.capture())).thenReturn(List.of());

        service.checkTool("performance", Map.of(
                "partIds", List.of("cpu-1", "gpu-1"),
                "context", context));

        int index = -1;
        for (int i = 0; i < sql.getAllValues().size(); i += 1) {
            if (sql.getAllValues().get(i).contains("game_fps_benchmarks")) {
                index = i;
                break;
            }
        }
        assertThat(index).as("FPS 근거 조회 SQL이 실행되어야 한다").isNotNegative();
        return new FpsQuery(sql.getAllValues().get(index), params.getAllValues().get(index));
    }

    private record FpsQuery(String sql, Object[] params) {
    }

    private static int countPlaceholders(String sql) {
        return (int) sql.chars().filter(character -> character == '?').count();
    }

    private static ToolBuildPart cpu() {
        return new ToolBuildPart(101L, "cpu-1", "CPU", "라이젠 9600X", "AMD", 300_000,
                Map.of("cpuClass", "RYZEN_5_9600X", "tdpW", 65), 1);
    }

    private static ToolBuildPart gpu() {
        return new ToolBuildPart(201L, "gpu-1", "GPU", "RTX 5060", "NVIDIA", 500_000,
                Map.of("gpuClass", "RTX_5060", "tdpW", 145), 1);
    }
}
