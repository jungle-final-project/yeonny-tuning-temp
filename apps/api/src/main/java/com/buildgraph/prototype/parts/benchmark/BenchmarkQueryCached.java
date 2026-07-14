package com.buildgraph.prototype.parts.benchmark;

import static com.buildgraph.prototype.parts.util.RuleValueReader.numberLong;

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

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class BenchmarkQueryCached {
    
    private final CacheManager cacheManager;
    private final JdbcTemplate jdbcTemplate;

    private static final String CACHE_NAME = "benchmark-summary";

    /* Benchmark 스키마에 접근하는 쿼리 */
    public Map<Long, Map<String, Object>> latestBenchmarkInfos(List<Long> prePartIds){
        /* 방어코드 */
        if (prePartIds == null || prePartIds.isEmpty()) {
            return Map.of();
        }

        /* 중복 처리 */
        List<Long> partIds = prePartIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        
        /* 캐싱 객체 가져오기 */
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            return findBenchMarksByIds(partIds);
        }

        /* 캐싱 유무에 따른 객체 저장하기 */
        Map<Long, Map<String, Object>> cachedBenchmarks = new HashMap<>();
        List<Long> missedIds = new ArrayList<>();

        /* 순회하면서 캐싱 유무 검사 */
        for(Long partId : partIds){
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = cache.get(partId, Map.class);

            if (cached != null) {
                cachedBenchmarks.put(partId, cached);
            } else {
                missedIds.add(partId);
            }            
        }

        /* missedIds가 있을 경우 */
        if(!missedIds.isEmpty()){
            Map<Long, Map<String, Object>> foundByQuery = findBenchMarksByIds(missedIds);

            foundByQuery.forEach((partId, benchmarkInfo) -> {
                cachedBenchmarks.put(partId, benchmarkInfo);
                cache.put(partId, benchmarkInfo);
            });
        }

        
        return cachedBenchmarks;
    }

    /* 접근하는 Query 문 */
    private Map<Long, Map<String, Object>> findBenchMarksByIds(List<Long> misseIds){
        /* List 형태를 String으로 => result 객체에 query로 넣기 */
        String placeholders = String.join(", ", Collections.nCopies(misseIds.size(), "?"));   
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
                        """, misseIds.toArray())
                .forEach(row -> result.put(numberLong(row.get("part_id")), row));
        
        return result;
    }
}
