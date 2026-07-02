package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartAliasReviewService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> CATEGORIES = List.of("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");

    private final JdbcTemplate jdbcTemplate;

    public PartAliasReviewService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> listReviewItems(String status, String category, String targetField, String sourceType, Integer page, Integer size) {
        int safePage = page == null ? 0 : Math.max(0, page);
        int safeSize = size == null ? 20 : Math.max(1, Math.min(size, 100));
        List<Object> params = new ArrayList<>();
        String where = reviewWhere(status, category, targetField, sourceType, params);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT r.public_id::text AS id,
                       r.source_type,
                       r.category,
                       r.target_field,
                       r.alias_text,
                       r.raw_value,
                       r.canonical_suggestion,
                       r.message,
                       r.status,
                       r.resolution_note,
                       r.raw_payload,
                       r.created_at,
                       r.updated_at,
                       p.public_id::text AS part_id,
                       p.name AS part_name,
                       ar.public_id::text AS resolved_alias_rule_id
                FROM part_alias_review_items r
                LEFT JOIN parts p ON p.id = r.part_id
                LEFT JOIN part_alias_rules ar ON ar.id = r.resolved_alias_rule_id
                %s
                ORDER BY r.created_at DESC, r.id DESC
                LIMIT ? OFFSET ?
                """.formatted(where), append(params, safeSize, safePage * safeSize).toArray());
        Integer total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM part_alias_review_items r " + where,
                Integer.class,
                params.toArray()
        );
        return MockData.map(
                "items", rows.stream().map(this::reviewItem).toList(),
                "page", safePage,
                "size", safeSize,
                "total", total == null ? 0 : total
        );
    }

    public Map<String, Object> reviewSummary() {
        List<Map<String, Object>> byCategory = jdbcTemplate.queryForList("""
                SELECT coalesce(category, 'UNKNOWN') AS category,
                       count(*) AS open_count
                FROM part_alias_review_items
                WHERE status = 'OPEN'
                  AND deleted_at IS NULL
                GROUP BY coalesce(category, 'UNKNOWN')
                ORDER BY category
                """);
        List<Map<String, Object>> bySourceType = jdbcTemplate.queryForList("""
                SELECT source_type,
                       count(*) AS open_count
                FROM part_alias_review_items
                WHERE status = 'OPEN'
                  AND deleted_at IS NULL
                GROUP BY source_type
                ORDER BY source_type
                """);
        Integer openTotal = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM part_alias_review_items
                WHERE status = 'OPEN'
                  AND deleted_at IS NULL
                """, Integer.class);
        return MockData.map(
                "openTotal", openTotal == null ? 0 : openTotal,
                "byCategory", byCategory.stream().map(row -> MockData.map(
                        "category", DbValueMapper.string(row, "category"),
                        "openCount", DbValueMapper.integer(row, "open_count")
                )).toList(),
                "bySourceType", bySourceType.stream().map(row -> MockData.map(
                        "sourceType", DbValueMapper.string(row, "source_type"),
                        "openCount", DbValueMapper.integer(row, "open_count")
                )).toList()
        );
    }

    public Map<String, Object> listRules(String category, String targetField, Integer page, Integer size) {
        int safePage = page == null ? 0 : Math.max(0, page);
        int safeSize = size == null ? 50 : Math.max(1, Math.min(size, 100));
        List<Object> params = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        clauses.add("deleted_at IS NULL");
        if (normalize(category) != null) {
            clauses.add("category = ?");
            params.add(category(normalize(category)));
        }
        if (normalize(targetField) != null) {
            clauses.add("target_field = ?");
            params.add(normalize(targetField));
        }
        String where = "WHERE " + String.join(" AND ", clauses);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       category,
                       target_field,
                       alias_text,
                       canonical_value,
                       status,
                       source,
                       note,
                       created_at,
                       updated_at
                FROM part_alias_rules
                %s
                ORDER BY category ASC, target_field ASC, alias_text ASC
                LIMIT ? OFFSET ?
                """.formatted(where), append(params, safeSize, safePage * safeSize).toArray());
        Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM part_alias_rules " + where, Integer.class, params.toArray());
        return MockData.map(
                "items", rows.stream().map(this::rule).toList(),
                "page", safePage,
                "size", safeSize,
                "total", total == null ? 0 : total
        );
    }

    @Transactional
    public Map<String, Object> createRule(Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        String aliasText = requiredText(request.get("aliasText"), "aliasText가 필요합니다.");
        String ruleCategory = category(requiredText(request.get("category"), "category가 필요합니다."));
        String targetField = requiredText(request.get("targetField"), "targetField가 필요합니다.");
        String canonicalValue = requiredText(request.get("canonicalValue"), "canonicalValue가 필요합니다.");
        String note = text(request.get("note"));
        Map<String, Object> row = upsertRule(ruleCategory, targetField, aliasText, canonicalValue, "ADMIN_REVIEW", note);
        audit(admin, "PART_ALIAS_RULE_CREATED", "part_alias_rules", DbValueMapper.string(row, "id"), MockData.map(
                "category", ruleCategory,
                "targetField", targetField,
                "aliasText", aliasText,
                "canonicalValue", canonicalValue
        ));
        return rule(row);
    }

    @Transactional
    public Map<String, Object> resolveReviewItem(String id, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> item = findReviewItem(id);
        String aliasText = firstText(text(request.get("aliasText")), DbValueMapper.string(item, "alias_text"));
        String ruleCategory = category(firstText(text(request.get("category")), DbValueMapper.string(item, "category")));
        String targetField = firstText(text(request.get("targetField")), DbValueMapper.string(item, "target_field"));
        String canonicalValue = firstText(text(request.get("canonicalValue")), DbValueMapper.string(item, "canonical_suggestion"), DbValueMapper.string(item, "raw_value"));
        if (targetField == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "targetField가 필요합니다.");
        }
        if (aliasText == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "aliasText가 필요합니다.");
        }
        if (canonicalValue == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "canonicalValue가 필요합니다.");
        }
        String note = text(request.get("note"));
        Map<String, Object> rule = upsertRule(ruleCategory, targetField, aliasText, canonicalValue, "REVIEW_RESOLUTION", note);
        jdbcTemplate.update("""
                UPDATE part_alias_review_items
                SET status = 'RESOLVED',
                    resolution_note = ?,
                    resolved_alias_rule_id = (SELECT id FROM part_alias_rules WHERE public_id = ?::uuid),
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, note, DbValueMapper.string(rule, "id"), id);
        audit(admin, "PART_ALIAS_REVIEW_RESOLVED", "part_alias_review_items", id, MockData.map(
                "aliasRuleId", DbValueMapper.string(rule, "id"),
                "aliasText", aliasText,
                "canonicalValue", canonicalValue
        ));
        return getReviewItem(id);
    }

    @Transactional
    public Map<String, Object> ignoreReviewItem(String id, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        findReviewItem(id);
        String note = text(request == null ? null : request.get("note"));
        jdbcTemplate.update("""
                UPDATE part_alias_review_items
                SET status = 'IGNORED',
                    resolution_note = ?,
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, note, id);
        audit(admin, "PART_ALIAS_REVIEW_IGNORED", "part_alias_review_items", id, MockData.map("note", note));
        return getReviewItem(id);
    }

    public void queueReviewItem(String sourceType, String category, String targetField, String aliasText, String rawValue, String message, Map<String, Object> rawPayload) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO part_alias_review_items (
                      source_type,
                      category,
                      target_field,
                      alias_text,
                      raw_value,
                      message,
                      raw_payload,
                      created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, now())
                    ON CONFLICT DO NOTHING
                    """,
                    normalize(sourceType) == null ? "AI_BUILD_CHAT" : normalize(sourceType),
                    normalize(category),
                    normalize(targetField),
                    text(aliasText),
                    text(rawValue),
                    text(message),
                    json(rawPayload == null ? Map.of() : rawPayload)
            );
        } catch (RuntimeException ignored) {
            // Review queue failure must not block a user-facing recommendation response.
        }
    }

    public String canonicalValue(String category, String targetField, String aliasText) {
        if (normalize(category) == null || normalize(targetField) == null || normalize(aliasText) == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT canonical_value
                FROM part_alias_rules
                WHERE deleted_at IS NULL
                  AND status = 'ACTIVE'
                  AND lower(category) = lower(?)
                  AND lower(target_field) = lower(?)
                  AND lower(alias_text) = lower(?)
                ORDER BY updated_at DESC NULLS LAST, created_at DESC
                LIMIT 1
                """, category, targetField, aliasText);
        return rows.isEmpty() ? null : DbValueMapper.string(rows.get(0), "canonical_value");
    }

    private String reviewWhere(String status, String category, String targetField, String sourceType, List<Object> params) {
        List<String> clauses = new ArrayList<>();
        clauses.add("r.deleted_at IS NULL");
        if (normalize(status) != null) {
            clauses.add("r.status = ?");
            params.add(normalize(status));
        }
        if (normalize(category) != null) {
            clauses.add("r.category = ?");
            params.add(category(normalize(category)));
        }
        if (normalize(targetField) != null) {
            clauses.add("r.target_field = ?");
            params.add(normalize(targetField));
        }
        if (normalize(sourceType) != null) {
            clauses.add("r.source_type = ?");
            params.add(normalize(sourceType));
        }
        return "WHERE " + String.join(" AND ", clauses);
    }

    private Map<String, Object> findReviewItem(String id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT *
                FROM part_alias_review_items
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                LIMIT 1
                """, id);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "검수 항목을 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    private Map<String, Object> getReviewItem(String id) {
        return reviewItem(jdbcTemplate.queryForList("""
                SELECT r.public_id::text AS id,
                       r.source_type,
                       r.category,
                       r.target_field,
                       r.alias_text,
                       r.raw_value,
                       r.canonical_suggestion,
                       r.message,
                       r.status,
                       r.resolution_note,
                       r.raw_payload,
                       r.created_at,
                       r.updated_at,
                       p.public_id::text AS part_id,
                       p.name AS part_name,
                       ar.public_id::text AS resolved_alias_rule_id
                FROM part_alias_review_items r
                LEFT JOIN parts p ON p.id = r.part_id
                LEFT JOIN part_alias_rules ar ON ar.id = r.resolved_alias_rule_id
                WHERE r.public_id = ?::uuid
                LIMIT 1
                """, id).get(0));
    }

    private Map<String, Object> upsertRule(String category, String targetField, String aliasText, String canonicalValue, String source, String note) {
        try {
            return jdbcTemplate.queryForMap("""
                    INSERT INTO part_alias_rules (
                      category,
                      target_field,
                      alias_text,
                      canonical_value,
                      source,
                      note,
                      created_at,
                      updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, now(), now())
                    RETURNING public_id::text AS id,
                              category,
                              target_field,
                              alias_text,
                              canonical_value,
                              status,
                              source,
                              note,
                              created_at,
                              updated_at
                    """, category, targetField, aliasText, canonicalValue, source, note);
        } catch (DuplicateKeyException ignored) {
            jdbcTemplate.update("""
                    UPDATE part_alias_rules
                    SET canonical_value = ?,
                        source = ?,
                        note = ?,
                        updated_at = now()
                    WHERE deleted_at IS NULL
                      AND status = 'ACTIVE'
                      AND lower(category) = lower(?)
                      AND lower(target_field) = lower(?)
                      AND lower(alias_text) = lower(?)
                    """, canonicalValue, source, note, category, targetField, aliasText);
            return jdbcTemplate.queryForMap("""
                    SELECT public_id::text AS id,
                           category,
                           target_field,
                           alias_text,
                           canonical_value,
                           status,
                           source,
                           note,
                           created_at,
                           updated_at
                    FROM part_alias_rules
                    WHERE deleted_at IS NULL
                      AND status = 'ACTIVE'
                      AND lower(category) = lower(?)
                      AND lower(target_field) = lower(?)
                      AND lower(alias_text) = lower(?)
                    LIMIT 1
                    """, category, targetField, aliasText);
        }
    }

    private Map<String, Object> reviewItem(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "sourceType", DbValueMapper.string(row, "source_type"),
                "category", DbValueMapper.string(row, "category"),
                "targetField", DbValueMapper.string(row, "target_field"),
                "aliasText", DbValueMapper.string(row, "alias_text"),
                "rawValue", DbValueMapper.string(row, "raw_value"),
                "canonicalSuggestion", DbValueMapper.string(row, "canonical_suggestion"),
                "message", DbValueMapper.string(row, "message"),
                "status", DbValueMapper.string(row, "status"),
                "resolutionNote", DbValueMapper.string(row, "resolution_note"),
                "rawPayload", DbValueMapper.json(row, "raw_payload", Map.of()),
                "partId", DbValueMapper.string(row, "part_id"),
                "partName", DbValueMapper.string(row, "part_name"),
                "resolvedAliasRuleId", DbValueMapper.string(row, "resolved_alias_rule_id"),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "updatedAt", DbValueMapper.timestamp(row, "updated_at")
        );
    }

    private Map<String, Object> rule(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "category", DbValueMapper.string(row, "category"),
                "targetField", DbValueMapper.string(row, "target_field"),
                "aliasText", DbValueMapper.string(row, "alias_text"),
                "canonicalValue", DbValueMapper.string(row, "canonical_value"),
                "status", DbValueMapper.string(row, "status"),
                "source", DbValueMapper.string(row, "source"),
                "note", DbValueMapper.string(row, "note"),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "updatedAt", DbValueMapper.timestamp(row, "updated_at")
        );
    }

    private void audit(CurrentUserService.CurrentUser admin, String action, String targetType, String targetId, Object metadata) {
        if (admin == null || admin.internalId() == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (actor_user_id, action, target_type, target_id, metadata, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, now())
                """,
                admin.internalId(),
                action,
                targetType,
                targetId,
                json(metadata == null ? Map.of() : metadata)
        );
    }

    private static List<Object> append(List<Object> params, Object... values) {
        List<Object> result = new ArrayList<>(params);
        result.addAll(List.of(values));
        return result;
    }

    private static String category(String value) {
        String normalized = normalize(value);
        if (normalized == null || !CATEGORIES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "지원하지 않는 부품 category입니다.");
        }
        return normalized;
    }

    private static String requiredText(Object value, String message) {
        String text = text(value);
        if (text == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        }
        return text;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (text(value) != null) {
                return text(value);
            }
        }
        return null;
    }

    private static String normalize(String value) {
        String text = text(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? new LinkedHashMap<>() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
