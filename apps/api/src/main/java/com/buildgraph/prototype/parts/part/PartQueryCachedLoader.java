package com.buildgraph.prototype.parts.part;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.buildgraph.prototype.parts.tool.ToolBuildPart;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
/* 캐싱 정책?에 관여하는 컴포넌트 입니다 */
public class PartQueryCachedLoader {
    
    private final CacheManager cacheManager;
    private final JdbcTemplate jdbcTemplate;

    public List<ToolBuildPart> partsByPublicIds(List<String> requestIds){
        /* "tool-part" 캐시 객체를 불러오기 */
        Cache cache = cacheManager.getCache("tool-part");
        
        /* 1. 캐시 히트된 부품 정보를 저장
           2. 캐시 미스된 부품 id를 저장 */
        Map<String, ToolBuildPart> cachedParts = new HashMap<>();
        List<String> notCachedPartIds = new ArrayList<>();

        /* 순회하면서 ID 별 캐시 히트 or 미스 확인 */
        for(String partId : requestIds.stream().distinct().toList()){
            /* 1. 해당 ID 캐시 정보를 가져오기 */
            ToolBuildPart cachedPart = null;
            if(cache != null){
                cachedPart = cache.get(partId, ToolBuildPart.class);
            }

            /* 2. 실제 캐시됨 유무에 따라 각 분기에서 객체에 넣기 */
            if(cachedPart != null){
                cachedParts.put(partId, cachedPart);
            }else{
                notCachedPartIds.add(partId);
            }
        }

        if(!notCachedPartIds.isEmpty()){
            /* 캐시 미스된 부품 일괄 조회: DB 호출 */
            List<ToolBuildPart> willBeCachedParts = findAllByPublicIds(notCachedPartIds);

            /* 조회한 부품들을 개별 캐싱 수행 => cachedParts에 넣기 */
            for(ToolBuildPart part : willBeCachedParts){
                cachedParts.put(part.publicId(), part);

                if (cache != null) {
                    cache.put(part.publicId(), part);
                }
            }
        }

        /* 반환은 요청한 순서대로 예쁘게? 다시 포장 */
        return requestIds.stream()
                .map(cachedParts::get)
                .filter(Objects::nonNull)
                .toList();

    }

    /* 한꺼번에 batch로 가져오는 query 문 */
    private List<ToolBuildPart> findAllByPublicIds(List<String> partIds) {
        String placeholders = String.join(
                ", ", Collections.nCopies(partIds.size(), "?::uuid")
        );

        String sql = """
                SELECT id AS internal_id,
                    public_id::text AS id,
                    category,
                    name,
                    manufacturer,
                    price,
                    attributes
                FROM parts
                WHERE public_id IN (%s)
                AND status = 'ACTIVE'
                AND deleted_at IS NULL
                """.formatted(placeholders);

        /* sql 본문을 삽입해 실제 조회 수행 */
        return jdbcTemplate.query(
                Objects.requireNonNull(sql),
                (rs, rowNum) -> PartQueryUtil.part(rs),
                partIds.toArray()
        );
    }
}
