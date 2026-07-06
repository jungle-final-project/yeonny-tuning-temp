package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationTrainingService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> ELIGIBLE_SURFACES = Set.of(
            "HOME_RECOMMENDED_PARTS",
            "ADMIN_HOME_PART_FEEDBACK",
            "ADMIN_AS_FEEDBACK"
    );

    private final JdbcTemplate jdbcTemplate;
    private final RecommendationScoringClient scoringClient;
    private final HomePartRecommendationService homePartRecommendationService;

    public RecommendationTrainingService(
            JdbcTemplate jdbcTemplate,
            RecommendationScoringClient scoringClient,
            HomePartRecommendationService homePartRecommendationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.scoringClient = scoringClient;
        this.homePartRecommendationService = homePartRecommendationService;
    }

    public Map<String, Object> overview() {
        long eligibleEvents = count("""
                SELECT count(*)
                FROM recommendation_events
                WHERE source_surface IN ('HOME_RECOMMENDED_PARTS', 'ADMIN_HOME_PART_FEEDBACK', 'ADMIN_AS_FEEDBACK')
                """);
        long trainedDistinctEvents = count("""
                SELECT count(DISTINCT item.event_id)
                FROM recommendation_training_dataset_items item
                JOIN recommendation_training_jobs job ON job.dataset_id = item.dataset_id
                WHERE item.included = true
                  AND job.status = 'SUCCEEDED'
                """);
        long excludedItems = count("""
                SELECT count(*)
                FROM recommendation_training_dataset_items
                WHERE included = false
                """);
        long recentSevenDays = count("""
                SELECT count(*)
                FROM recommendation_events
                WHERE source_surface IN ('HOME_RECOMMENDED_PARTS', 'ADMIN_HOME_PART_FEEDBACK', 'ADMIN_AS_FEEDBACK')
                  AND created_at >= now() - interval '7 days'
                """);
        long asFeedbackEvents = count("""
                SELECT count(*)
                FROM recommendation_events
                WHERE source_surface = 'ADMIN_AS_FEEDBACK'
                """);
        long asFeedbackTrainedEvents = count("""
                SELECT count(DISTINCT item.event_id)
                FROM recommendation_training_dataset_items item
                JOIN recommendation_training_jobs job ON job.dataset_id = item.dataset_id
                JOIN recommendation_events event ON event.id = item.event_id
                WHERE item.included = true
                  AND job.status = 'SUCCEEDED'
                  AND event.source_surface = 'ADMIN_AS_FEEDBACK'
                """);
        Map<String, Object> activeModel = jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               model_name,
                               model_version,
                               status,
                               artifact_path,
                               activated_at,
                               created_at
                        FROM recommendation_model_versions
                        WHERE deleted_at IS NULL
                          AND status = 'ACTIVE'
                        ORDER BY activated_at DESC NULLS LAST, created_at DESC
                        LIMIT 1
                        """)
                .stream()
                .findFirst()
                .map(this::modelDto)
                .orElse(null);
        Map<String, Object> latestJob = jdbcTemplate.queryForList("""
                        SELECT job.public_id::text AS id,
                               ds.public_id::text AS dataset_id,
                               ds.name AS dataset_name,
                               job.status,
                               job.model_version,
                               job.artifact_path,
                               job.metrics,
                               job.log_summary,
                               job.created_at,
                               job.started_at,
                               job.finished_at
                        FROM recommendation_training_jobs job
                        JOIN recommendation_training_datasets ds ON ds.id = job.dataset_id
                        WHERE job.deleted_at IS NULL
                        ORDER BY job.created_at DESC, job.id DESC
                        LIMIT 1
                        """)
                .stream()
                .findFirst()
                .map(this::jobDto)
                .orElse(null);
        return MockData.map(
                "eligibleEvents", eligibleEvents,
                "trainedDistinctEvents", trainedDistinctEvents,
                "untrainedEligibleEvents", Math.max(0, eligibleEvents - trainedDistinctEvents),
                "excludedDatasetItems", excludedItems,
                "recentSevenDayEvents", recentSevenDays,
                "asFeedbackEvents", asFeedbackEvents,
                "untrainedAsFeedbackEvents", Math.max(0, asFeedbackEvents - asFeedbackTrainedEvents),
                "activeModel", activeModel,
                "latestJob", latestJob,
                "generatedAt", Instant.now().toString()
        );
    }

    public Map<String, Object> datasets() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               name,
                               source_surface,
                               filters,
                               status,
                               eligible_count,
                               included_count,
                               excluded_count,
                               locked_at,
                               created_at,
                               updated_at
                        FROM recommendation_training_datasets
                        WHERE deleted_at IS NULL
                        ORDER BY created_at DESC, id DESC
                        LIMIT 50
                        """)
                .stream()
                .map(this::datasetDto)
                .toList();
        return MockData.map("items", items, "page", 0, "size", 50, "total", items.size());
    }

    @Transactional
    public Map<String, Object> createDataset(Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> filters = normalizeFilters(request);
        String name = firstText(text(request.get("name")), "홈 추천부품 학습 데이터셋 " + java.time.LocalDate.now());
        Map<String, Object> dataset = jdbcTemplate.queryForMap("""
                INSERT INTO recommendation_training_datasets (
                  name,
                  source_surface,
                  filters,
                  status,
                  created_by
                )
                VALUES (?, 'HOME_PARTS_WITH_AS_FEEDBACK', ?::jsonb, 'DRAFT', ?)
                RETURNING id, public_id::text AS public_id
                """,
                name,
                writeJson(filters),
                admin.internalId()
        );
        Long datasetId = longValue(dataset, "id");
        for (Map<String, Object> event : eligibleEvents(filters)) {
            jdbcTemplate.update("""
                    INSERT INTO recommendation_training_dataset_items (
                      dataset_id,
                      event_id,
                      included,
                      label_score_snapshot,
                      features_snapshot,
                      event_snapshot
                    )
                    VALUES (?, ?, true, ?, ?::jsonb, ?::jsonb)
                    ON CONFLICT (dataset_id, event_id) DO NOTHING
                    """,
                    datasetId,
                    longValue(event, "event_id"),
                    event.get("label_score"),
                    writeJson(featureSnapshot(event)),
                    writeJson(eventSnapshot(event))
            );
        }
        refreshDatasetCounts(datasetId);
        return datasetById(datasetId);
    }

    @Transactional
    public Map<String, Object> updateDataset(String datasetPublicId, Map<String, Object> request) {
        Map<String, Object> dataset = requireDataset(datasetPublicId);
        requireDraft(dataset);
        String name = text(request.get("name"));
        if (name == null) {
            return datasetById(longValue(dataset, "id"));
        }
        jdbcTemplate.update("""
                UPDATE recommendation_training_datasets
                SET name = ?,
                    updated_at = now()
                WHERE id = ?
                """, name, longValue(dataset, "id"));
        return datasetById(longValue(dataset, "id"));
    }

    @Transactional
    public Map<String, Object> lockDataset(String datasetPublicId) {
        Map<String, Object> dataset = requireDataset(datasetPublicId);
        requireDraft(dataset);
        Long datasetId = longValue(dataset, "id");
        refreshDatasetCounts(datasetId);
        jdbcTemplate.update("""
                UPDATE recommendation_training_datasets
                SET status = 'LOCKED',
                    locked_at = now(),
                    updated_at = now()
                WHERE id = ?
                """, datasetId);
        return datasetById(datasetId);
    }

    @Transactional
    public Map<String, Object> archiveDataset(String datasetPublicId) {
        Map<String, Object> dataset = requireDataset(datasetPublicId);
        jdbcTemplate.update("""
                UPDATE recommendation_training_datasets
                SET status = 'ARCHIVED',
                    updated_at = now()
                WHERE id = ?
                """, longValue(dataset, "id"));
        return datasetById(longValue(dataset, "id"));
    }

    public Map<String, Object> datasetItems(String datasetPublicId) {
        Map<String, Object> dataset = requireDataset(datasetPublicId);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT item.public_id::text AS id,
                               event.public_id::text AS event_id,
                               event.event_type,
                               event.source_surface,
                               event.category,
                               event.rank_position,
                               event.created_at AS event_created_at,
                               item.included,
                               item.excluded_reason,
                               item.label_score_snapshot,
                               item.features_snapshot,
                               item.event_snapshot,
                               item.created_at
                        FROM recommendation_training_dataset_items item
                        JOIN recommendation_events event ON event.id = item.event_id
                        WHERE item.dataset_id = ?
                        ORDER BY item.created_at DESC, item.id DESC
                        LIMIT 200
                        """, longValue(dataset, "id"))
                .stream()
                .map(this::datasetItemDto)
                .toList();
        return MockData.map("items", items, "page", 0, "size", 200, "total", items.size());
    }

    @Transactional
    public Map<String, Object> bulkInclude(String datasetPublicId, Map<String, Object> request) {
        return bulkSetIncluded(datasetPublicId, request, true);
    }

    @Transactional
    public Map<String, Object> bulkExclude(String datasetPublicId, Map<String, Object> request) {
        return bulkSetIncluded(datasetPublicId, request, false);
    }

    @Transactional
    public Map<String, Object> createJob(Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        String datasetPublicId = text(request.get("datasetId"));
        if (datasetPublicId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetId는 필수입니다.");
        }
        Map<String, Object> dataset = requireDataset(datasetPublicId);
        if (!"LOCKED".equals(text(dataset.get("status")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LOCKED dataset만 학습 Job을 만들 수 있습니다.");
        }
        Long included = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM recommendation_training_dataset_items
                WHERE dataset_id = ?
                  AND included = true
                """, Long.class, longValue(dataset, "id"));
        if (included == null || included <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "포함된 학습 데이터가 없습니다.");
        }
        Map<String, Object> job = jdbcTemplate.queryForMap("""
                INSERT INTO recommendation_training_jobs (
                  dataset_id,
                  status,
                  queued_by,
                  log_summary
                )
                VALUES (?, 'QUEUED', ?, '관리자 요청으로 학습 대기열에 등록되었습니다.')
                RETURNING id
                """, longValue(dataset, "id"), admin.internalId());
        return jobById(longValue(job, "id"));
    }

    public Map<String, Object> jobs() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT job.public_id::text AS id,
                               ds.public_id::text AS dataset_id,
                               ds.name AS dataset_name,
                               job.status,
                               job.worker_id,
                               job.model_version,
                               job.artifact_path,
                               job.metrics,
                               job.log_summary,
                               job.created_at,
                               job.started_at,
                               job.finished_at
                        FROM recommendation_training_jobs job
                        JOIN recommendation_training_datasets ds ON ds.id = job.dataset_id
                        WHERE job.deleted_at IS NULL
                        ORDER BY job.created_at DESC, job.id DESC
                        LIMIT 50
                        """)
                .stream()
                .map(this::jobDto)
                .toList();
        return MockData.map("items", items, "page", 0, "size", 50, "total", items.size());
    }

    @Transactional
    public Map<String, Object> activateModel(String modelPublicId) {
        Map<String, Object> model = requireModel(modelPublicId);
        String status = text(model.get("status"));
        if ("ACTIVE".equals(status)) {
            return modelById(longValue(model, "id"));
        }
        if (!"SHADOW".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SHADOW 모델만 활성화할 수 있습니다.");
        }
        String artifactPath = text(model.get("artifact_path"));
        if (artifactPath == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "artifactPath가 없는 모델은 활성화할 수 없습니다.");
        }
        // in-sample(train-on-train) 지표만 있는 모델은 일반화 성능이 검증되지 않았다.
        // 워커가 holdout 평가를 기록한 모델만 ACTIVE 승급을 허용한다(평가 없는 자동화 방지 게이트).
        Map<String, Object> metrics = json(model.get("metrics"));
        if (!(metrics.get("holdout") instanceof Map<?, ?>)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "holdout 평가 지표가 없는 모델은 활성화할 수 없습니다. 최신 학습 워커로 재훈련하세요.");
        }
        // 학습-서빙 피처 스큐 게이트(M6): 모델이 지금 서빙 중인 스코어러의 FEATURES와 다른 스키마로
        // 훈련됐으면 승급을 막는다. reload보다 먼저 검사해, 차단 시 스코어러 인메모리 상태를 바꾸지 않는다.
        assertServingSchemaCompatible(model);
        // Champion-Challenger 승급 게이트(M1): 워커가 기록한 comparison.verdict를 본다. 공정 비교
        // (현재 ACTIVE 챔피언과 대조 + holdout 겹침 0)에서 챔피언이 더 우수하면 승급을 막는다.
        // INCONCLUSIVE/INSUFFICIENT_DATA/verdict 부재/stale/겹침>0은 승급 허용(사람 판단 존중, 경고만).
        String activationWarning = evaluatePromotionVerdict(json(metrics.get("comparison")));
        Map<String, Object> reload = scoringClient.reload(artifactPath);
        Object loaded = reload.get("modelLoaded");
        if (!(loaded instanceof Boolean bool && bool)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "scorer reload에 실패했습니다.");
        }
        jdbcTemplate.update("""
                UPDATE recommendation_model_versions
                SET status = 'RETIRED'
                WHERE status = 'ACTIVE'
                  AND deleted_at IS NULL
                  AND id <> ?
                """, longValue(model, "id"));
        jdbcTemplate.update("""
                UPDATE recommendation_model_versions
                SET status = 'ACTIVE',
                    activated_at = now()
                WHERE id = ?
                """, longValue(model, "id"));
        // 홈 서빙 경로가 다음 요청부터 즉시 동기 스코어링(실모델 순위 반영)으로 전환하도록 알린다.
        homePartRecommendationService.notifyScorerModelChanged(true);
        Map<String, Object> activated = modelById(longValue(model, "id"));
        if (activationWarning != null) {
            activated.put("activationWarning", activationWarning);
        }
        return activated;
    }

    /**
     * M1 승급 게이트 판정. 워커 metrics.comparison.verdict를 소비한다.
     * CHAMPION_BETTER이면서 (1) 비교 대상 챔피언이 현재 ACTIVE와 동일하고 (2) holdout이 챔피언 학습
     * 구간과 겹치지 않은(공정 비교) 경우에만 409로 승급을 막는다. 그 외에는 승급을 허용하되, 근거가
     * 약한 경우(챔피언 우세이나 불공정, INCONCLUSIVE/INSUFFICIENT_DATA) 경고 문구를 반환한다.
     * @return 경고 문구(없으면 null)
     */
    private String evaluatePromotionVerdict(Map<String, Object> comparison) {
        return evaluatePromotionVerdict(comparison, currentActiveModelVersion());
    }

    // DB 조회를 분리한 순수 판정 로직(단위 테스트 대상). activeModelVersion은 현재 ACTIVE 챔피언의 버전.
    static String evaluatePromotionVerdict(Map<String, Object> comparison, String activeModelVersion) {
        String verdict = text(comparison.get("verdict"));
        if (verdict == null || "CHALLENGER_BETTER".equals(verdict)) {
            return null;
        }
        if ("CHAMPION_BETTER".equals(verdict)) {
            boolean sameChampion = Objects.equals(text(comparison.get("champion")), activeModelVersion);
            boolean fairHoldout = comparison.containsKey("holdoutOverlapWithChampion")
                    && doubleValue(comparison.get("holdoutOverlapWithChampion")) == 0.0;
            if (sameChampion && fairHoldout) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "현재 활성 모델이 더 우수해 승급이 거절되었습니다("
                                + firstText(text(comparison.get("verdictReason")), "champion 우세") + ").");
            }
            return "챔피언 우세 판정이나 공정 비교 조건(동일 챔피언·holdout 겹침 0) 미충족으로 승급을 허용했습니다.";
        }
        return "승급 근거가 충분치 않습니다(verdict=" + verdict + "). 지표를 확인하고 신중히 판단하세요.";
    }

    private String currentActiveModelVersion() {
        return jdbcTemplate.queryForList("""
                        SELECT model_version
                        FROM recommendation_model_versions
                        WHERE deleted_at IS NULL
                          AND status = 'ACTIVE'
                        ORDER BY activated_at DESC NULLS LAST, created_at DESC
                        LIMIT 1
                        """)
                .stream()
                .findFirst()
                .map(row -> text(row.get("model_version")))
                .orElse(null);
    }

    @Transactional
    public Map<String, Object> retireModel(String modelPublicId) {
        Map<String, Object> model = requireModel(modelPublicId);
        jdbcTemplate.update("""
                UPDATE recommendation_model_versions
                SET status = 'RETIRED'
                WHERE id = ?
                """, longValue(model, "id"));
        if ("ACTIVE".equals(text(model.get("status")))) {
            try {
                scoringClient.reload(null);
            } catch (Exception ignored) {
                // Retiring the DB model must not fail only because the optional scorer is unavailable.
            }
            // 실모델이 내려갔으므로 홈 서빙은 baseline 모드(비동기 shadow)로 되돌린다.
            homePartRecommendationService.notifyScorerModelChanged(false);
        }
        return modelById(longValue(model, "id"));
    }

    private Map<String, Object> bulkSetIncluded(String datasetPublicId, Map<String, Object> request, boolean included) {
        Map<String, Object> dataset = requireDataset(datasetPublicId);
        requireDraft(dataset);
        Long datasetId = longValue(dataset, "id");
        Set<String> itemIds = new LinkedHashSet<>(stringList(request.get("itemIds")));
        String reason = firstText(text(request.get("reason")), included ? null : "관리자 제외");
        int updated;
        if (!itemIds.isEmpty()) {
            updated = 0;
            for (String itemId : itemIds) {
                updated += jdbcTemplate.update("""
                        UPDATE recommendation_training_dataset_items
                        SET included = ?,
                            excluded_reason = ?,
                            updated_at = now()
                        WHERE dataset_id = ?
                          AND public_id = ?::uuid
                        """, included, included ? null : reason, datasetId, itemId);
            }
        } else {
            String eventType = text(request.get("eventType"));
            String category = text(request.get("category"));
            updated = jdbcTemplate.update("""
                    UPDATE recommendation_training_dataset_items item
                    SET included = ?,
                        excluded_reason = ?,
                        updated_at = now()
                    FROM recommendation_events event
                    WHERE item.event_id = event.id
                      AND item.dataset_id = ?
                      AND (? IS NULL OR event.event_type = ?)
                      AND (? IS NULL OR event.category = ?)
                    """,
                    included,
                    included ? null : reason,
                    datasetId,
                    eventType,
                    eventType,
                    category,
                    category);
        }
        refreshDatasetCounts(datasetId);
        Map<String, Object> response = datasetById(datasetId);
        response.put("updatedItems", updated);
        return response;
    }

    private List<Map<String, Object>> eligibleEvents(Map<String, Object> filters) {
        String from = text(filters.get("from"));
        String to = text(filters.get("to"));
        Set<String> eventTypes = upperSet(filters.get("eventTypes"));
        Set<String> categories = upperSet(filters.get("categories"));
        Set<String> sourceSurfaces = upperSet(filters.get("sourceSurfaces"));
        StringBuilder sql = new StringBuilder("""
                        SELECT e.id AS event_id,
                               e.public_id::text AS event_public_id,
                               e.event_type,
                               e.label_score,
                               e.source_surface,
                               e.recommendation_id,
                               e.category,
                               e.rank_position,
                               e.event_payload,
                               e.created_at,
                               p.id AS part_internal_id,
                               p.public_id::text AS part_id,
                               p.name AS part_name,
                               p.manufacturer,
                               p.price AS part_price,
                               p.category AS part_category,
                               p.attributes AS part_attributes,
                               bs.score AS benchmark_score,
                               peo.image_url AS external_offer_image_url,
                               peo.offer_url AS external_offer_url,
                               ps.collected_at AS price_collected_at,
                               CASE
                                 WHEN ps.collected_at IS NULL THEN NULL
                                 ELSE extract(epoch FROM (now() - ps.collected_at)) / 86400.0
                               END AS price_age_days,
                               EXISTS (
                                 SELECT 1
                                 FROM game_fps_benchmarks fps
                                 WHERE fps.cpu_part_id = p.id OR fps.gpu_part_id = p.id
                               ) AS has_fps_coverage,
                               als.feature_payload AS as_feature_payload,
                               als.risk_flags AS as_risk_flags,
                               atl.failure_category AS as_failure_category,
                               atl.severity AS as_severity
                        FROM recommendation_events e
                        LEFT JOIN parts p ON p.id = e.part_id
                        LEFT JOIN agent_log_summaries als ON als.as_ticket_id = e.as_ticket_id
                        LEFT JOIN as_ticket_labels atl ON atl.as_ticket_id = e.as_ticket_id
                        LEFT JOIN LATERAL (
                          SELECT b.score
                          FROM benchmark_summaries b
                          WHERE b.part_id = p.id
                            AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC
                          LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT offer.image_url, offer.offer_url
                          FROM part_external_offers offer
                          WHERE offer.part_id = p.id
                            AND offer.deleted_at IS NULL
                          ORDER BY offer.refreshed_at DESC NULLS LAST, offer.id DESC
                          LIMIT 1
                        ) peo ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.collected_at
                          FROM price_snapshots snapshot
                          WHERE snapshot.part_id = p.id
                            AND snapshot.collected_at <= now()
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        WHERE e.source_surface IN ('HOME_RECOMMENDED_PARTS', 'ADMIN_HOME_PART_FEEDBACK', 'ADMIN_AS_FEEDBACK')
                        """);
        List<Object> args = new ArrayList<>();
        if (from != null) {
            sql.append(" AND e.created_at >= CAST(? AS timestamptz)\n");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND e.created_at <= CAST(? AS timestamptz)\n");
            args.add(to);
        }
        sql.append(" ORDER BY e.created_at DESC, e.id DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray())
                .stream()
                .filter(row -> sourceSurfaces.isEmpty() || sourceSurfaces.contains(text(row.get("source_surface"))))
                .filter(row -> eventTypes.isEmpty() || eventTypes.contains(text(row.get("event_type"))))
                .filter(row -> categories.isEmpty() || categories.contains(firstText(text(row.get("category")), text(row.get("part_category")))))
                .toList();
    }

    // features_snapshot에는 '모델이 실제로 소비하는 피처'만 담는다. 이름·결측 기본값은
    // 서빙(HomePartRecommendationService.features)·워커(tools/reranker_service.py FEATURES)와 일치해야 한다.
    // as_* 지표는 서빙 시점에 계산할 수 없어(요청에 없음) 모델 피처로 넣으면 학습-서빙 스큐가 생기므로,
    // 분석·향후 피처 엔지니어링용 컨텍스트로 eventSnapshot(asContext)에 보존한다.
    private Map<String, Object> featureSnapshot(Map<String, Object> row) {
        String category = firstText(text(row.get("category")), text(row.get("part_category")));
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("rank_position", integer(row.get("rank_position"), 0));
        features.put("part_price", integer(row.get("part_price"), 0));
        features.put("build_total_price", 0);
        features.put("part_benchmark_score", number(row.get("benchmark_score"), 0.0));
        features.put("part_tool_ready", toolReady(row.get("part_attributes")) ? 1 : 0);
        features.put("part_has_image", text(row.get("external_offer_image_url")) == null ? 0 : 1);
        features.put("part_has_offer", text(row.get("external_offer_url")) == null ? 0 : 1);
        features.put("part_price_age_days", number(row.get("price_age_days"), 999.0));
        features.put("part_has_fps_coverage", booleanValue(row.get("has_fps_coverage")) ? 1 : 0);
        for (String partCategory : List.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER")) {
            features.put("category_" + partCategory, partCategory.equals(category) ? 1 : 0);
        }
        return features;
    }

    private Map<String, Object> eventSnapshot(Map<String, Object> row) {
        Map<String, Object> asFeatures = json(row.get("as_feature_payload"));
        Map<String, Object> asRisks = json(row.get("as_risk_flags"));
        return MockData.map(
                "eventId", text(row.get("event_public_id")),
                "eventType", text(row.get("event_type")),
                "sourceSurface", text(row.get("source_surface")),
                "recommendationId", text(row.get("recommendation_id")),
                "category", firstText(text(row.get("category")), text(row.get("part_category"))),
                "partId", text(row.get("part_id")),
                "partName", text(row.get("part_name")),
                "asFailureCategory", text(row.get("as_failure_category")),
                "asSeverity", text(row.get("as_severity")),
                // 모델 피처에서 제외된 AS 컨텍스트를 재현 가능하게 보존한다(분석/향후 서빙 가능 집계 피처 설계용).
                "asContext", MockData.map(
                        "features", asFeatures,
                        "riskFlags", asRisks
                ),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private Map<String, Object> normalizeFilters(Map<String, Object> request) {
        return MockData.map(
                "from", text(request.get("from")),
                "to", text(request.get("to")),
                "sourceSurfaces", stringList(request.get("sourceSurfaces")),
                "eventTypes", stringList(request.get("eventTypes")),
                "categories", stringList(request.get("categories"))
        );
    }

    private void requireDraft(Map<String, Object> dataset) {
        if (!"DRAFT".equals(text(dataset.get("status")))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DRAFT dataset만 수정할 수 있습니다.");
        }
    }

    private Map<String, Object> requireDataset(String publicId) {
        if (publicId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataset id는 필수입니다.");
        }
        return jdbcTemplate.queryForList("""
                        SELECT id,
                               public_id::text AS public_id,
                               name,
                               source_surface,
                               filters,
                               status,
                               eligible_count,
                               included_count,
                               excluded_count,
                               locked_at,
                               created_at,
                               updated_at
                        FROM recommendation_training_datasets
                        WHERE public_id = ?::uuid
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "학습 데이터셋을 찾을 수 없습니다."));
    }

    private Map<String, Object> datasetById(Long id) {
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               name,
                               source_surface,
                               filters,
                               status,
                               eligible_count,
                               included_count,
                               excluded_count,
                               locked_at,
                               created_at,
                               updated_at
                        FROM recommendation_training_datasets
                        WHERE id = ?
                        """, id)
                .stream()
                .findFirst()
                .map(this::datasetDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "학습 데이터셋을 찾을 수 없습니다."));
    }

    private Map<String, Object> jobById(Long id) {
        return jdbcTemplate.queryForList("""
                        SELECT job.public_id::text AS id,
                               ds.public_id::text AS dataset_id,
                               ds.name AS dataset_name,
                               job.status,
                               job.worker_id,
                               job.model_version,
                               job.artifact_path,
                               job.metrics,
                               job.log_summary,
                               job.created_at,
                               job.started_at,
                               job.finished_at
                        FROM recommendation_training_jobs job
                        JOIN recommendation_training_datasets ds ON ds.id = job.dataset_id
                        WHERE job.id = ?
                        """, id)
                .stream()
                .findFirst()
                .map(this::jobDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "학습 Job을 찾을 수 없습니다."));
    }

    private Map<String, Object> requireModel(String publicId) {
        if (publicId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model id는 필수입니다.");
        }
        return jdbcTemplate.queryForList("""
                        SELECT id,
                               public_id::text AS public_id,
                               model_name,
                               model_version,
                               algorithm,
                               artifact_path,
                               status,
                               metrics,
                               feature_schema,
                               activated_at,
                               created_at
                        FROM recommendation_model_versions
                        WHERE public_id = ?::uuid
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "모델 버전을 찾을 수 없습니다."));
    }

    private Map<String, Object> modelById(Long id) {
        return jdbcTemplate.queryForList("""
                        SELECT id,
                               public_id::text AS public_id,
                               model_name,
                               model_version,
                               algorithm,
                               artifact_path,
                               status,
                               metrics,
                               feature_schema,
                               activated_at,
                               created_at
                        FROM recommendation_model_versions
                        WHERE id = ?
                        """, id)
                .stream()
                .findFirst()
                .map(this::modelDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "모델 버전을 찾을 수 없습니다."));
    }

    private void refreshDatasetCounts(Long datasetId) {
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT count(*) AS eligible_count,
                       count(*) FILTER (WHERE included = true) AS included_count,
                       count(*) FILTER (WHERE included = false) AS excluded_count
                FROM recommendation_training_dataset_items
                WHERE dataset_id = ?
                """, datasetId);
        jdbcTemplate.update("""
                UPDATE recommendation_training_datasets
                SET eligible_count = ?,
                    included_count = ?,
                    excluded_count = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                longValue(counts, "eligible_count"),
                longValue(counts, "included_count"),
                longValue(counts, "excluded_count"),
                datasetId);
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private Map<String, Object> datasetDto(Map<String, Object> row) {
        return MockData.map(
                "id", firstText(text(row.get("id")), text(row.get("public_id"))),
                "name", text(row.get("name")),
                "sourceSurface", text(row.get("source_surface")),
                "filters", json(row.get("filters")),
                "status", text(row.get("status")),
                "eligibleCount", longValue(row, "eligible_count"),
                "includedCount", longValue(row, "included_count"),
                "excludedCount", longValue(row, "excluded_count"),
                "lockedAt", DbValueMapper.timestamp(row, "locked_at"),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "updatedAt", DbValueMapper.timestamp(row, "updated_at")
        );
    }

    private Map<String, Object> datasetItemDto(Map<String, Object> row) {
        return MockData.map(
                "id", text(row.get("id")),
                "eventId", text(row.get("event_id")),
                "eventType", text(row.get("event_type")),
                "sourceSurface", text(row.get("source_surface")),
                "category", text(row.get("category")),
                "rankPosition", row.get("rank_position"),
                "eventCreatedAt", DbValueMapper.timestamp(row, "event_created_at"),
                "included", row.get("included"),
                "excludedReason", text(row.get("excluded_reason")),
                "labelScoreSnapshot", row.get("label_score_snapshot"),
                "featuresSnapshot", json(row.get("features_snapshot")),
                "eventSnapshot", json(row.get("event_snapshot")),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private Map<String, Object> jobDto(Map<String, Object> row) {
        return MockData.map(
                "id", text(row.get("id")),
                "datasetId", text(row.get("dataset_id")),
                "datasetName", text(row.get("dataset_name")),
                "status", text(row.get("status")),
                "workerId", text(row.get("worker_id")),
                "modelVersion", text(row.get("model_version")),
                "artifactPath", text(row.get("artifact_path")),
                "metrics", json(row.get("metrics")),
                "logSummary", text(row.get("log_summary")),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "startedAt", DbValueMapper.timestamp(row, "started_at"),
                "finishedAt", DbValueMapper.timestamp(row, "finished_at")
        );
    }

    private Map<String, Object> modelDto(Map<String, Object> row) {
        return MockData.map(
                "id", firstText(text(row.get("id")), text(row.get("public_id"))),
                "modelName", text(row.get("model_name")),
                "modelVersion", text(row.get("model_version")),
                "algorithm", text(row.get("algorithm")),
                "artifactPath", text(row.get("artifact_path")),
                "status", text(row.get("status")),
                "metrics", json(row.get("metrics")),
                "featureSchema", json(row.get("feature_schema")),
                "activatedAt", DbValueMapper.timestamp(row, "activated_at"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
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

    private static Integer integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0L : Long.parseLong(value.toString());
    }

    private static double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Set.of("1", "true", "yes", "y").contains(value.toString().toLowerCase(Locale.ROOT));
    }

    private static boolean toolReady(Object attributes) {
        Map<String, Object> map = json(attributes);
        return booleanValue(map.get("toolReady"));
    }

    private static Set<String> upperSet(Object value) {
        return stringList(value).stream()
                .map(item -> item.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            iterable.forEach(item -> {
                String text = text(item);
                if (text != null) {
                    result.add(text);
                }
            });
            return result;
        }
        String text = text(value);
        return text == null ? List.of() : List.of(text);
    }

    private static Map<String, Object> json(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        try {
            return OBJECT_MAPPER.readValue(value.toString(), MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * 학습-서빙 피처 스큐 게이트(M6). 모델의 feature_schema.features가 현재 스코어러의 서빙 피처
     * 계약과 다르면 409로 활성화를 막는다. 안전측 설계: 어느 한쪽 스키마를 확정할 수 없으면(구모델의
     * 스키마 부재, 스코어러 상태 조회 실패) 게이트를 발동하지 않고 뒤의 reload 게이트에 맡긴다 —
     * 스큐를 "확실히 감지"했을 때만 차단하고, 판정 불능으로 승급을 막지 않는다.
     */
    private void assertServingSchemaCompatible(Map<String, Object> model) {
        List<String> modelFeatures = featureNames(json(model.get("feature_schema")).get("features"));
        List<String> scorerFeatures;
        try {
            scorerFeatures = featureNames(json(scoringClient.health().get("featureSchema")).get("features"));
        } catch (RuntimeException probeFailed) {
            return;
        }
        if (servingSchemaMismatch(modelFeatures, scorerFeatures)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이 모델은 현재 서빙 피처 스키마와 다르게 훈련되었습니다(학습-서빙 스큐). 최신 워커로 재훈련 후 활성화하세요.");
        }
    }

    // 순수 스큐 판정(단위 테스트 대상). 양쪽 스키마를 모두 확정할 수 있고 서로 다를 때만 true.
    // 어느 한쪽이 비면(구모델 스키마 부재 등) 판정 불능으로 보고 차단하지 않는다.
    static boolean servingSchemaMismatch(List<String> modelFeatures, List<String> scorerFeatures) {
        return !modelFeatures.isEmpty() && !scorerFeatures.isEmpty() && !scorerFeatures.equals(modelFeatures);
    }

    private static List<String> featureNames(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                names.add(item.toString());
            }
        }
        return names;
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalStateException("JSON 직렬화 실패", error);
        }
    }
}
