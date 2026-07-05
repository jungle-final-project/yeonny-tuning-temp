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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuoteDraftQueryService {
    private static final Set<String> MULTI_ITEM_CATEGORIES = Set.of("RAM", "STORAGE");

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public QuoteDraftQueryService(JdbcTemplate jdbcTemplate, CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    public Map<String, Object> current(String authorization) {
        Long userId = currentUserId(authorization);
        Map<String, Object> draft = activeDraft(userId);
        if (draft == null) {
            return emptyDraft();
        }
        return draftMap(draft);
    }

    @Transactional
    public Map<String, Object> putItem(String authorization, String partId, Map<String, Object> request) {
        Long userId = currentUserId(authorization);
        Map<String, Object> part = part(partId);
        String category = DbValueMapper.string(part, "category");
        int quantity = quantity(request.get("quantity"), category);
        Map<String, Object> draft = activeDraft(userId);
        Long draftId = draft == null ? createDraft(userId) : longValue(draft, "internal_id");
        upsertDraftItem(draftId, part, quantity);
        return draftMap(activeDraft(userId));
    }

    @Transactional
    public Map<String, Object> applyAiBuild(String authorization, Map<String, Object> request) {
        Long userId = currentUserId(authorization);
        if (!"REPLACE".equals(text(request.get("conflictPolicy")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conflictPolicy는 REPLACE만 지원합니다.");
        }
        List<ResolvedAiItem> items = resolveAiItems(request.get("items"));
        Map<String, Object> draft = activeDraft(userId);
        Long draftId = draft == null ? createDraft(userId) : longValue(draft, "internal_id");
        jdbcTemplate.update("""
                UPDATE quote_draft_items
                SET deleted_at = now(),
                    updated_at = now()
                WHERE quote_draft_id = ?
                  AND deleted_at IS NULL
                """, draftId);
        for (ResolvedAiItem item : items) {
            jdbcTemplate.update("""
                    INSERT INTO quote_draft_items (quote_draft_id, part_id, category, quantity, unit_price_at_add)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    draftId,
                    longValue(item.part(), "internal_id"),
                    item.category(),
                    item.quantity(),
                    DbValueMapper.integer(item.part(), "price"));
        }
        jdbcTemplate.update("UPDATE quote_drafts SET updated_at = now() WHERE id = ?", draftId);
        return draftMap(activeDraft(userId));
    }

    @Transactional
    public Map<String, Object> patchItem(String authorization, String partId, Map<String, Object> request) {
        Long userId = currentUserId(authorization);
        Map<String, Object> draft = requireActiveDraft(userId);
        Map<String, Object> part = part(partId);
        int quantity = quantity(request.get("quantity"), DbValueMapper.string(part, "category"));
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
        return draftMap(activeDraft(userId));
    }

    @Transactional
    public Map<String, Object> deleteItem(String authorization, String partId) {
        Long userId = currentUserId(authorization);
        Map<String, Object> draft = activeDraft(userId);
        if (draft == null) {
            return emptyDraft();
        }
        Map<String, Object> part = part(partId);
        jdbcTemplate.update("""
                UPDATE quote_draft_items
                SET deleted_at = now(),
                    updated_at = now()
                WHERE quote_draft_id = ?
                  AND part_id = ?
                  AND deleted_at IS NULL
                """, longValue(draft, "internal_id"), longValue(part, "internal_id"));
        return draftMap(activeDraft(userId));
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

    private Long createDraft(Long userId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO quote_drafts (user_id, name, status)
                VALUES (?, '셀프 견적', 'ACTIVE')
                RETURNING id
                """, Long.class, userId);
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."));
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
        jdbcTemplate.update("UPDATE quote_drafts SET updated_at = now() WHERE id = ?", draftId);
    }

    private void upsertSingleCategoryItem(Long draftId, Map<String, Object> part, int quantity) {
        int updated = jdbcTemplate.update("""
                UPDATE quote_draft_items
                SET part_id = ?,
                    quantity = ?,
                    unit_price_at_add = ?,
                    updated_at = now()
                WHERE quote_draft_id = ?
                  AND category = ?
                  AND deleted_at IS NULL
                """,
                longValue(part, "internal_id"),
                quantity,
                DbValueMapper.integer(part, "price"),
                draftId,
                DbValueMapper.string(part, "category"));
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO quote_draft_items (quote_draft_id, part_id, category, quantity, unit_price_at_add)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    draftId,
                    longValue(part, "internal_id"),
                    DbValueMapper.string(part, "category"),
                    quantity,
                    DbValueMapper.integer(part, "price"));
        }
    }

    private void upsertSamePartItem(Long draftId, Map<String, Object> part, int quantity) {
        int updated = jdbcTemplate.update("""
                UPDATE quote_draft_items
                SET quantity = ?,
                    unit_price_at_add = ?,
                    updated_at = now()
                WHERE quote_draft_id = ?
                  AND part_id = ?
                  AND deleted_at IS NULL
                """,
                quantity,
                DbValueMapper.integer(part, "price"),
                draftId,
                longValue(part, "internal_id"));
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO quote_draft_items (quote_draft_id, part_id, category, quantity, unit_price_at_add)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    draftId,
                    longValue(part, "internal_id"),
                    DbValueMapper.string(part, "category"),
                    quantity,
                    DbValueMapper.integer(part, "price"));
        }
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
