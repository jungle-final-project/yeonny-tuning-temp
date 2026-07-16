package com.buildgraph.prototype.part.query;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.util.PartQueryUtil;
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

@Component
/* 캐싱 정책?에 관여하는 컴포넌트 입니다 */
public class PartQueryCachedLoader {

    public static final String CACHE_NAME = "tool-part";

    private final CacheManager cacheManager;
    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong dbQueryCount = new AtomicLong();

    public PartQueryCachedLoader(CacheManager cacheManager, JdbcTemplate jdbcTemplate) {
        this.cacheManager = cacheManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ToolBuildPart> partsByPublicIds(List<String> requestIds) {
        if (requestIds == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "partIds가 필요합니다.");
        }
        if (requestIds.isEmpty()) {
            return List.of();
        }

        List<String> normalizedRequestIds = requestIds.stream()
                .map(this::validateAndNormalizePartId)
                .toList();
        Cache cache = cacheManager.getCache(CACHE_NAME);

        /* 1. 캐시에 있는 부품과 DB에서 다시 읽어야 하는 부품 ID를 분리한다. */
        Map<String, ToolBuildPart> loadedParts = new HashMap<>();
        List<String> missedPartIds = new ArrayList<>();
        for (String partId : normalizedRequestIds.stream().distinct().toList()) {
            ToolBuildPart cachedPart = cache == null ? null : cache.get(partId, ToolBuildPart.class);
            if (cachedPart == null) {
                missedPartIds.add(partId);
            } else {
                loadedParts.put(partId, cachedPart);
            }
        }

        /* 2. 캐시 miss는 ID마다 조회하지 않고 IN 쿼리 한 번으로 가져와 N+1을 막는다. */
        if (!missedPartIds.isEmpty()) {
            for (ToolBuildPart part : findAllByPublicIds(missedPartIds)) {
                loadedParts.put(part.publicId(), part);
                if (cache != null) {
                    cache.put(part.publicId(), part);
                }
            }
        }

        Set<String> missingPartIds = normalizedRequestIds.stream()
                .filter(partId -> !loadedParts.containsKey(partId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missingPartIds.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "NOT_FOUND",
                    "활성 부품을 찾을 수 없습니다.",
                    Map.of("partIds", List.copyOf(missingPartIds))
            );
        }

        /* 3. DB의 반환 순서가 아니라 호출자가 요청한 ID 순서대로 결과를 복원한다. */
        return normalizedRequestIds.stream().map(loadedParts::get).toList();
    }

    /* 한꺼번에 batch로 가져오는 query 문 */
    private List<ToolBuildPart> findAllByPublicIds(List<String> partIds) {
        dbQueryCount.incrementAndGet();
        String placeholders = String.join(", ", Collections.nCopies(partIds.size(), "?::uuid"));
        String sql = """
                SELECT id AS internal_id,
                        public_id::text AS id,
                        category,
                        name,
                        manufacturer,
                        price,
                        attributes,
                        1 AS quantity
                FROM parts
                WHERE public_id IN (%s)
                    AND status = 'ACTIVE'
                    AND deleted_at IS NULL
                """.formatted(placeholders);

                /* sql 본문을 삽입해 실제 조회 수행 */
        return jdbcTemplate.queryForList(Objects.requireNonNull(sql), partIds.toArray()).stream()
                .map(PartQueryUtil::toolPart)
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

    /* db 접근 카운트 불러오기 */
    public long dbQueryCount() {
        return dbQueryCount.get();
    }

    /* db 접근 카운트 초기화하기 */
    public void resetDbQueryCount() {
        dbQueryCount.set(0);
    }
}
