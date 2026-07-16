package com.buildgraph.prototype.part.query;

import com.buildgraph.prototype.common.ApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
/* 부품 세부 정보를 캐싱하도록 로직 구성 */
public class PartDetailCachedLoader {

    public static final String CACHE_NAME = "part-detail";

    private final CacheManager cacheManager;
    private final PartQuery partQuery;
    private final AtomicLong dbQueryCount = new AtomicLong();

    public PartDetailCachedLoader(
            CacheManager cacheManager,
            PartQuery partQuery
    ) {
        this.cacheManager = cacheManager;
        this.partQuery = partQuery;
    }

    /* 캐시 미스/히트로 분기하여 batch로 저장 및 조회를 수행한다 */
    public List<PartDetailDto> detailsByPublicIds(
            List<String> requestIds
    ) {

        /* 방어코드 1, 2 */
        if (requestIds == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "partIds가 필요합니다."
            );
        }

        if (requestIds.isEmpty()) {
            return List.of();
        }

        List<String> normalizedIds = requestIds.stream()
                .map(this::validateAndNormalizePartId)
                .toList();

        Cache cache = cacheManager.getCache(CACHE_NAME);

        Map<String, PartDetailDto> loadedDetails = new HashMap<>();
        List<String> missedIds = new ArrayList<>();

        /* ID별로 cache hit/miss를 분리한다. */
        for (String partId : normalizedIds.stream().distinct().toList()) {
            PartDetailDto cached = cache == null
                    ? null
                    : cache.get(partId, PartDetailDto.class);

            if (cached == null) {
                missedIds.add(partId);
            } else {
                loadedDetails.put(partId, cached);
            }
        }

        /* cache miss일 경우 일괄 조화 => DB 접근 */
        if (!missedIds.isEmpty()) {
            dbQueryCount.incrementAndGet();
            List<PartDetailDto> missedDetails =
                    partQuery.findAllByPublicIds(missedIds);

            for (PartDetailDto detail : missedDetails) {
                String publicId = detail.part().publicId();

                loadedDetails.put(publicId, detail);

                if (cache != null) {
                    cache.put(publicId, detail);
                }
            }
        }

        Set<String> missingIds = normalizedIds.stream()
                .filter(id -> !loadedDetails.containsKey(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!missingIds.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "NOT_FOUND",
                    "부품을 찾을 수 없습니다.",
                    Map.of("partIds", List.copyOf(missingIds))
            );
        }

        /* DB 반환 순서가 아니라 요청 ID 순서대로 결과를 복원한다. */
        return normalizedIds.stream()
                .map(loadedDetails::get)
                .toList();
    }

    private String validateAndNormalizePartId(
            String partId
    ) {
        if (partId == null || partId.isBlank()) {
            throw invalidPartId(partId);
        }

        try {
            return UUID.fromString(partId).toString();
        } catch (IllegalArgumentException exception) {
            throw invalidPartId(partId);
        }
    }

    private ApiException invalidPartId(
            String partId
    ) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "partId는 UUID 형식이어야 합니다.",
                Map.of(
                        "partId",
                        partId == null ? "null" : partId
                )
        );
    }

    public long dbQueryCount() {
        return dbQueryCount.get();
    }

    public void resetDbQueryCount() {
        dbQueryCount.set(0);
    }

}
