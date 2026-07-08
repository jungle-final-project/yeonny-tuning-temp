package com.buildgraph.prototype.part;

import static com.buildgraph.prototype.part.util.RuleValueReader.numberLong;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.buildgraph.prototype.common.DbValueMapper;

import static com.buildgraph.prototype.part.util.RuleValueReader.objectMap;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ToolQuery {
    
    private final JdbcTemplate jdbcTemplate;

    /* DB에서 특정 부품 정보를 가져오는 함수
       여기서 캐싱을 적용: id 기반 DB의 raw 데이터 불러오기 */
    public List<ToolBuildPart> partsByPublicIds(List<String> partIds) {
        String placeholders = String.join(", ", Collections.nCopies(partIds.size(), "?"));
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                            public_id::text AS id,
                            category,
                            name,
                            manufacturer,
                            price,
                            attributes
                        FROM parts
                        WHERE public_id::text IN (
                        """ + placeholders + """
                        )
                        AND deleted_at IS NULL
                        ORDER BY category, id
                        """, partIds.toArray())
                .stream()
                .map(this::part)
                .toList();
    }

    /* helper 함수들 */
    private ToolBuildPart part(Map<String, Object> row) {
        return new ToolBuildPart(
                numberLong(row.get("internal_id")),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                DbValueMapper.integer(row, "price"),
                objectMap(DbValueMapper.json(row, "attributes", Map.of()))
        );
    }
}
