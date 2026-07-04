package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationLearningService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, Double> LABEL_SCORES = Map.ofEntries(
            Map.entry("IMPRESSION", 0.0),
            Map.entry("CLICK", 1.0),
            Map.entry("DETAIL_VIEW", 1.0),
            Map.entry("SAVE", 3.0),
            Map.entry("CHANGE_ADOPTED", 3.0),
            Map.entry("ADD_BUILD_TO_DRAFT", 3.0),
            Map.entry("ADD_PART_TO_DRAFT", 3.0),
            Map.entry("ORDER_INTENT", 5.0),
            Map.entry("REJECT", -1.0),
            Map.entry("CHANGE_REVERTED", -1.0),
            Map.entry("AS_CONFIRMED_NEGATIVE", -2.0),
            Map.entry("ADMIN_PROMOTE", 4.0),
            Map.entry("ADMIN_DEMOTE", -3.0)
    );

    private final JdbcTemplate jdbcTemplate;

    public RecommendationLearningService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> recordEvent(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        String eventType = normalizeEventType(text(request.get("eventType")));
        if ("AS_CONFIRMED_NEGATIVE".equals(eventType)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AS 확정 피드백은 관리자 API를 사용해야 합니다.");
        }
        if (eventType.startsWith("ADMIN_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 추천 피드백은 관리자 API를 사용해야 합니다.");
        }
        String idempotencyKey = text(request.get("idempotencyKey"));
        if (idempotencyKey != null) {
            Map<String, Object> existing = findEventByIdempotency(user.internalId(), idempotencyKey);
            if (!existing.isEmpty()) {
                return existing;
            }
        }
        Long partId = resolvePartId(text(request.get("partId")));
        Long buildId = resolveOwnedBuildId(text(request.get("buildId")), user.internalId());
        Long asTicketId = resolveOwnedAsTicketId(text(request.get("asTicketId")), user.internalId());
        return insertEvent(
                user.internalId(),
                null,
                eventType,
                label(eventType),
                firstText(text(request.get("sourceSurface")), "BUILD_CHAT"),
                text(request.get("recommendationId")),
                buildId,
                partId,
                asTicketId,
                text(request.get("category")),
                integer(request.get("rankPosition")),
                idempotencyKey,
                eventPayload(request)
        );
    }

    public Map<String, Object> confirmAsNegativeFeedback(
            String ticketPublicId,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        Map<String, Object> ticket = jdbcTemplate.queryForList("""
                        SELECT id,
                               user_id,
                               public_id::text AS public_id,
                               symptom,
                               status
                        FROM as_tickets
                        WHERE public_id = ?::uuid
                          AND deleted_at IS NULL
                        """, ticketPublicId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
        Long partId = resolvePartId(text(request.get("relatedPartId")));
        Long buildId = resolveOwnedBuildId(text(request.get("relatedBuildId")), longValue(ticket, "user_id"));
        String recommendationId = text(request.get("recommendationId"));
        boolean useForTraining = booleanValue(request.get("useForRecommendationTraining"), true);
        Map<String, Object> label = upsertAsTicketLabel(ticket, request, admin, partId, buildId, recommendationId, useForTraining);
        boolean hasTrainingLink = partId != null || buildId != null || recommendationId != null;
        if (!useForTraining || !hasTrainingLink) {
            Map<String, Object> response = new LinkedHashMap<>(label);
            response.put("event", null);
            response.put("trainingEventCreated", false);
            return response;
        }
        String idempotencyKey = "AS_CONFIRMED_NEGATIVE:" + ticket.get("id");
        Map<String, Object> existing = findEventByIdempotency(longValue(ticket, "user_id"), idempotencyKey);
        if (!existing.isEmpty()) {
            existing.put("label", label);
            existing.put("trainingEventCreated", false);
            return existing;
        }
        Map<String, Object> payload = new LinkedHashMap<>(eventPayload(request));
        payload.put("ticketId", ticketPublicId);
        payload.put("ticketStatus", ticket.get("status"));
        payload.put("ticketSymptom", ticket.get("symptom"));
        payload.put("confirmedByAdminId", admin.id());
        payload.put("asTicketLabelId", label.get("id"));
        Map<String, Object> event = insertEvent(
                longValue(ticket, "user_id"),
                admin.internalId(),
                "AS_CONFIRMED_NEGATIVE",
                label("AS_CONFIRMED_NEGATIVE"),
                "ADMIN_AS_FEEDBACK",
                recommendationId,
                buildId,
                partId,
                longValue(ticket, "id"),
                text(request.get("category")),
                null,
                idempotencyKey,
                payload
        );
        event.put("label", label);
        event.put("trainingEventCreated", true);
        return event;
    }

    public Map<String, Object> recordHomePartAdminFeedback(
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        String partPublicId = text(request.get("partId"));
        if (partPublicId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId는 필수입니다.");
        }
        Long partId = resolvePartId(partPublicId);
        String label = text(request.get("label"));
        String eventType;
        if ("PROMOTE".equalsIgnoreCase(label)) {
            eventType = "ADMIN_PROMOTE";
        } else if ("DEMOTE".equalsIgnoreCase(label)) {
            eventType = "ADMIN_DEMOTE";
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label은 PROMOTE 또는 DEMOTE여야 합니다.");
        }
        Map<String, Object> part = jdbcTemplate.queryForMap("""
                SELECT category,
                       name,
                       manufacturer
                FROM parts
                WHERE id = ?
                """, partId);
        Map<String, Object> payload = new LinkedHashMap<>(eventPayload(request));
        payload.put("label", label.toUpperCase(Locale.ROOT));
        payload.put("partName", part.get("name"));
        payload.put("manufacturer", part.get("manufacturer"));
        payload.put("createdByAdminId", admin.id());
        return insertEvent(
                admin.internalId(),
                admin.internalId(),
                eventType,
                label(eventType),
                "ADMIN_HOME_PART_FEEDBACK",
                text(request.get("recommendationId")),
                null,
                partId,
                null,
                firstText(text(request.get("category")), DbValueMapper.string(part, "category")),
                integer(request.get("rankPosition")),
                text(request.get("idempotencyKey")),
                payload
        );
    }

    private Map<String, Object> upsertAsTicketLabel(
            Map<String, Object> ticket,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin,
            Long partId,
            Long buildId,
            String recommendationId,
            boolean useForTraining
    ) {
        String failureCategory = normalizeFailureCategory(firstText(
                text(request.get("failureCategory")),
                partId != null ? "PART_SELECTION" : "RECOMMENDATION_BUILD"
        ));
        String severity = normalizeSeverity(firstText(text(request.get("severity")), "MEDIUM"));
        String note = text(request.get("note"));
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_ticket_labels (
                  as_ticket_id,
                  log_summary_id,
                  failure_category,
                  severity,
                  related_part_id,
                  related_build_id,
                  recommendation_id,
                  use_for_recommendation_training,
                  note,
                  labeled_by_admin_id,
                  updated_at
                )
                VALUES (
                  ?,
                  (SELECT id FROM agent_log_summaries WHERE as_ticket_id = ? LIMIT 1),
                  ?,
                  ?,
                  ?,
                  ?,
                  ?,
                  ?,
                  ?,
                  ?,
                  now()
                )
                ON CONFLICT (as_ticket_id) DO UPDATE
                SET log_summary_id = EXCLUDED.log_summary_id,
                    failure_category = EXCLUDED.failure_category,
                    severity = EXCLUDED.severity,
                    related_part_id = EXCLUDED.related_part_id,
                    related_build_id = EXCLUDED.related_build_id,
                    recommendation_id = EXCLUDED.recommendation_id,
                    use_for_recommendation_training = EXCLUDED.use_for_recommendation_training,
                    note = EXCLUDED.note,
                    labeled_by_admin_id = EXCLUDED.labeled_by_admin_id,
                    updated_at = now()
                RETURNING public_id::text AS id,
                          failure_category,
                          severity,
                          recommendation_id,
                          use_for_recommendation_training,
                          note,
                          created_at,
                          updated_at
                """,
                longValue(ticket, "id"),
                longValue(ticket, "id"),
                failureCategory,
                severity,
                partId,
                buildId,
                recommendationId,
                useForTraining,
                note,
                admin.internalId()
        );
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "failureCategory", DbValueMapper.string(row, "failure_category"),
                "severity", DbValueMapper.string(row, "severity"),
                "recommendationId", DbValueMapper.string(row, "recommendation_id"),
                "useForRecommendationTraining", row.get("use_for_recommendation_training"),
                "note", DbValueMapper.string(row, "note"),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "updatedAt", DbValueMapper.timestamp(row, "updated_at")
        );
    }

    public Map<String, Object> modelVersions() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               model_name,
                               model_version,
                               algorithm,
                               artifact_path,
                               status,
                               trained_from,
                               trained_to,
                               metrics,
                               feature_schema,
                               activated_at,
                               created_at
                        FROM recommendation_model_versions
                        WHERE deleted_at IS NULL
                        ORDER BY created_at DESC, id DESC
                        LIMIT 50
                        """)
                .stream()
                .map(this::modelVersionDto)
                .toList();
        return MockData.map("items", items, "page", 0, "size", 50, "total", items.size());
    }

    public Map<String, Object> modelSummary() {
        Map<String, Object> latestModel = jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               model_name,
                               model_version,
                               algorithm,
                               artifact_path,
                               status,
                               trained_from,
                               trained_to,
                               metrics,
                               feature_schema,
                               activated_at,
                               created_at
                        FROM recommendation_model_versions
                        WHERE deleted_at IS NULL
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """)
                .stream()
                .findFirst()
                .map(this::modelVersionDto)
                .orElse(null);
        Map<String, Object> homeEvents = jdbcTemplate.queryForMap("""
                SELECT count(*) FILTER (WHERE event_type = 'IMPRESSION') AS impressions,
                       count(*) FILTER (WHERE event_type IN ('CLICK', 'DETAIL_VIEW')) AS clicks
                FROM recommendation_events
                WHERE source_surface = 'HOME_RECOMMENDED_PARTS'
                  AND created_at >= now() - interval '7 days'
                """);
        long impressions = longValue(homeEvents, "impressions");
        long clicks = longValue(homeEvents, "clicks");
        List<Map<String, Object>> scoreSources = jdbcTemplate.queryForList("""
                        SELECT coalesce(
                                 nullif(event_payload->>'scoreSource', ''),
                                 nullif(event_payload #>> '{eventPayload,scoreSource}', ''),
                                 'UNKNOWN'
                               ) AS score_source,
                               count(*) AS count
                        FROM recommendation_events
                        WHERE source_surface = 'HOME_RECOMMENDED_PARTS'
                          AND event_type = 'IMPRESSION'
                          AND created_at >= now() - interval '7 days'
                        GROUP BY 1
                        ORDER BY count DESC, score_source
                        """)
                .stream()
                .map(row -> {
                    long count = longValue(row, "count");
                    return MockData.map(
                            "scoreSource", DbValueMapper.string(row, "score_source"),
                            "count", count,
                            "share", impressions <= 0 ? 0.0 : count / (double) impressions
                    );
                })
                .toList();
        Long recentShadowScores = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM recommendation_shadow_scores
                WHERE source_surface = 'HOME_RECOMMENDED_PARTS'
                  AND created_at >= now() - interval '7 days'
                """, Long.class);
        List<Map<String, Object>> recentCandidates = jdbcTemplate.queryForList("""
                        SELECT *
                        FROM (
                          SELECT DISTINCT ON (p.id)
                                 p.public_id::text AS part_id,
                                 p.category,
                                 p.name,
                                 p.manufacturer,
                                 p.price,
                                 s.score,
                                 s.rank_position,
                                 mv.model_version,
                                 s.created_at
                          FROM recommendation_shadow_scores s
                          JOIN parts p ON p.id = s.part_id
                          LEFT JOIN recommendation_model_versions mv ON mv.id = s.model_version_id
                          WHERE s.source_surface = 'HOME_RECOMMENDED_PARTS'
                            AND p.deleted_at IS NULL
                          ORDER BY p.id, s.created_at DESC
                        ) latest
                        ORDER BY created_at DESC
                        LIMIT 12
                        """)
                .stream()
                .map(row -> MockData.map(
                        "partId", DbValueMapper.string(row, "part_id"),
                        "category", DbValueMapper.string(row, "category"),
                        "name", DbValueMapper.string(row, "name"),
                        "manufacturer", DbValueMapper.string(row, "manufacturer"),
                        "price", row.get("price"),
                        "score", row.get("score"),
                        "rankPosition", row.get("rank_position"),
                        "modelVersion", DbValueMapper.string(row, "model_version"),
                        "createdAt", DbValueMapper.timestamp(row, "created_at")
                ))
                .toList();
        return MockData.map(
                "latestModel", latestModel,
                "homeParts", MockData.map(
                        "windowDays", 7,
                        "impressions", impressions,
                        "clicks", clicks,
                        "ctr", impressions <= 0 ? 0.0 : clicks / (double) impressions,
                        "scoreSources", scoreSources,
                        "recentShadowScores", recentShadowScores == null ? 0L : recentShadowScores,
                        "recentCandidates", recentCandidates
                ),
                "generatedAt", java.time.Instant.now().toString()
        );
    }

    private Map<String, Object> insertEvent(
            Long userId,
            Long adminId,
            String eventType,
            double labelScore,
            String sourceSurface,
            String recommendationId,
            Long buildId,
            Long partId,
            Long asTicketId,
            String category,
            Integer rankPosition,
            String idempotencyKey,
            Map<String, Object> payload
    ) {
        try {
            return eventDto(jdbcTemplate.queryForMap("""
                    INSERT INTO recommendation_events (
                      user_id,
                      created_by_admin_id,
                      event_type,
                      label_score,
                      source_surface,
                      recommendation_id,
                      build_id,
                      part_id,
                      as_ticket_id,
                      category,
                      rank_position,
                      idempotency_key,
                      event_payload
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    RETURNING public_id::text AS id,
                              event_type,
                              label_score,
                              source_surface,
                              recommendation_id,
                              category,
                              rank_position,
                              created_at
                    """,
                    userId,
                    adminId,
                    eventType,
                    labelScore,
                    sourceSurface,
                    recommendationId,
                    buildId,
                    partId,
                    asTicketId,
                    normalizeCategory(category),
                    rankPosition,
                    idempotencyKey,
                    toJson(payload)
            ));
        } catch (DataAccessException error) {
            if (idempotencyKey != null) {
                Map<String, Object> existing = findEventByIdempotency(userId, idempotencyKey);
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            throw error;
        }
    }

    private Map<String, Object> findEventByIdempotency(Long userId, String idempotencyKey) {
        if (userId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return Map.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               event_type,
                               label_score,
                               source_surface,
                               recommendation_id,
                               category,
                               rank_position,
                               created_at
                        FROM recommendation_events
                        WHERE user_id = ?
                          AND idempotency_key = ?
                        """, userId, idempotencyKey)
                .stream()
                .findFirst()
                .map(this::eventDto)
                .orElse(Map.of());
    }

    private Long resolvePartId(String partPublicId) {
        if (partPublicId == null) {
            return null;
        }
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM parts
                        WHERE public_id = ?::uuid
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """, partPublicId)
                .stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."));
    }

    private Long resolveOwnedBuildId(String buildPublicId, Long userId) {
        if (buildPublicId == null) {
            return null;
        }
        return jdbcTemplate.queryForList("""
                        SELECT b.id
                        FROM builds b
                        JOIN requirements r ON r.id = b.requirement_id
                        WHERE b.public_id = ?::uuid
                          AND r.user_id = ?
                        """, buildPublicId, userId)
                .stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "추천 견적을 찾을 수 없습니다."));
    }

    private Long resolveOwnedAsTicketId(String ticketPublicId, Long userId) {
        if (ticketPublicId == null) {
            return null;
        }
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM as_tickets
                        WHERE public_id = ?::uuid
                          AND user_id = ?
                          AND deleted_at IS NULL
                        """, ticketPublicId, userId)
                .stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private String normalizeEventType(String eventType) {
        if (eventType == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventType은 필수입니다.");
        }
        String normalized = eventType.trim().toUpperCase(Locale.ROOT);
        if (!LABEL_SCORES.containsKey(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 recommendation eventType입니다.");
        }
        return normalized;
    }

    private static double label(String eventType) {
        return LABEL_SCORES.getOrDefault(eventType, 0.0);
    }

    private Map<String, Object> eventPayload(Map<String, Object> request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String key : List.of("message", "reason", "intent", "aiProfile", "modelVersion", "metadata")) {
            Object value = request.get(key);
            if (value != null) {
                payload.put(key, value);
            }
        }
        Object eventPayload = request.get("eventPayload");
        if (eventPayload instanceof Map<?, ?> map) {
            map.forEach((key, value) -> payload.put(String.valueOf(key), value));
        } else if (eventPayload != null) {
            payload.put("eventPayload", eventPayload);
        }
        return payload;
    }

    private Map<String, Object> eventDto(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "eventType", DbValueMapper.string(row, "event_type"),
                "labelScore", row.get("label_score"),
                "sourceSurface", DbValueMapper.string(row, "source_surface"),
                "recommendationId", DbValueMapper.string(row, "recommendation_id"),
                "category", DbValueMapper.string(row, "category"),
                "rankPosition", DbValueMapper.integer(row, "rank_position"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private Map<String, Object> modelVersionDto(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "modelName", DbValueMapper.string(row, "model_name"),
                "modelVersion", DbValueMapper.string(row, "model_version"),
                "algorithm", DbValueMapper.string(row, "algorithm"),
                "artifactPath", DbValueMapper.string(row, "artifact_path"),
                "status", DbValueMapper.string(row, "status"),
                "trainedFrom", DbValueMapper.timestamp(row, "trained_from"),
                "trainedTo", DbValueMapper.timestamp(row, "trained_to"),
                "metrics", DbValueMapper.json(row, "metrics", Map.of()),
                "featureSchema", DbValueMapper.json(row, "feature_schema", Map.of()),
                "activatedAt", DbValueMapper.timestamp(row, "activated_at"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeFailureCategory(String value) {
        String normalized = text(value) == null ? "OTHER" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of(
                "RECOMMENDATION_BUILD",
                "PART_SELECTION",
                "COMPATIBILITY",
                "PERFORMANCE",
                "USER_ENVIRONMENT",
                "AGENT_LOG_ONLY",
                "OTHER"
        ).contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 AS failureCategory입니다.");
        }
        return normalized;
    }

    private String normalizeSeverity(String value) {
        String normalized = text(value) == null ? "MEDIUM" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 AS severity입니다.");
        }
        return normalized;
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON payload 직렬화에 실패했습니다.", error);
        }
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return value instanceof Boolean booleanValue ? booleanValue : Boolean.parseBoolean(value.toString());
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Integer.valueOf(value.toString());
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}
