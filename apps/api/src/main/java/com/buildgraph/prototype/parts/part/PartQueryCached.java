package com.buildgraph.prototype.parts.part;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.buildgraph.prototype.parts.tool.ToolBuildPart;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.cache.annotation.Cacheable;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PartQueryCached {

    private final JdbcTemplate jdbcTemplate;

    private final AtomicLong dbQueryCount = new AtomicLong();
    
    /* 부품을 개별 항목으로 가져오는 함수(이곳으로 단일화)
    : 캐싱 적용.. id기반 단일 품목 정보 */
    @Cacheable(
        cacheNames = "tool-part",
        key = "#partId",
        unless = "#result == null"
    )
    public ToolBuildPart partByPublicId(String partId) {
        dbQueryCount.incrementAndGet(); 
        
        return jdbcTemplate.queryForObject(
            """
            SELECT id AS internal_id,
                    public_id::text AS id,
                    category,
                    name,
                    manufacturer,
                    price,
                    attributes
            FROM parts
            WHERE public_id::text = ?
                AND status = 'ACTIVE'
                AND deleted_at IS NULL
            """,
            (rs, rowNum) -> PartQueryUtil.part(rs),
            partId
        );
    }

    /* db 접근 카운트 불러오기 */
    public long dbQueryCount() {
        return dbQueryCount.get();
    }

    /* db 접근 카운트 초기화하기 */
    public void resetDbQueryCount() {
        dbQueryCount.set(0);
    }
}
