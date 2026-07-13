package com.buildgraph.prototype.parts.part;

import static com.buildgraph.prototype.parts.part.PartQueryUtil.orderBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.buildgraph.prototype.parts.part.PartService.SearchConditions;
import com.buildgraph.prototype.parts.tool.ToolBuildPart;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
/* Part 정보를 불러오기 위한 DB와의 접합부 입니다
   : 실제 DB와 상호작용은 PartQueryCached가 수행합니다 */
public class PartQuery {
    
    private final JdbcTemplate jdbcTemplate;
    private final PartQueryCached partQueryCached;

    /* DB에서 특정 카테고리 부품 정보를 "리스트"로 가져오는 함수 */
    public List<ToolBuildPart> partsByPublicIds(List<String> partIds) {
        return partIds.stream()
                .map(partQueryCached::partByPublicId)
                .toList();
    }

    /* DB에서 특정 조건들에 따라 부품 정보를 "리스트"로 가져옴 */
    public List<ToolBuildPart> partsBySearchConditions(SearchConditions search){
        /* 특정 조건에 맞는 부품 id를 순차적으로 먼저 가져온다: 1차 DB 방문 */
        List<String> partIds = partIdsBySearchConditions(search);

        return partIds.stream()
                .map(partQueryCached::partByPublicId)
                .toList();
    }

    /* draftId => partIds 목록 => parts 객체 리스트(캐싱) */
    public List<ToolBuildPart> partsByDraftIds(Long draftId) {
        return partIdsByDraftId(draftId).stream()
                .map(partQueryCached::partByPublicId)
                .toList();
    }

    /* helper 함수들 */
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

    /* partId를 조건에 따라 리스트 만들기 위해 쿼리문을 추가함 */
    private List<String> partIdsBySearchConditions(SearchConditions search) {
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder("""
                WHERE p.deleted_at IS NULL
                    AND p.status = 'ACTIVE'
                """);

        if (search.category() != null) {
            where.append("""
                    AND p.category = ?
                    """);
            params.add(search.category());
        }

        if (search.query() != null) {
            where.append("""
                    AND (p.name ILIKE ? OR p.manufacturer ILIKE ?)
                    """);
            String keyword = "%" + search.query() + "%";
            params.add(keyword);
            params.add(keyword);
        }

        if (search.minPrice() != null) {
            where.append("""
                    AND p.price >= ?
                    """);
            params.add(search.minPrice());
        }

        params.add(search.size());
        params.add(search.offset());

        String sql = """
                SELECT p.public_id::text
                FROM parts p
                %s
                ORDER BY %s
                LIMIT ? OFFSET ?
                """.formatted(where, orderBy(search.sort()));

        return jdbcTemplate.queryForList(Objects.requireNonNull(sql), String.class, params.toArray());
    }
}
