package com.buildgraph.prototype.verification.tool;

import static com.buildgraph.prototype.verification.util.RuleValueReader.numberLong;
import static com.buildgraph.prototype.verification.util.RuleValueReader.objectMap;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.buildgraph.prototype.common.DbValueMapper;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class ToolQuery {
    
    private final JdbcTemplate jdbcTemplate;

    /* DB에서 특정 부품 정보를 "리스트"로 가져오는 함수 => 개별 List로 호출 */
    public List<ToolBuildPart> partsByPublicIds(List<String> partIds) {
        return partIds.stream()
                .map(this::partsByPublicId)
                .toList();
    }

    /* 부품을 개별 항목으로 가져오는 함수
       : 여기서 캐싱을 적용.. id기반 단일 DB 품목 정보 */
    @Cacheable(
        cacheNames = "tool-part",
        key = "#partId",
        unless = "#result == null"
    )
    public ToolBuildPart partsByPublicId(String partId){
        return jdbcTemplate.queryForObject("""
                        SELECT id AS internal_id,
                            public_id::text AS id,
                            category,
                            name,
                            manufacturer,
                            price,
                            attributes
                        FROM parts
                        WHERE public_id::text = ?
                        AND deleted_at IS NULL
                        """,             
                        (rs, rowNum) -> part(rs),
                    partId);
    }

    /* helper 함수들 */
    private ToolBuildPart part(ResultSet rs) throws SQLException {
        return new ToolBuildPart(
                numberLong(rs.getObject("internal_id")),
                rs.getString("id"),
                rs.getString("category"),
                rs.getString("name"),
                rs.getString("manufacturer"),
                rs.getInt("price"),
                objectMap(DbValueMapper.json(Map.of("attributes", rs.getObject("attributes")), "attributes", Map.of()))
        );
    }
}
