package com.buildgraph.prototype.parts.tool;

import static com.buildgraph.prototype.parts.util.RuleValueReader.numberLong;
import static com.buildgraph.prototype.parts.util.RuleValueReader.objectMap;

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

    /* DB에서 특정 카테고리 부품 정보를 "리스트"로 가져오는 함수 */
    public List<ToolBuildPart> partsByPublicIds(List<String> partIds) {
        return partIds.stream()
                .map(this::partByPublicId)
                .toList();
    }

    /* draftId => partIds 목록 => parts 객체 리스트(캐싱) */
    public List<ToolBuildPart> partsByDraftIds(Long draftId) {
        return partIdsByDraftId(draftId).stream()
                .map(this::partByPublicId)
                .toList();
    }

    /* 부품을 개별 항목으로 가져오는 함수
       : 캐싱 적용.. id기반 단일 품목 정보 */
    @Cacheable(
        cacheNames = "tool-part",
        key = "#partId",
        unless = "#result == null"
    )
    public ToolBuildPart partByPublicId(String partId){
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

    /* draftId 기반 partIds 가져오기 */
    private List<String> partIdsByDraftId(Long draftId) {
        return jdbcTemplate.queryForList("""
                SELECT p.public_id::text
                FROM quote_draft_items qdi
                JOIN parts p ON p.id = qdi.part_id
                WHERE qdi.quote_draft_id = ?
                    AND qdi.deleted_at IS NULL
                    AND p.deleted_at IS NULL
                ORDER BY qdi.created_at ASC, qdi.id ASC
                """,
                String.class,
                draftId
            );
    }
}
