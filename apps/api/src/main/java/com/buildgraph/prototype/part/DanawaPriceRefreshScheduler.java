package com.buildgraph.prototype.part;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "part.danawa-refresh", name = "enabled", havingValue = "true")
public class DanawaPriceRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DanawaPriceRefreshScheduler.class);

    private final DanawaPriceSnapshotService danawaPriceSnapshotService;

    public DanawaPriceRefreshScheduler(DanawaPriceSnapshotService danawaPriceSnapshotService) {
        this.danawaPriceSnapshotService = danawaPriceSnapshotService;
    }

    @Scheduled(cron = "${part.danawa-refresh.cron:0 30 4 * * *}", zone = "${part.danawa-refresh.zone:Asia/Seoul}")
    public void refreshDailyDanawaSnapshots() {
        Map<String, Object> result = danawaPriceSnapshotService.refreshDailySnapshots();
        LOGGER.info("Daily Danawa backup price snapshot refresh finished: {}", result);
    }
}
