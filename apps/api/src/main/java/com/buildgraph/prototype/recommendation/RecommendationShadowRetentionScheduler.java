package com.buildgraph.prototype.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * recommendation_shadow_scores 보존 기간 정리.
 *
 * 홈 요청마다 후보 수만큼 shadow 행이 쌓이는데 정리 장치가 없어 무기한 성장했다(감사 B9).
 * 학습·비교 분석에 필요한 최근 구간만 남기고 오래된 행을 매일 삭제한다.
 */
@Component
@ConditionalOnProperty(prefix = "recommendation.shadow", name = "retention-enabled", havingValue = "true", matchIfMissing = true)
public class RecommendationShadowRetentionScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecommendationShadowRetentionScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final int retentionDays;

    public RecommendationShadowRetentionScheduler(
            JdbcTemplate jdbcTemplate,
            @Value("${recommendation.shadow.retention-days:30}") int retentionDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = Math.max(retentionDays, 1);
    }

    @Scheduled(cron = "${recommendation.shadow.retention-cron:0 40 3 * * *}", zone = "${recommendation.shadow.retention-zone:Asia/Seoul}")
    public void purgeExpiredShadowScores() {
        int deleted = jdbcTemplate.update(
                "DELETE FROM recommendation_shadow_scores WHERE created_at < now() - make_interval(days => ?)",
                retentionDays
        );
        LOGGER.info("Recommendation shadow score retention finished: deleted={}, retentionDays={}", deleted, retentionDays);
    }
}
