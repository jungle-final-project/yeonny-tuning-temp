package com.buildgraph.prototype.recommender.matching;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

import com.buildgraph.prototype.common.DbValueMapper;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PartMatchQuery {
    private final JdbcTemplate jdbcTemplate;

    public List<Map<String, Object>> findAllPartVectors() {
        return jdbcTemplate.queryForList("""
                SELECT
                    p.id AS part_id,
                    p.public_id::text AS id,
                    p.category,
                    p.name,
                    p.manufacturer,
                    p.price,
                    p.attributes,
                    pv.performance_score,
                    pv.value_score
                FROM part_vectors pv
                JOIN parts p ON p.id = pv.part_id
                WHERE p.status = 'ACTIVE'
                    AND p.deleted_at IS NULL
                    /* toolReady와 무관하게 실제 GPU 부품이 아닌 액세서리는 추천에서 제외한다 */
                    AND (
                        p.category <> 'GPU'
                        OR p.name !~* '(GPU 없음|피규어|장식|모형|워터[[:space:]]*블럭|워터[[:space:]]*블록|WATER[[:space:]]*BLOCK|WATERBLOCK|백플레이트|브라켓)'
                    )
                ORDER BY p.category, p.id
                """)
                .stream()
                .map(row -> {
                    Map<String, Object> part = new LinkedHashMap<>(row);
                    part.put(
                            "attributes",
                            DbValueMapper.json(row, "attributes", Map.of())
                    );
                    return part;
                })
                .toList();
    }   

    /* 카테고리별 부품 벡터 조회 */
    public List<Map<String, Object>> findPartVectorsByCategory(
            String category,
            int budget
    ) {
        return jdbcTemplate.queryForList("""
                SELECT
                    p.id AS part_id,
                    p.public_id::text AS id,
                    p.category,
                    p.name,
                    p.manufacturer,
                    p.price,
                    p.attributes,
                    pv.performance_score,
                    pv.value_score
                FROM part_vectors pv
                JOIN parts p ON p.id = pv.part_id
                WHERE p.category = ?
                    AND p.price <= ?
                    AND p.status = 'ACTIVE'
                    AND p.deleted_at IS NULL
                    /* toolReady와 무관하게 실제 GPU 부품이 아닌 액세서리는 추천에서 제외한다 */
                    AND (
                        p.category <> 'GPU'
                        OR p.name !~* '(GPU 없음|피규어|장식|모형|워터[[:space:]]*블럭|워터[[:space:]]*블록|WATER[[:space:]]*BLOCK|WATERBLOCK|백플레이트|브라켓)'
                    )
                ORDER BY p.id
                """,
                category,
                budget
        )
        .stream()
        .map(row -> {
            Map<String, Object> part =
                    new LinkedHashMap<>(row);

            part.put(
                    "attributes",
                    DbValueMapper.json(
                            row,
                            "attributes",
                            Map.of()
                    )
            );

            return part;
        })
        .toList();
    }   
}
