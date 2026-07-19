package com.buildgraph.prototype.quote;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuoteDraftQueryService {
    // 견적에 같은 카테고리 상품을 여러 개 담을 수 있는 카테고리. 드래프트와 저장 견적(from-chat)이 같은 규칙을 공유한다.
    public static final Set<String> MULTI_ITEM_CATEGORIES = Set.of("RAM", "STORAGE");

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    // @Transactional이 응답 read-back(3-way JOIN)까지 감싸 커밋 전 락·커넥션을 오래 잡았다(idle-in-transaction 본류).
    // 쓰기 구간만 짧게 감싸 즉시 커밋하기 위해 프로그래매틱 트랜잭션을 쓴다.
    private final TransactionTemplate transactionTemplate;
    // draft 읽기 캐시 — 조회는 캐시를 타고, 이 클래스의 모든 쓰기 경로가 커밋 직후 무효화한다.
    private final QuoteDraftReadCache draftReadCache;

    public QuoteDraftQueryService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            PlatformTransactionManager transactionManager,
            QuoteDraftReadCache draftReadCache
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.draftReadCache = draftReadCache;
    }

    public Map<String, Object> current(String authorization) {
        Long userId = currentUserId(authorization);
        return draftReadCache.response(userId, () -> {
            Map<String, Object> draft = activeDraft(userId);
            if (draft == null) {
                return emptyDraft();
            }
            return draftMap(draft);
        });
    }

    public Map<String, Object> putItem(String authorization, String partId, Map<String, Object> request) {
        Long userId = currentUserId(authorization);
        Map<String, Object> part = part(partId);
        String category = DbValueMapper.string(part, "category");
        int quantity = quantity(request.get("quantity"), category);
        // draft touch(UPDATE ... RETURNING)가 응답용 draft 행을 함께 돌려준다 — 커밋 후
        // activeDraft 재조회(순수 중복 1쿼리)를 없앤다. items read-back은 여전히 트랜잭션 밖이다.
        Map<String, Object> draft = transactionTemplate.execute(status -> {
            Long draftId = ensureActiveDraftId(userId);
            upsertDraftItem(draftId, part, quantity);
            return touchDraftReturning(draftId);
        });
        // 커밋 직후 무효화 — 응답은 아래에서 직접 재조립하고(캐시에 되쓰지 않음), 다음 읽기가 재적재한다.
        draftReadCache.invalidate(userId);
        return draftMap(draft);
    }

    public Map<String, Object> applyAiBuild(String authorization, Map<String, Object> request) {
        Long userId = currentUserId(authorization);
        if (!"REPLACE".equals(text(request.get("conflictPolicy")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conflictPolicy는 REPLACE만 지원합니다.");
        }
        // 부품 해석·검증은 트랜잭션 진입 전에 끝낸다 — 실패해도 드래프트는 건드리지 않는다(기존 의미 유지).
        List<ResolvedAiItem> items = resolveAiItems(request.get("items"));
        Map<String, Object> draft = transactionTemplate.execute(status -> {
            Long draftId = ensureActiveDraftId(userId);
            jdbcTemplate.update("""
                    UPDATE quote_draft_items
                    SET deleted_at = now(),
                        updated_at = now()
                    WHERE quote_draft_id = ?
                      AND deleted_at IS NULL
                    """, draftId);
            // 카테고리 수만큼 왕복하던 INSERT를 배치 1회로 — 쓰기 트랜잭션 보유 시간을 줄인다.
            jdbcTemplate.batchUpdate("""
                    INSERT INTO quote_draft_items (quote_draft_id, part_id, category, quantity, unit_price_at_add)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    items.stream()
                            .map(item -> new Object[]{
                                    draftId,
                                    longValue(item.part(), "internal_id"),
                                    item.category(),
                                    item.quantity(),
                                    DbValueMapper.integer(item.part(), "price")
                            })
                            .toList());
            return touchDraftReturning(draftId);
        });
        draftReadCache.invalidate(userId);
        return draftMap(draft);
    }

    public Map<String, Object> patchItem(String authorization, String partId, Map<String, Object> request) {
        Long userId = currentUserId(authorization);
        Map<String, Object> draft = requireActiveDraft(userId);
        // 담긴 뒤 단종/비활성된 부품도 수량 변경·정리는 가능해야 한다 — 상태 무관 조회(신규 담기만 ACTIVE 요구).
        Map<String, Object> part = partAnyStatus(partId);
        int quantity = quantity(request.get("quantity"), DbValueMapper.string(part, "category"));
        // 쓰기가 단일 UPDATE라 그 자체로 원자적 — 트랜잭션 없이 자동 커밋(드래프트 touch 안 함, 기존 유지).
        int updated = jdbcTemplate.update("""
                UPDATE quote_draft_items
                SET quantity = ?,
                    updated_at = now()
                WHERE quote_draft_id = ?
                  AND part_id = ?
                  AND deleted_at IS NULL
                """, quantity, longValue(draft, "internal_id"), longValue(part, "internal_id"));
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "견적초안에 담긴 부품을 찾을 수 없습니다.");
        }
        draftReadCache.invalidate(userId);
        // 이 경로는 draft 행을 건드리지 않으므로 진입 시 읽은 행을 그대로 응답에 쓴다 — 재조회 중복 제거.
        return draftMap(draft);
    }

    public Map<String, Object> deleteItem(String authorization, String partId) {
        Long userId = currentUserId(authorization);
        Map<String, Object> draft = activeDraft(userId);
        if (draft == null) {
            return emptyDraft();
        }
        // 삭제는 부품의 판매 상태와 무관해야 한다 — 비활성 부품이 견적에 남아 지울 수 없는 잠금 방지.
        Map<String, Object> part = partAnyStatus(partId);
        // 쓰기가 단일 UPDATE(soft-delete)라 그 자체로 원자적 — 트랜잭션 없이 자동 커밋(드래프트 touch 안 함, 기존 유지).
        jdbcTemplate.update("""
                UPDATE quote_draft_items
                SET deleted_at = now(),
                    updated_at = now()
                WHERE quote_draft_id = ?
                  AND part_id = ?
                  AND deleted_at IS NULL
                """, longValue(draft, "internal_id"), longValue(part, "internal_id"));
        draftReadCache.invalidate(userId);
        // 이 경로는 draft 행을 건드리지 않으므로 진입 시 읽은 행을 그대로 응답에 쓴다 — 재조회 중복 제거.
        return draftMap(draft);
    }

    private Long currentUserId(String authorization) {
        return currentUserService.requireUser(authorization).internalId();
    }

    private Map<String, Object> activeDraft(Long userId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               status,
                               name,
                               created_at,
                               updated_at
                        FROM quote_drafts
                        WHERE user_id = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """, userId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> requireActiveDraft(Long userId) {
        Map<String, Object> draft = activeDraft(userId);
        if (draft == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 견적초안을 찾을 수 없습니다.");
        }
        return draft;
    }

    // 동시 첫 담기 2건이 둘 다 INSERT로 가면 부분 유니크(ux_quote_drafts_active_user) 23505로 500이 났다.
    // ON CONFLICT DO NOTHING 후 재조회로 승자 드래프트에 합류한다. conflict target의 WHERE는
    // V28 부분 인덱스 술어와 정확히 일치해야 arbiter로 추론된다.
    private Long ensureActiveDraftId(Long userId) {
        Map<String, Object> draft = activeDraft(userId);
        if (draft != null) {
            return longValue(draft, "internal_id");
        }
        List<Long> created = jdbcTemplate.queryForList("""
                INSERT INTO quote_drafts (user_id, name, status)
                VALUES (?, '셀프 견적', 'ACTIVE')
                ON CONFLICT (user_id) WHERE status = 'ACTIVE' AND deleted_at IS NULL DO NOTHING
                RETURNING id
                """, Long.class, userId);
        if (!created.isEmpty()) {
            return created.get(0);
        }
        return longValue(requireActiveDraft(userId), "internal_id");
    }

    private Map<String, Object> part(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE public_id = ?::uuid
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                // 비활성/삭제된 부품이면 어떤 부품이 실패했는지 프론트가 알 수 있도록 partId를 메시지에 포함한다
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "부품을 찾을 수 없습니다. (비활성 또는 삭제된 부품일 수 있습니다. partId=" + publicId + ")"));
    }

    // 삭제/수량 변경 경로 전용: 판매 상태(ACTIVE 여부)와 무관하게 이미 담긴 부품을 해석한다.
    private Map<String, Object> partAnyStatus(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE public_id = ?::uuid
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "부품을 찾을 수 없습니다. (partId=" + publicId + ")"));
    }

    private List<ResolvedAiItem> resolveAiItems(Object value) {
        List<Map<String, Object>> rows = objectMaps(value);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items는 1개 이상이어야 합니다.");
        }
        Set<String> categories = new LinkedHashSet<>();
        List<ResolvedAiItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String partId = text(row.get("partId"));
            if (partId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items[].partId는 필수입니다.");
            }
            Map<String, Object> part = part(partId);
            String partCategory = DbValueMapper.string(part, "category");
            String requestedCategory = text(row.get("category"));
            String category = requestedCategory == null ? partCategory : requestedCategory;
            if (!Objects.equals(category, partCategory)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items[].category가 부품 카테고리와 일치하지 않습니다.");
            }
            if (!categories.add(category)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI 조합 적용은 카테고리당 1개 부품만 허용합니다.");
            }
            items.add(new ResolvedAiItem(part, quantity(row.get("quantity"), category), category));
        }
        return items;
    }

    private void upsertDraftItem(Long draftId, Map<String, Object> part, int quantity) {
        String category = DbValueMapper.string(part, "category");
        if (MULTI_ITEM_CATEGORIES.contains(category)) {
            upsertSamePartItem(draftId, part, quantity);
        } else {
            upsertSingleCategoryItem(draftId, part, quantity);
        }
    }

    // draft touch와 응답용 draft 행 조회를 RETURNING 한 문장으로 합친다 — 커밋 후 activeDraft
    // 재조회를 없애면서 응답의 updatedAt은 touch 직후 값 그대로다.
    private Map<String, Object> touchDraftReturning(Long draftId) {
        return jdbcTemplate.queryForList("""
                UPDATE quote_drafts
                SET updated_at = now()
                WHERE id = ?
                RETURNING id AS internal_id, public_id::text AS id, status, name, created_at, updated_at
                """, draftId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    // UPDATE→0행→INSERT 수동 업서트는 동시 PUT 2건이 둘 다 INSERT로 가서 23505로 터졌다(부하 실측 500의 범인).
    // 부분 유니크 인덱스(V29 ux_quote_draft_items_active_single_category)를 arbiter로 지정한 원자적 업서트로 교체.
    // conflict target의 WHERE(IN 목록 포함)는 인덱스 술어와 정확히 일치해야 추론된다. DO UPDATE는 deleted_at을
    // 건드리지 않고, soft-delete 행은 인덱스 밖이라 충돌 대상이 아니다 — 부활 없음(기존 의미 유지).
    private void upsertSingleCategoryItem(Long draftId, Map<String, Object> part, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO quote_draft_items (quote_draft_id, part_id, category, quantity, unit_price_at_add)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (quote_draft_id, category)
                WHERE deleted_at IS NULL
                  AND category IN ('CPU', 'GPU', 'MOTHERBOARD', 'PSU', 'CASE', 'COOLER')
                DO UPDATE SET part_id = EXCLUDED.part_id,
                              quantity = EXCLUDED.quantity,
                              unit_price_at_add = EXCLUDED.unit_price_at_add,
                              updated_at = now()
                """,
                draftId,
                longValue(part, "internal_id"),
                DbValueMapper.string(part, "category"),
                quantity,
                DbValueMapper.integer(part, "price"));
    }

    // RAM·STORAGE는 같은 부품 행만 갱신(V29 ux_quote_draft_items_active_part arbiter).
    private void upsertSamePartItem(Long draftId, Map<String, Object> part, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO quote_draft_items (quote_draft_id, part_id, category, quantity, unit_price_at_add)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (quote_draft_id, part_id) WHERE deleted_at IS NULL
                DO UPDATE SET quantity = EXCLUDED.quantity,
                              unit_price_at_add = EXCLUDED.unit_price_at_add,
                              updated_at = now()
                """,
                draftId,
                longValue(part, "internal_id"),
                DbValueMapper.string(part, "category"),
                quantity,
                DbValueMapper.integer(part, "price"));
    }

    private Map<String, Object> draftMap(Map<String, Object> draft) {
        if (draft == null) {
            return emptyDraft();
        }
        List<Map<String, Object>> items = draftItems(longValue(draft, "internal_id"));
        int totalPrice = items.stream().mapToInt(item -> (Integer) item.get("lineTotal")).sum();
        int itemCount = items.stream().mapToInt(item -> (Integer) item.get("quantity")).sum();
        return MockData.map(
                "id", DbValueMapper.string(draft, "id"),
                "status", DbValueMapper.string(draft, "status"),
                "name", DbValueMapper.string(draft, "name"),
                "items", items,
                "totalPrice", totalPrice,
                "itemCount", itemCount,
                "createdAt", DbValueMapper.timestamp(draft, "created_at"),
                "updatedAt", DbValueMapper.timestamp(draft, "updated_at")
        );
    }

    private List<Map<String, Object>> draftItems(Long draftId) {
        return jdbcTemplate.queryForList("""
                        SELECT qdi.public_id::text AS id,
                               p.public_id::text AS part_id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price AS current_price,
                               p.attributes,
                               qdi.quantity,
                               qdi.unit_price_at_add,
                               qdi.created_at,
                               qdi.updated_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM quote_draft_items qdi
                        JOIN parts p ON p.id = qdi.part_id
                        LEFT JOIN part_external_offers peo
                          ON peo.part_id = p.id
                         AND peo.source = 'NAVER_SHOPPING_SEARCH'
                         AND peo.deleted_at IS NULL
                        WHERE qdi.quote_draft_id = ?
                          AND qdi.deleted_at IS NULL
                          AND p.deleted_at IS NULL
                        ORDER BY CASE p.category
                                   WHEN 'CPU' THEN 1
                                   WHEN 'MOTHERBOARD' THEN 2
                                   WHEN 'RAM' THEN 3
                                   WHEN 'GPU' THEN 4
                                   WHEN 'STORAGE' THEN 5
                                   WHEN 'PSU' THEN 6
                                   WHEN 'CASE' THEN 7
                                   WHEN 'COOLER' THEN 8
                                   ELSE 99
                                 END,
                                 qdi.id
                        """, draftId)
                .stream()
                .map(this::itemMap)
                .toList();
    }

    private Map<String, Object> itemMap(Map<String, Object> row) {
        int currentPrice = DbValueMapper.integer(row, "current_price");
        int quantity = DbValueMapper.integer(row, "quantity");
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "partId", DbValueMapper.string(row, "part_id"),
                "category", DbValueMapper.string(row, "category"),
                "name", DbValueMapper.string(row, "name"),
                "manufacturer", DbValueMapper.string(row, "manufacturer"),
                "quantity", quantity,
                "unitPriceAtAdd", DbValueMapper.integer(row, "unit_price_at_add"),
                "currentPrice", currentPrice,
                "lineTotal", currentPrice * quantity,
                "attributes", DbValueMapper.json(row, "attributes", Map.of()),
                "externalOffer", externalOffer(row),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "updatedAt", DbValueMapper.timestamp(row, "updated_at")
        );
    }

    private static Map<String, Object> externalOffer(Map<String, Object> row) {
        String source = DbValueMapper.string(row, "external_offer_source");
        if (source == null) {
            return null;
        }
        return MockData.map(
                "title", DbValueMapper.string(row, "external_offer_title"),
                "imageUrl", DbValueMapper.string(row, "external_offer_image_url"),
                "supplierName", DbValueMapper.string(row, "external_offer_supplier_name"),
                "offerUrl", DbValueMapper.string(row, "external_offer_url"),
                "lowPrice", DbValueMapper.integer(row, "external_offer_low_price"),
                "source", source,
                "refreshedAt", DbValueMapper.timestamp(row, "external_offer_refreshed_at")
        );
    }

    private static int quantity(Object value, String category) {
        Integer parsed = null;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null && !String.valueOf(value).isBlank()) {
            // 비숫자/소수/전각 문자열은 NumberFormatException으로 500이 났다. 사용자 입력 오류이므로 400으로 돌린다.
            try {
                parsed = Integer.valueOf(String.valueOf(value).trim());
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity 형식이 올바르지 않습니다.");
            }
        }
        int quantity = parsed == null ? 1 : parsed;
        if (quantity < 1 || quantity > 9) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity는 1 이상 9 이하이어야 합니다.");
        }
        if (!MULTI_ITEM_CATEGORIES.contains(category) && quantity != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이 카테고리는 수량 1개만 허용합니다.");
        }
        return quantity;
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            rows.add(objectMap(item));
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Map<String, Object> emptyDraft() {
        return MockData.map(
                "id", null,
                "status", "EMPTY",
                "name", "셀프 견적",
                "items", new ArrayList<>(),
                "totalPrice", 0,
                "itemCount", 0,
                "createdAt", null,
                "updatedAt", null
        );
    }

    private record ResolvedAiItem(Map<String, Object> part, int quantity, String category) {
    }
}
