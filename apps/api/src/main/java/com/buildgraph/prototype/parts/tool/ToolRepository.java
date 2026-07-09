package com.buildgraph.prototype.parts.tool;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.buildgraph.prototype.common.DbValueMapper;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ToolRepository {

    private final JdbcTemplate jdbcTemplate;

    /* 실제로 DB에 Query 검색을 통해 수행
       id를 기준으로 DB에서 부품 객체(ToolBuildPart)를 가져옴 */
    public List<ToolBuildPart> partsByBuildId(String buildId) {
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               bi.price,
                               p.attributes
                        FROM build_items bi
                        JOIN builds b ON b.id = bi.build_id
                        JOIN parts p ON p.id = bi.part_id
                        WHERE b.public_id = ?::uuid
                        ORDER BY bi.id
                        """, buildId)
                .stream()
                .map(this::part)
                .toList();
    }

    private ToolBuildPart part(Map<String, Object> row) {
        return new ToolBuildPart(
                ((Number) row.get("internal_id")).longValue(),
                String.valueOf(row.get("id")),
                String.valueOf(row.get("category")),
                String.valueOf(row.get("name")),
                String.valueOf(row.get("manufacturer")),
                ((Number) row.get("price")).intValue(),
                objectMap(DbValueMapper.json(row, "attributes", Map.of()))
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
