package com.buildgraph.prototype.quote;

import com.buildgraph.prototype.common.ReadThroughTtlCache;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 사용자별 활성 견적초안(draft)의 읽기 캐시 — 부하 실측(2026-07-20 RDS Performance Insights)에서
 * 무릎 구간 DB 부하 상위가 전부 "draft 다시 읽기"였다(응답용 상세 0.281 + 호환평가용 파츠 0.131/0.064
 * + 서명 0.028 + 활성 draft 조회 0.033 ≈ top10 부하의 절반). 후보 GET·담기 PUT·검증 resolve가
 * 각자 매 요청 draft를 재조회하던 것을 사용자 키 캐시 1곳으로 모은다.
 *
 * <ul>
 *   <li>쓰기 경로(담기/AI 적용/수량/삭제 — 전부 {@link QuoteDraftQueryService})가 커밋 직후
 *       {@link #invalidate(Long)}를 호출해 같은 인스턴스에서는 read-your-writes가 유지된다.</li>
 *   <li>TTL(기본 15초)은 인스턴스 밖 변경(가격 일일 cron, 수평 확장 시 다른 인스턴스의 쓰기)의
 *       반영 지연 상한이다 — 기존 카탈로그·호환성 캐시와 같은 운영 전제.</li>
 *   <li>서명(signature)과 툴 파츠(toolParts)가 같은 light 스냅샷에서 파생되므로, 호환성 평가
 *       캐시 키(서명)와 평가 입력(파츠)이 서로 어긋나지 않는다.</li>
 * </ul>
 */
@Service
public class QuoteDraftReadCache {
    /** draft 항목의 최소 표현 — 서명·툴 파츠 파생에 필요한 것만 담는다. */
    public record LightItem(long partInternalId, String partPublicId, int quantity) {
    }

    /** 활성 draft의 light 스냅샷. draftInternalId가 null이면 활성 draft 없음. */
    public record LightDraft(Long draftInternalId, List<LightItem> items) {
        static final LightDraft EMPTY = new LightDraft(null, List.of());

        /**
         * draft 내용 서명 — (draft id, 항목별 내부 part_id×quantity). 담기/교체·AI 적용·수량 변경·
         * 삭제 전부가 서명을 바꾼다. draft가 없으면 고정 서명("no-draft").
         */
        public String signature() {
            if (draftInternalId == null) {
                return "no-draft";
            }
            StringBuilder signature = new StringBuilder().append(draftInternalId).append(':');
            items.stream()
                    .sorted(Comparator.comparingLong(LightItem::partInternalId))
                    .forEach(item -> signature.append(item.partInternalId()).append('x').append(item.quantity()).append(';'));
            return signature.toString();
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final PartQuery partQuery;
    private final ReadThroughTtlCache<Long, LightDraft> lightCache;
    private final ReadThroughTtlCache<Long, Map<String, Object>> responseCache;

    @Autowired
    public QuoteDraftReadCache(
            JdbcTemplate jdbcTemplate,
            PartQuery partQuery,
            @Value("${quote.draft-read-cache.ttl-seconds:15}") long ttlSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.partQuery = partQuery;
        this.lightCache = new ReadThroughTtlCache<>(Duration.ofSeconds(ttlSeconds), 4096);
        this.responseCache = new ReadThroughTtlCache<>(Duration.ofSeconds(ttlSeconds), 4096);
    }

    /** 테스트 편의 생성자 — TTL 0으로 캐시를 끄고(항상 로더 실행) 기존 jdbc 목 기반 테스트를 그대로 통과시킨다. */
    public QuoteDraftReadCache(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null, 0L);
    }

    /** 활성 draft의 light 스냅샷(없으면 EMPTY) — 서명·툴 파츠의 단일 출처. */
    public LightDraft lightDraft(Long userId) {
        return lightCache.get(userId, () -> loadLightDraft(userId));
    }

    /** 호환성 평가 캐시 키용 draft 내용 서명. */
    public String signature(Long userId) {
        return lightDraft(userId).signature();
    }

    /** 호환 평가·그래프 검증용 현재 draft 파츠 — 부품 본문은 기존 부품 캐시 로더를 재사용한다. */
    public List<ToolBuildPart> toolParts(Long userId) {
        LightDraft light = lightDraft(userId);
        if (light.items().isEmpty()) {
            return List.of();
        }
        List<String> orderedPublicIds = new ArrayList<>();
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (LightItem item : light.items()) {
            orderedPublicIds.add(item.partPublicId());
            quantities.put(item.partPublicId(), item.quantity());
        }
        return partQuery.partsForPublicIdQuantities(orderedPublicIds, quantities);
    }

    /** GET /quote-drafts/current 응답 캐시 — 조립은 호출자(QuoteDraftQueryService)가 소유한다. */
    public Map<String, Object> response(Long userId, Supplier<Map<String, Object>> loader) {
        return responseCache.get(userId, loader);
    }

    /**
     * draft 쓰기 직후 호출 — light·응답 캐시를 함께 비운다. 쓰기 응답은 캐시에 되쓰지 않는다
     * (동시 쓰기에서 늦게 저장된 오래된 스냅샷이 최신을 덮는 역전을 피한다 — 다음 읽기가 재적재).
     */
    public void invalidate(Long userId) {
        lightCache.remove(userId);
        responseCache.remove(userId);
    }

    private LightDraft loadLightDraft(Long userId) {
        // 활성 draft + 항목을 1왕복으로 — draft가 없으면 0행, 항목이 없으면 draft 행 1개(part NULL).
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT qd.id AS draft_internal_id,
                       p.id AS part_internal_id,
                       p.public_id::text AS part_public_id,
                       qdi.quantity
                FROM (
                  SELECT id
                  FROM quote_drafts
                  WHERE user_id = ?
                    AND status = 'ACTIVE'
                    AND deleted_at IS NULL
                  ORDER BY updated_at DESC, id DESC
                  LIMIT 1
                ) qd
                LEFT JOIN quote_draft_items qdi
                  ON qdi.quote_draft_id = qd.id
                 AND qdi.deleted_at IS NULL
                LEFT JOIN parts p
                  ON p.id = qdi.part_id
                 AND p.deleted_at IS NULL
                ORDER BY qdi.created_at ASC, qdi.id ASC
                """, userId);
        if (rows.isEmpty()) {
            return LightDraft.EMPTY;
        }
        Long draftId = longValue(rows.get(0).get("draft_internal_id"));
        List<LightItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long partInternalId = longValue(row.get("part_internal_id"));
            if (partInternalId == null) {
                continue;
            }
            Object quantity = row.get("quantity");
            items.add(new LightItem(
                    partInternalId,
                    String.valueOf(row.get("part_public_id")),
                    quantity instanceof Number number ? number.intValue() : 1
            ));
        }
        return new LightDraft(draftId, List.copyOf(items));
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }
}
