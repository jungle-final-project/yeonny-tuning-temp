package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.DemoFreezeGuard;
import com.buildgraph.prototype.common.PipelineJobRunRecorder;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * M2 자동 재훈련 스케줄러(설계 docs/mlops-maturity-design.md §3). 주 1회 조건을 확인해 충족 시 dataset·job을
 * 자동 생성한다(오염 가드 포함). 승급은 하지 않는다(auto-train, manual-promote — 원칙 2).
 *
 * 기본 비활성(enabled=false): 자동 학습을 켤지는 운영 결정 사항이라 명시적으로 활성화해야 동작한다.
 * DemoFreezeGuard는 Java의 잡 '생성'만 막는다 — 워커(Python) 측 동결은 데모 절차(RECOMMENDATION_OPERATIONS §6)로 처리.
 */
@Component
@ConditionalOnProperty(prefix = "recommendation.auto-retrain", name = "enabled", havingValue = "true")
public class RecommendationRetrainScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationRetrainScheduler.class);
    private static final String JOB_NAME = "RECOMMENDATION_AUTO_RETRAIN";

    private final RecommendationTrainingService trainingService;
    private final DemoFreezeGuard demoFreezeGuard;
    private final PipelineJobRunRecorder jobRunRecorder;
    private final int minNewEvents;
    private final int minNewPositives;
    private final int minIntervalDays;
    private final int minRows;

    public RecommendationRetrainScheduler(
            RecommendationTrainingService trainingService,
            DemoFreezeGuard demoFreezeGuard,
            PipelineJobRunRecorder jobRunRecorder,
            @Value("${recommendation.auto-retrain.min-new-events:100}") int minNewEvents,
            @Value("${recommendation.auto-retrain.min-new-positives:10}") int minNewPositives,
            @Value("${recommendation.auto-retrain.min-interval-days:7}") int minIntervalDays,
            // 워커의 RECOMMENDATION_TRAINING_MIN_ROWS와 일치시켜야 doomed job 생성을 막는다.
            @Value("${recommendation.auto-retrain.min-rows:50}") int minRows
    ) {
        this.trainingService = trainingService;
        this.demoFreezeGuard = demoFreezeGuard;
        this.jobRunRecorder = jobRunRecorder;
        this.minNewEvents = minNewEvents;
        this.minNewPositives = minNewPositives;
        this.minIntervalDays = minIntervalDays;
        this.minRows = minRows;
    }

    @Scheduled(
            cron = "${recommendation.auto-retrain.cron:0 0 3 * * SUN}",
            zone = "${recommendation.auto-retrain.zone:Asia/Seoul}"
    )
    public void runWeeklyAutoRetrain() {
        if (demoFreezeGuard.frozen()) {
            LOGGER.info("Auto retrain skipped: demo freeze is on");
            jobRunRecorder.recordSkippedFrozen(JOB_NAME);
            return;
        }
        jobRunRecorder.run(JOB_NAME, () -> {
            Map<String, Object> result = trainingService.runAutoRetrain(minNewEvents, minNewPositives, minIntervalDays, minRows);
            LOGGER.info("Auto retrain finished: {}", result);
            return result;
        });
    }
}
