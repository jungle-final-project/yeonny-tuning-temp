package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.DemoFreezeGuard;
import com.buildgraph.prototype.common.PipelineJobRunRecorder;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * M3 일일 drift 스냅샷 스케줄러(설계 §4). 기본 off. drift 계산(읽기 전용 관측)은 매일 기록하고,
 * 카탈로그 PSI가 심각(>0.3)하면 재훈련을 즉시 트리거한다 — 단 retrain-on-severe가 켜져 있고 데모
 * 동결이 아닐 때만(관측과 재훈련 트리거를 분리 opt-in). 재훈련은 pipeline_job_runs에 trigger_type=
 * DRIFT_TRIGGERED로 기록돼 스케줄 트리거와 구분된다.
 */
@Component
@ConditionalOnProperty(prefix = "recommendation.drift", name = "enabled", havingValue = "true")
public class RecommendationDriftScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationDriftScheduler.class);
    private static final String DRIFT_JOB = "RECOMMENDATION_DRIFT";
    private static final String RETRAIN_JOB = "RECOMMENDATION_AUTO_RETRAIN";

    private final RecommendationDriftService driftService;
    private final RecommendationTrainingService trainingService;
    private final DemoFreezeGuard demoFreezeGuard;
    private final PipelineJobRunRecorder jobRunRecorder;
    private final boolean retrainOnSevere;
    private final int minNewEvents;
    private final int minNewPositives;
    private final int minRows;

    public RecommendationDriftScheduler(
            RecommendationDriftService driftService,
            RecommendationTrainingService trainingService,
            DemoFreezeGuard demoFreezeGuard,
            PipelineJobRunRecorder jobRunRecorder,
            @Value("${recommendation.drift.retrain-on-severe:false}") boolean retrainOnSevere,
            @Value("${recommendation.auto-retrain.min-new-events:100}") int minNewEvents,
            @Value("${recommendation.auto-retrain.min-new-positives:10}") int minNewPositives,
            @Value("${recommendation.auto-retrain.min-rows:50}") int minRows
    ) {
        this.driftService = driftService;
        this.trainingService = trainingService;
        this.demoFreezeGuard = demoFreezeGuard;
        this.jobRunRecorder = jobRunRecorder;
        this.retrainOnSevere = retrainOnSevere;
        this.minNewEvents = minNewEvents;
        this.minNewPositives = minNewPositives;
        this.minRows = minRows;
    }

    @Scheduled(
            cron = "${recommendation.drift.cron:0 50 4 * * *}",
            zone = "${recommendation.drift.zone:Asia/Seoul}"
    )
    public void runDailyDrift() {
        AtomicReference<Map<String, Object>> snapshot = new AtomicReference<>();
        jobRunRecorder.run(DRIFT_JOB, () -> {
            Map<String, Object> result = driftService.computeDailySnapshot();
            snapshot.set(result);
            LOGGER.info("Drift snapshot: {}", result);
            return result;
        });

        Map<String, Object> result = snapshot.get();
        boolean severe = result != null && Boolean.TRUE.equals(result.get("catalogPsiSevere"));
        if (severe && retrainOnSevere) {
            if (demoFreezeGuard.frozen()) {
                // 침묵 스킵 금지(원칙 5): 심각 드리프트인데 동결로 재훈련을 못 한 사실을 이력에 남긴다.
                LOGGER.info("Severe drift retraining skipped: demo freeze is on");
                jobRunRecorder.recordSkippedFrozen(RETRAIN_JOB, "DRIFT_TRIGGERED");
            } else {
                // min-interval-days=0으로 즉시 재훈련(주 1회 스케줄을 안 기다림). trigger_type=DRIFT_TRIGGERED.
                LOGGER.warn("Severe catalog PSI drift detected → triggering immediate retraining");
                jobRunRecorder.run(RETRAIN_JOB, "DRIFT_TRIGGERED", () ->
                        trainingService.runAutoRetrain(minNewEvents, minNewPositives, 0, minRows));
            }
        }
    }
}
