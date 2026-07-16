package com.buildgraph.prototype.part.query;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.part.tool.ToolBuildPart;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PartQuery {

    private final JdbcTemplate jdbcTemplate;
    private final PartQueryCachedLoader cachedLoader;

    public PartQuery(JdbcTemplate jdbcTemplate, PartQueryCachedLoader cachedLoader) {
        this.jdbcTemplate = jdbcTemplate;
        this.cachedLoader = cachedLoader;
    }

    /* public ID 기반 부품 조회는 이 진입점으로 통일한다. */
    public List<ToolBuildPart> partsByPublicIds(List<String> partIds) {
        return cachedLoader.partsByPublicIds(partIds);
    }

    /* 사용자별 최신 ACTIVE draft ID만 직접 찾고, 부품 본문은 공통 캐시 조회로 넘긴다. */
    public List<ToolBuildPart> partsByActiveDraftUserId(long userInternalId) {
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList("""
                SELECT id AS internal_id
                FROM quote_drafts
                WHERE user_id = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """, userInternalId);
        if (drafts.isEmpty()) {
            return List.of();
        }
        Object draftId = drafts.get(0).get("internal_id");
        return partsByDraftId(draftId instanceof Number number ? number.longValue() : null);
    }

    /* draft ID -> part ID/수량 -> 캐시 가능한 부품 본문 순서로 조회한다. */
    public List<ToolBuildPart> partsByDraftId(Long draftId) {
        List<Map<String, Object>> draftItems = jdbcTemplate.queryForList("""
                SELECT p.public_id::text AS part_id,
                        qdi.quantity
                FROM quote_draft_items qdi
                JOIN parts p ON p.id = qdi.part_id
                WHERE qdi.quote_draft_id = ?
                    AND qdi.deleted_at IS NULL
                    AND p.deleted_at IS NULL
                ORDER BY qdi.created_at ASC, qdi.id ASC
                """, draftId);
        if (draftItems.isEmpty()) {
            return List.of();
        }

        List<String> partIds = draftItems.stream().map(row -> String.valueOf(row.get("part_id"))).toList();
        List<ToolBuildPart> parts = cachedLoader.partsByPublicIds(partIds);
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (Map<String, Object> item : draftItems) {
            Object value = item.get("quantity");
            quantities.put(String.valueOf(item.get("part_id")), value instanceof Number number ? number.intValue() : 1);
        }
        return parts.stream()
                .map(part -> new ToolBuildPart(
                        part.internalId(),
                        part.publicId(),
                        part.category(),
                        part.name(),
                        part.manufacturer(),
                        part.price(),
                        part.attributes(),
                        quantities.getOrDefault(part.publicId(), 1)
                ))
                .toList();
    }


    /* cache miss ID의 부품·벤치마크·가격·외부 오퍼를 한 번에 조회한다. */
    public List<PartDetailDto> findAllByPublicIds(
            List<String> partIds
    ) {
        if (partIds.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(
                ", ",
                Collections.nCopies(partIds.size(), "?::uuid")
        );

        String sql = """
                SELECT
                    p.id AS internal_id,
                    p.public_id::text AS id,
                    p.category,
                    p.name,
                    p.manufacturer,
                    p.price,
                    p.status,
                    p.attributes,
                    1 AS quantity,

                    bs.summary AS benchmark_summary,
                    bs.score AS benchmark_score,

                    ps.price AS price_snapshot_price,
                    CASE
                        WHEN peo.low_price IS NOT NULL
                             AND peo.low_price = p.price
                            THEN peo.source
                        ELSE ps.source
                    END AS latest_price_source,
                    CASE
                        WHEN peo.low_price IS NOT NULL
                             AND peo.low_price = p.price
                            THEN peo.refreshed_at
                        ELSE ps.collected_at
                    END AS latest_price_collected_at,

                    peo.title AS external_offer_title,
                    peo.image_url AS external_offer_image_url,
                    peo.supplier_name AS external_offer_supplier_name,
                    peo.offer_url AS external_offer_url,
                    peo.low_price AS external_offer_low_price,
                    peo.source AS external_offer_source,
                    peo.refreshed_at AS external_offer_refreshed_at

                FROM parts p

                LEFT JOIN LATERAL (
                    SELECT
                        b.summary,
                        b.score
                    FROM benchmark_summaries b
                    WHERE b.part_id = p.id
                      AND b.deleted_at IS NULL
                    ORDER BY b.created_at DESC, b.id DESC
                    LIMIT 1
                ) bs ON true

                LEFT JOIN LATERAL (
                    SELECT
                        snapshot.price,
                        snapshot.source,
                        snapshot.collected_at
                    FROM price_snapshots snapshot
                    WHERE snapshot.part_id = p.id
                      AND snapshot.collected_at <= now()
                    ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                    LIMIT 1
                ) ps ON true

                LEFT JOIN LATERAL (
                    SELECT offer.*
                    FROM part_external_offers offer
                    WHERE offer.part_id = p.id
                      AND offer.deleted_at IS NULL
                    ORDER BY
                        CASE offer.source
                            WHEN 'NAVER_SHOPPING_SEARCH' THEN 1
                            WHEN 'ADMIN_MANUAL' THEN 2
                            ELSE 9
                        END,
                        offer.refreshed_at DESC,
                        offer.id DESC
                    LIMIT 1
                ) peo ON true

                WHERE p.public_id IN (%s)
                  AND p.deleted_at IS NULL
                """.formatted(placeholders);

        return jdbcTemplate.queryForList(sql, partIds.toArray())
                .stream()
                .map(this::toPartDetailDto)
                .toList();
    }

    /* 통합 객체로 변환을 하여 반환 */
    private PartDetailDto toPartDetailDto(
            Map<String, Object> row
    ) {
        return new PartDetailDto(
                com.buildgraph.prototype.part.util.PartQueryUtil.toolPart(row),
                DbValueMapper.string(row, "status"),
                PartQueryUtil.benchmark(row),
                PartQueryUtil.latestPrice(row),
                PartQueryUtil.externalOffer(row)
        );
    }
}
