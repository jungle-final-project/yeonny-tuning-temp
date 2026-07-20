package com.buildgraph.prototype.recommender.partvector;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PartVectorQuery {
    
    private final JdbcTemplate jdbcTemplate;

    /* 조회되어 반환되는 객체 상태: 
       하나의 Map 기준으로, 부품과 이와 연계된 속성(벤치마크) 4개 가져옴
       */
    public List<Map<String, Object>> findAllVectorSources() {
        return jdbcTemplate.queryForList("""
                SELECT
                    p.id AS part_id,
                    p.category,
                    p.price,
                    benchmark.score AS benchmark_score
                FROM parts p
                JOIN LATERAL (
                    SELECT bs.score
                    FROM benchmark_summaries bs
                    WHERE bs.part_id = p.id
                        AND bs.deleted_at IS NULL
                        AND bs.score IS NOT NULL
                    ORDER BY bs.created_at DESC, bs.id DESC
                    LIMIT 1
                ) benchmark ON true
                WHERE p.status = 'ACTIVE'
                    AND p.deleted_at IS NULL
                    /* toolReady와 무관하게 실제 GPU 부품이 아닌 액세서리는 추천에서 제외한다 */
                    AND (
                        p.category <> 'GPU'
                        OR p.name !~* '(GPU 없음|피규어|장식|모형|워터[[:space:]]*블럭|워터[[:space:]]*블록|WATER[[:space:]]*BLOCK|WATERBLOCK|백플레이트|브라켓)'
                    )
                    AND p.price > 0
                ORDER BY p.category, p.id
                """);
    }

    /* 전체 재계산 전에 기존 벡터를 제거하여 제외된 상품의 오래된 벡터가 남지 않게 한다 */
    public int deleteAll() {
        return jdbcTemplate.update("DELETE FROM part_vectors");
    }

    /* Bach 저장을 수행하는 함수 */
    public int saveAll(List<Map<String, Object>> vectors) {
        if (vectors == null || vectors.isEmpty()) {
            return 0;
        }

        jdbcTemplate.batchUpdate("""
                INSERT INTO part_vectors (
                    part_id,
                    performance_score,
                    value_score,
                    calculated_at
                )
                VALUES (?, ?, ?, now())
                ON CONFLICT (part_id)
                DO UPDATE SET
                    performance_score = EXCLUDED.performance_score,
                    value_score = EXCLUDED.value_score,
                    calculated_at = now()
                """,
                vectors,
                100,
                this::setVectorParameters
        );

        return vectors.size();
    }

    /* 형변환... 각 column에 맞게 들어가도록 수행 */
    private void setVectorParameters(
            PreparedStatement preparedStatement,
            Map<String, Object> vector
    ) throws SQLException {
        preparedStatement.setLong(
                1,
                ((Number) vector.get("part_id")).longValue()
        );

        preparedStatement.setDouble(
                2,
                ((Number) vector.get("performance_score")).doubleValue()
        );

        preparedStatement.setDouble(
                3,
                ((Number) vector.get("value_score")).doubleValue()
        );
    }
}
