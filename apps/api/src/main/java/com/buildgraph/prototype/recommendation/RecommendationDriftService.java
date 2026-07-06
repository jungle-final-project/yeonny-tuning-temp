package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M3 Drift·품질 모니터링(설계 docs/mlops-maturity-design.md §4). 매일 3계열을 계산해
 * recommendation_drift_snapshots에 upsert한다.
 *  ① 카탈로그 피처 PSI(현재 ACTIVE 부품 분포 vs 기준 모델 학습창 분포) — 재훈련 연계 대상
 *  ② 예측 drift PSI(동일 모델 shadow score 최근 7일 vs 직전 7일) — 표시 전용
 *  ③ 운영 지표(fallback 비율·scorer scoreErrors 증분·훈련 실패율)
 */
@Service
public class RecommendationDriftService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationDriftService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final double PSI_WARN = 0.2;
    private static final double PSI_SEVERE = 0.3;
    private static final double FALLBACK_WARN = 0.30;

    private final JdbcTemplate jdbcTemplate;
    private final RecommendationScoringClient scoringClient;
    private final int minSamples;

    public RecommendationDriftService(
            JdbcTemplate jdbcTemplate,
            RecommendationScoringClient scoringClient,
            @Value("${recommendation.drift.min-samples:500}") int minSamples
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.scoringClient = scoringClient;
        this.minSamples = Math.max(minSamples, 1);
    }

    /** 오늘자 drift 스냅샷을 계산·upsert하고 결과 요약(재훈련 연계 판단용 catalogPsiSevere 포함)을 반환. */
    @Transactional
    public Map<String, Object> computeDailySnapshot() {
        Map<String, Object> reference = referenceModel();
        Map<String, Object> metrics = new LinkedHashMap<>();
        List<Map<String, Object>> alerts = new ArrayList<>();

        Map<String, Object> catalogPsi = catalogFeatureDrift(reference, alerts);
        Map<String, Object> predictionPsi = predictionDrift(reference);
        Map<String, Object> operational = operationalMetrics(alerts);

        metrics.put("referenceModel", reference == null ? null : reference.get("model_version"));
        metrics.put("catalogFeaturePsi", catalogPsi);
        metrics.put("predictionDriftPsi", predictionPsi);
        metrics.put("operational", operational);

        boolean catalogSevere = maxNumeric(catalogPsi, "psi") >= PSI_SEVERE;
        upsertSnapshot(metrics, alerts);

        return MockData.map(
                "snapshotSaved", true,
                "catalogPsiSevere", catalogSevere,
                "alertCount", alerts.size(),
                "referenceModel", reference == null ? null : reference.get("model_version")
        );
    }

    // 기준 모델: ACTIVE 우선, 없으면 최신 SHADOW.
    private Map<String, Object> referenceModel() {
        return jdbcTemplate.queryForList("""
                        SELECT id, model_version, activated_at
                        FROM recommendation_model_versions
                        WHERE deleted_at IS NULL
                          AND status IN ('ACTIVE', 'SHADOW')
                          AND model_version <> 'baseline-shadow'
                        ORDER BY (status = 'ACTIVE') DESC, activated_at DESC NULLS LAST, created_at DESC
                        LIMIT 1
                        """)
                .stream().findFirst().orElse(null);
    }

    // 계열 ①: 현재 ACTIVE 카탈로그 서빙 피처 분포 vs 기준 모델 학습창 features_snapshot 분포의 PSI.
    private Map<String, Object> catalogFeatureDrift(Map<String, Object> reference, List<Map<String, Object>> alerts) {
        if (reference == null) {
            return MockData.map("skipped", true, "reason", "기준 모델(ACTIVE/SHADOW) 없음");
        }
        Long modelId = ((Number) reference.get("id")).longValue();
        List<Map<String, Object>> current = jdbcTemplate.queryForList("""
                        SELECT p.price AS part_price,
                               -- 결측 기본값을 학습 스냅샷(featureSnapshot)과 맞춘다: benchmark=0, price_age=999.
                               coalesce(bs.score, 0.0) AS part_benchmark_score,
                               CASE WHEN ps.collected_at IS NULL THEN 999.0
                                    ELSE extract(epoch FROM (now() - ps.collected_at)) / 86400.0 END AS part_price_age_days
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT b.score FROM benchmark_summaries b
                          WHERE b.part_id = p.id AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.collected_at FROM price_snapshots snapshot
                          WHERE snapshot.part_id = p.id AND snapshot.collected_at <= now()
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC LIMIT 1
                        ) ps ON true
                        WHERE p.status = 'ACTIVE' AND p.deleted_at IS NULL AND p.price IS NOT NULL AND p.price > 0
                        """);
        List<Map<String, Object>> referenceRows = jdbcTemplate.queryForList("""
                        SELECT (i.features_snapshot->>'part_price')::numeric AS part_price,
                               (i.features_snapshot->>'part_benchmark_score')::numeric AS part_benchmark_score,
                               (i.features_snapshot->>'part_price_age_days')::numeric AS part_price_age_days
                        FROM recommendation_training_dataset_items i
                        JOIN recommendation_training_jobs j ON j.dataset_id = i.dataset_id
                        WHERE j.model_version_id = ?
                          AND i.included = true
                          -- current 카탈로그 쿼리의 price>0과 대칭. features_snapshot의 part_price=0은 실제
                          -- 무료가 아니라 가격 결측(null→0 스냅샷)이라, 양쪽 모두 제외해야 분포가 정합한다.
                          AND (i.features_snapshot->>'part_price')::numeric > 0
                        """, modelId);

        Map<String, Object> result = new LinkedHashMap<>();
        for (String feature : List.of("part_price", "part_benchmark_score", "part_price_age_days")) {
            double[] expected = column(referenceRows, feature);
            double[] actual = column(current, feature);
            if (expected.length < minSamples || actual.length < minSamples) {
                result.put(feature, MockData.map("skipped", true, "reason",
                        "표본 부족(ref=" + expected.length + ", cur=" + actual.length + ", min=" + minSamples + ")"));
                continue;
            }
            Double psi = PopulationStabilityIndex.psi(expected, actual);
            result.put(feature, MockData.map("psi", psi, "refN", expected.length, "curN", actual.length));
            addPsiAlert(alerts, "catalog." + feature, psi);
        }
        return result;
    }

    // 계열 ②: 동일 모델 shadow score 최근 7일 vs 직전 7일 PSI. 버전 전환 주간은 경보 억제(표시만).
    private Map<String, Object> predictionDrift(Map<String, Object> reference) {
        if (reference == null) {
            return MockData.map("skipped", true, "reason", "기준 모델 없음");
        }
        Long modelId = ((Number) reference.get("id")).longValue();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT (s.created_at >= now() - interval '7 days') AS recent,
                               s.score
                        FROM recommendation_shadow_scores s
                        WHERE s.source_surface = 'HOME_RECOMMENDED_PARTS'
                          AND s.candidate_type = 'PART'
                          AND s.model_version_id = ?
                          AND s.created_at >= now() - interval '14 days'
                        """, modelId);
        List<Double> recent = new ArrayList<>();
        List<Double> prior = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            double score = ((Number) row.get("score")).doubleValue();
            if (Boolean.TRUE.equals(row.get("recent"))) {
                recent.add(score);
            } else {
                prior.add(score);
            }
        }
        boolean modelChanged = jdbcTemplate.queryForObject("""
                        SELECT count(*) > 0
                        FROM recommendation_model_versions
                        WHERE id = ?
                          AND activated_at IS NOT NULL
                          AND activated_at >= now() - interval '7 days'
                        """, Boolean.class, modelId);
        if (prior.size() < minSamples || recent.size() < minSamples) {
            return MockData.map("skipped", true, "reason",
                    "표본 부족(prior=" + prior.size() + ", recent=" + recent.size() + ", min=" + minSamples + ")",
                    "modelChanged", modelChanged);
        }
        Double psi = PopulationStabilityIndex.psi(toArray(prior), toArray(recent));
        // 재훈련 연계 대상 아님(표시 전용). modelChanged면 경보 억제.
        return MockData.map("psi", psi, "priorN", prior.size(), "recentN", recent.size(), "modelChanged", modelChanged);
    }

    // 계열 ③: fallback 비율·scorer scoreErrors 증분·훈련 실패율.
    private Map<String, Object> operationalMetrics(List<Map<String, Object>> alerts) {
        Map<String, Object> sources = jdbcTemplate.queryForMap("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE coalesce(nullif(event_payload->>'scoreSource',''),'UNKNOWN') = 'FALLBACK') AS fallback
                        FROM recommendation_events
                        WHERE source_surface = 'HOME_RECOMMENDED_PARTS'
                          AND event_type = 'IMPRESSION'
                          AND created_at >= now() - interval '7 days'
                        """);
        long total = ((Number) sources.get("total")).longValue();
        long fallback = ((Number) sources.get("fallback")).longValue();
        Double fallbackRatio = total == 0 ? null : round4((double) fallback / total);
        if (fallbackRatio != null && fallbackRatio > FALLBACK_WARN) {
            alerts.add(MockData.map("series", "operational.fallbackRatio", "level", "WARN", "value", fallbackRatio));
        }

        Map<String, Object> jobs = jdbcTemplate.queryForMap("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE status = 'FAILED') AS failed
                        FROM recommendation_training_jobs
                        WHERE created_at >= now() - interval '30 days'
                        """);
        long jobTotal = ((Number) jobs.get("total")).longValue();
        long jobFailed = ((Number) jobs.get("failed")).longValue();
        Double jobFailureRate = jobTotal == 0 ? null : round4((double) jobFailed / jobTotal);

        // scorer scoreErrors 증분: 직전 스냅샷의 저장값과 비교. 현재값 < 직전값이면 컨테이너 리셋으로 간주.
        // 조회 실패(null)면 '측정 불가'로 표기 — 0으로 저장하면 baseline이 오염돼 다음날 과대/과소 보고가 된다.
        Long currentScoreErrors = scorerScoreErrors();
        Long previousScoreErrors = previousScoreErrors();
        boolean measured = currentScoreErrors != null;
        Long scoreErrorsTotal;
        Long scoreErrorsDelta;
        if (!measured) {
            scoreErrorsTotal = previousScoreErrors; // 마지막 측정값 carry-forward — 0 오염 방지
            scoreErrorsDelta = null;
        } else if (previousScoreErrors == null || currentScoreErrors < previousScoreErrors) {
            scoreErrorsTotal = currentScoreErrors;
            scoreErrorsDelta = currentScoreErrors; // 최초 또는 리셋
        } else {
            scoreErrorsTotal = currentScoreErrors;
            scoreErrorsDelta = currentScoreErrors - previousScoreErrors;
        }

        return MockData.map(
                "fallbackRatio", fallbackRatio,
                "impressionCount", total,
                "trainingJobFailureRate", jobFailureRate,
                "scoreErrorsMeasured", measured,
                "scoreErrorsTotal", scoreErrorsTotal,
                "scoreErrorsDelta", scoreErrorsDelta
        );
    }

    // scorer 인메모리 scoreErrors 카운터. 조회 실패나 형식 불일치면 '측정 불가'로 null 반환(0으로 붕괴 금지).
    private Long scorerScoreErrors() {
        try {
            Object counters = scoringClient.health().get("counters");
            if (counters instanceof Map<?, ?> map && map.get("scoreErrors") instanceof Number number) {
                return number.longValue();
            }
        } catch (RuntimeException probeFailed) {
            LOGGER.info("scorer health 조회 실패(scoreErrors 증분 생략): {}", probeFailed.getMessage());
        }
        return null;
    }

    private Long previousScoreErrors() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT metrics #>> '{operational,scoreErrorsTotal}' AS score_errors
                        FROM recommendation_drift_snapshots
                        WHERE snapshot_date < current_date
                        ORDER BY snapshot_date DESC
                        LIMIT 1
                        """);
        if (rows.isEmpty() || rows.get(0).get("score_errors") == null) {
            return null;
        }
        try {
            return Long.parseLong(rows.get(0).get("score_errors").toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void upsertSnapshot(Map<String, Object> metrics, List<Map<String, Object>> alerts) {
        jdbcTemplate.update("""
                        INSERT INTO recommendation_drift_snapshots (snapshot_date, metrics, alerts)
                        VALUES (current_date, ?::jsonb, ?::jsonb)
                        ON CONFLICT (snapshot_date)
                        DO UPDATE SET metrics = excluded.metrics,
                                      alerts = excluded.alerts,
                                      created_at = now()
                        """, writeJson(metrics), writeJson(alerts));
    }

    /** 관리자 대시보드용 최근 스냅샷 목록. */
    public Map<String, Object> recentSnapshots(int days) {
        int windowDays = Math.min(Math.max(days, 1), 90);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT snapshot_date, metrics, alerts, created_at
                        FROM recommendation_drift_snapshots
                        WHERE snapshot_date >= current_date - make_interval(days => ?)
                        ORDER BY snapshot_date DESC
                        """, windowDays);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(MockData.map(
                    "snapshotDate", row.get("snapshot_date") == null ? null : row.get("snapshot_date").toString(),
                    "metrics", parseJson(row.get("metrics")),
                    "alerts", parseJsonList(row.get("alerts")),
                    "createdAt", row.get("created_at") == null ? null : row.get("created_at").toString()
            ));
        }
        return MockData.map("items", items, "total", items.size());
    }

    private void addPsiAlert(List<Map<String, Object>> alerts, String series, Double psi) {
        if (psi == null) {
            return;
        }
        if (psi >= PSI_SEVERE) {
            alerts.add(MockData.map("series", series, "level", "SEVERE", "value", round4(psi)));
        } else if (psi >= PSI_WARN) {
            alerts.add(MockData.map("series", series, "level", "WARN", "value", round4(psi)));
        }
    }

    private static double maxNumeric(Map<String, Object> psiMap, String key) {
        double max = 0.0;
        for (Object value : psiMap.values()) {
            if (value instanceof Map<?, ?> inner && inner.get(key) instanceof Number number) {
                max = Math.max(max, number.doubleValue());
            }
        }
        return max;
    }

    private static double[] column(List<Map<String, Object>> rows, String key) {
        List<Double> values = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object value = row.get(key);
            if (value instanceof Number number) {
                values.add(number.doubleValue());
            }
        }
        return toArray(values);
    }

    private static double[] toArray(List<Double> values) {
        double[] array = new double[values.size()];
        for (int i = 0; i < values.size(); i += 1) {
            array[i] = values.get(i);
        }
        return array;
    }

    private static Double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalStateException("drift snapshot JSON 직렬화 실패", error);
        }
    }

    private static Map<String, Object> parseJson(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(value.toString(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static List<Map<String, Object>> parseJsonList(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(value.toString(), new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
