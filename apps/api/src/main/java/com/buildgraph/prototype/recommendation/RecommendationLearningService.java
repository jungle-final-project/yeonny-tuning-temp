package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
    // 사용자 이벤트 API가 허용하는 sourceSurface. 자유 문자열을 허용하면 사용자가
    // ADMIN_AS_FEEDBACK 같은 관리자 전용 surface를 위조해 학습 데이터를 오염시킬 수 있다.
    private static final java.util.Set<String> USER_SOURCE_SURFACES = java.util.Set.of(
            "BUILD_CHAT",
            "HOME_RECOMMENDED_PARTS"
    );
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
        String sourceSurface = firstText(text(request.get("sourceSurface")), "BUILD_CHAT").toUpperCase(Locale.ROOT);
        if (!USER_SOURCE_SURFACES.contains(sourceSurface)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 sourceSurface입니다.");
        }
        Long partId = resolvePartId(text(request.get("partId")));
        Long buildId = resolveOwnedBuildId(text(request.get("buildId")), user.internalId());
        Long asTicketId = resolveOwnedAsTicketId(text(request.get("asTicketId")), user.internalId());
        return insertEvent(
                user.internalId(),
                null,
                eventType,
                label(eventType),
                sourceSurface,
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
            // 라벨 재확정(부품 정정 등)이 학습 이벤트에도 반영되도록 관련 링크를 갱신한다.
            // 예전에는 기존 이벤트를 그대로 반환해, 관리자가 라벨을 고쳐도 잘못된 부품에
            // -2.0 라벨이 영구 잔존했다(감사 B6).
            Map<String, Object> correction = new LinkedHashMap<>(eventPayload(request));
            correction.put("relabeledByAdminId", admin.id());
            correction.put("asTicketLabelId", label.get("id"));
            jdbcTemplate.update("""
                    UPDATE recommendation_events
                    SET part_id = ?,
                        build_id = ?,
                        recommendation_id = ?,
                        category = ?,
                        event_payload = coalesce(event_payload, '{}'::jsonb) || ?::jsonb
                    WHERE user_id = ?
                      AND idempotency_key = ?
                    """,
                    partId,
                    buildId,
                    recommendationId,
                    normalizeCategory(text(request.get("category"))),
                    toJson(correction),
                    longValue(ticket, "user_id"),
                    idempotencyKey
            );
            Map<String, Object> refreshed = findEventByIdempotency(longValue(ticket, "user_id"), idempotencyKey);
            refreshed.put("label", label);
            refreshed.put("trainingEventCreated", false);
            return refreshed;
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
        Long logSummaryId = resolveLogSummaryId(text(request.get("logSummaryId")), longValue(ticket, "id"));
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
                  ?,
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
                logSummaryId,
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

    /**
     * M4 Shadow 비교 관측(설계 §5). 최근 windowDays 동안의 HOME/PART shadow 실모델 점수를 논리적 쿼리
     * (request_hash, model_version_id)별로 묶어, features로 재구성한 baseline 순위 대비 모델 순위의
     * 역전율·top4 교체율을 집계한다. 오염 필터를 SQL에서 강제한다:
     * ① HOME_RECOMMENDED_PARTS + PART만, ② baseline-shadow 모델 제외,
     * ③ 후보별 최신 1건만(row_number)으로 중복 회차 붕괴 — 홈 부품 피처는 사용자·시각 무관(부품 결정적)이라
     *    같은 request_hash는 같은 후보집합이므로, 초 버스트 분리 대신 후보별 최신 점수로 dedup하는 게
     *    회차 중복·초 경계 straddle 편향을 모두 없앤다.
     */
    public Map<String, Object> shadowComparisonSummary(int days) {
        int windowDays = Math.min(Math.max(days, 1), 30);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT dedup.request_hash,
                               dedup.model_version_id,
                               dedup.score,
                               dedup.features
                        FROM (
                          SELECT s.request_hash,
                                 s.model_version_id,
                                 s.candidate_id,
                                 s.score,
                                 s.features,
                                 row_number() OVER (
                                   PARTITION BY s.request_hash, s.model_version_id, s.candidate_id
                                   ORDER BY s.created_at DESC, s.id DESC
                                 ) AS rn
                          FROM recommendation_shadow_scores s
                          JOIN recommendation_model_versions mv ON mv.id = s.model_version_id
                          WHERE s.source_surface = 'HOME_RECOMMENDED_PARTS'
                            AND s.candidate_type = 'PART'
                            AND mv.model_version <> 'baseline-shadow'
                            AND s.created_at >= now() - make_interval(days => ?)
                        ) dedup
                        WHERE dedup.rn = 1
                        ORDER BY dedup.request_hash, dedup.model_version_id
                        """, windowDays);
        List<ShadowComparisonMetrics.ShadowRow> shadowRows = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String groupKey = text(row.get("request_hash")) + "|" + text(row.get("model_version_id"));
            shadowRows.add(new ShadowComparisonMetrics.ShadowRow(
                    groupKey,
                    doubleValue(row.get("score"), 0.0),
                    parseJsonMap(row.get("features"))
            ));
        }
        Map<String, Object> summary = ShadowComparisonMetrics.summarize(shadowRows, 2);
        summary.put("windowDays", windowDays);
        summary.put("generatedAt", java.time.Instant.now().toString());
        return summary;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Map<String, Object> parseJsonMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        try {
            return OBJECT_MAPPER.readValue(value.toString(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
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

    // 티켓당 요약이 여러 개(재업로드/재요약)일 때 무순서 LIMIT 1 조인이 임의 요약과 연결되던
    // 모호성을 제거한다(감사 B7). 명시 지정 시 티켓 소속을 검증하고, 미지정 시 최신 요약을 쓴다.
    private Long resolveLogSummaryId(String logSummaryPublicId, Long ticketId) {
        if (logSummaryPublicId != null) {
            return jdbcTemplate.queryForList("""
                            SELECT id
                            FROM agent_log_summaries
                            WHERE public_id = ?::uuid
                              AND as_ticket_id = ?
                            """, logSummaryPublicId, ticketId)
                    .stream()
                    .findFirst()
                    .map(row -> ((Number) row.get("id")).longValue())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "logSummaryId가 이 티켓의 로그 요약이 아닙니다."));
        }
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM agent_log_summaries
                        WHERE as_ticket_id = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """, ticketId)
                .stream()
                .findFirst()
                .map(row -> ((Number) row.get("id")).longValue())
                .orElse(null);
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
