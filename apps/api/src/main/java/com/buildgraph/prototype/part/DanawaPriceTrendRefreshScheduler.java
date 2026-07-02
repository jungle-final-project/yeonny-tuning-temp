package com.buildgraph.prototype.part;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "part.danawa-trend-refresh", name = "enabled", havingValue = "true")
public class DanawaPriceTrendRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DanawaPriceTrendRefreshScheduler.class);

    private final DanawaPriceTrendService danawaPriceTrendService;

    public DanawaPriceTrendRefreshScheduler(DanawaPriceTrendService danawaPriceTrendService) {
        this.danawaPriceTrendService = danawaPriceTrendService;
    }

    @Scheduled(cron = "${part.danawa-trend-refresh.cron:0 30 5 1 * *}", zone = "${part.danawa-trend-refresh.zone:Asia/Seoul}")
    public void refreshMonthlyDanawaPriceTrends() {
        Map<String, Object> result = danawaPriceTrendService.refreshMonthlyTrends();
        LOGGER.info("Monthly Danawa price trend refresh finished: {}", result);
    }
}
