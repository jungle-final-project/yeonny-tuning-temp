package com.buildgraph.prototype.part.benchmark;

import com.buildgraph.prototype.part.util.PartQueryUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BenchmarkQueryCached {

    public static final String CACHE_NAME = "benchmark-summary";

    /**
     * '벤치마크 행 없음'을 캐시에 남기는 센티널. 없는 부품(쿨러·케이스 등)이 매 요청 캐시 미스로
     * 배치 쿼리를 유발하던 것을 TTL 동안 봉인한다. 반환 맵에는 절대 포함하지 않는다 —
     * '없는 id는 결과 맵 미포함'이 호출부(ToolCheckService.benchmarkScore) 계약이다.
     */
    private static final Map<String, Object> NO_BENCHMARK = Map.of();

    private final CacheManager cacheManager;
    private final JdbcTemplate jdbcTemplate;

    public BenchmarkQueryCached(CacheManager cacheManager, JdbcTemplate jdbcTemplate) {
        this.cacheManager = cacheManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* Benchmark 스키마에 접근하는 쿼리 */
    public Map<Long, Map<String, Object>> latestBenchmarkInfos(List<Long> requestedPartIds) {
        /* 방어코드 */
        if (requestedPartIds == null || requestedPartIds.isEmpty()) {
            return Map.of();
        }

        /* 중복 처리 */
        List<Long> partIds = requestedPartIds.stream().filter(Objects::nonNull).distinct().toList();
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            return findBenchmarksByIds(partIds);
        }

        /* 순회하면서 캐싱 유무 검사 */
        Map<Long, Map<String, Object>> loaded = new HashMap<>();
        List<Long> missedIds = new ArrayList<>();
        for (Long partId : partIds) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = cache.get(partId, Map.class);
            if (cached == null) {
                missedIds.add(partId);
            } else if (!cached.isEmpty()) {
                loaded.put(partId, cached);
            }
            // 빈 맵 = negative 캐시 적중(벤치마크 없음) — 재조회도, 결과 포함도 하지 않는다.
        }

        /* missedIds가 있을 경우 — 조회 후 없는 id도 센티널로 캐시해 반복 미스를 막는다 */
        if (!missedIds.isEmpty()) {
            Map<Long, Map<String, Object>> fetched = findBenchmarksByIds(missedIds);
            for (Long partId : missedIds) {
                Map<String, Object> benchmark = fetched.get(partId);
                if (benchmark == null) {
                    cache.put(partId, NO_BENCHMARK);
                } else {
                    loaded.put(partId, benchmark);
                    cache.put(partId, benchmark);
                }
            }
        }
        return loaded;
    }

    /* 접근하는 Query 문 */
    private Map<Long, Map<String, Object>> findBenchmarksByIds(List<Long> partIds) {
        if (partIds.isEmpty()) {
            return Map.of();
        }

        String placeholders = String.join(", ", Collections.nCopies(partIds.size(), "?"));
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                        SELECT DISTINCT ON (part_id)
                                part_id,
                                summary,
                                score
                        FROM benchmark_summaries
                        WHERE part_id IN (
                        """ + placeholders + """
                        )
                            AND deleted_at IS NULL
                        ORDER BY part_id, created_at DESC, id DESC
                        """, partIds.toArray())
                .forEach(row -> result.put(PartQueryUtil.numberLong(row.get("part_id")), row));
        return result;
    }
}
