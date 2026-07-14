package com.buildgraph.prototype.parts.part;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.parts.tool.ToolBuildPart;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
/* 캐싱 정책?에 관여하는 컴포넌트 입니다 */
public class PartQueryCachedLoader {
    
    private final CacheManager cacheManager;
    private final JdbcTemplate jdbcTemplate;

    private final AtomicLong dbQueryCount = new AtomicLong();

    public List<ToolBuildPart> partsByPublicIds(List<String> requestIds){
        if (requestIds == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "partIds가 필요합니다."
            );
        }

        List<String> normalizedRequestIds = requestIds.stream()
                .map(this::validateAndNormalizePartId)
                .toList();

        /* "tool-part" 캐시 객체를 불러오기 */
        Cache cache = cacheManager.getCache("tool-part");
        
        /* 1. 캐시 히트된 부품 정보를 저장
           2. 캐시 미스된 부품 id를 저장 */
        Map<String, ToolBuildPart> cachedParts = new HashMap<>();
        List<String> notCachedPartIds = new ArrayList<>();

        /* 순회하면서 ID 별 캐시 히트 or 미스 확인 */
        for(String partId : normalizedRequestIds.stream().distinct().toList()){
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


        Set<String> missingPartIds = normalizedRequestIds.stream()
                .filter(partId -> !cachedParts.containsKey(partId))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!missingPartIds.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "NOT_FOUND",
                    "활성 부품을 찾을 수 없습니다.",
                    Map.of("partIds", List.copyOf(missingPartIds))
            );
        }
        /* 반환은 요청한 순서대로 예쁘게? 다시 포장 */
        return normalizedRequestIds.stream()
                .map(cachedParts::get)
                .toList();

    }

    private String validateAndNormalizePartId(String partId) {
        if (partId == null || partId.isBlank()) {
            throw invalidPartId(partId);
        }

        try {
            return UUID.fromString(partId).toString();
        } catch (IllegalArgumentException exception) {
            throw invalidPartId(partId);
        }
    }

    private ApiException invalidPartId(String partId) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "partId는 UUID 형식이어야 합니다.",
                Map.of("partId", partId == null ? "null" : partId)
        );
    }

    /* 한꺼번에 batch로 가져오는 query 문 */
    private List<ToolBuildPart> findAllByPublicIds(List<String> partIds) {
        dbQueryCount.incrementAndGet();

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

    /* db 접근 카운트 불러오기 */
    public long dbQueryCount() {
        return dbQueryCount.get();
    }

    /* db 접근 카운트 초기화하기 */
    public void resetDbQueryCount() {
        dbQueryCount.set(0);
    }
}
