package com.buildgraph.prototype.part.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

class BenchmarkQueryCachedTest {

    @Test
    void benchmarkMissesUseOneBatchQueryAndThenHitCache() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder().maximumSize(100));
        BenchmarkQueryCached query = new BenchmarkQueryCached(cacheManager, jdbcTemplate);

        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("part_id", 1L, "summary", "CPU benchmark", "score", 80),
                Map.of("part_id", 2L, "summary", "GPU benchmark", "score", 90)
        ));

        assertThat(query.latestBenchmarkInfos(List.of(1L, 2L))).containsOnlyKeys(1L, 2L);
        assertThat(query.latestBenchmarkInfos(List.of(2L, 1L))).containsOnlyKeys(1L, 2L);
        verify(jdbcTemplate, times(1)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void missingBenchmarkIsNegativeCachedAndNotRequeried() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder().maximumSize(100));
        BenchmarkQueryCached query = new BenchmarkQueryCached(cacheManager, jdbcTemplate);

        // 1L만 벤치마크 행 존재, 2L은 없음(쿨러·케이스류)
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                Map.of("part_id", 1L, "summary", "CPU benchmark", "score", 80)
        ));

        // 없는 id는 결과 맵에 미포함(호출부 계약), 재요청 시 negative 캐시가 흡수해 배치는 총 1회
        assertThat(query.latestBenchmarkInfos(List.of(1L, 2L))).containsOnlyKeys(1L);
        assertThat(query.latestBenchmarkInfos(List.of(1L, 2L))).containsOnlyKeys(1L);
        verify(jdbcTemplate, times(1)).queryForList(anyString(), any(Object[].class));
    }
}
