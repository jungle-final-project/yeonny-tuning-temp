package com.buildgraph.prototype.part.price;

import com.buildgraph.prototype.common.DemoFreezeGuard;
import com.buildgraph.prototype.common.PipelineJobRunRecorder;
import com.buildgraph.prototype.recommender.partvector.PartVectorCalculator;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "part.price-refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
/* 일일 기준으로 가격 등을 갱신하는 스캐줄러 */
public class PartPriceRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PartPriceRefreshScheduler.class);

    private final NaverShoppingOfferService naverShoppingOfferService;
    private final DemoFreezeGuard demoFreezeGuard;
    private final PipelineJobRunRecorder jobRunRecorder;
    private final PartVectorCalculator partVectorCalculator;

    public PartPriceRefreshScheduler(
            NaverShoppingOfferService naverShoppingOfferService,
            DemoFreezeGuard demoFreezeGuard,
            PipelineJobRunRecorder jobRunRecorder,
            PartVectorCalculator partVectorCalculator
    ) {
        this.naverShoppingOfferService = naverShoppingOfferService;
        this.demoFreezeGuard = demoFreezeGuard;
        this.jobRunRecorder = jobRunRecorder;
        this.partVectorCalculator = partVectorCalculator;
    }

    @Scheduled(cron = "${part.price-refresh.cron:0 0 4 * * *}", zone = "${part.price-refresh.zone:Asia/Seoul}")
    public void refreshDailyExternalOffers() {
        if (demoFreezeGuard.frozen()) {
            LOGGER.info("Daily part price refresh skipped: demo freeze is on");
            jobRunRecorder.recordSkippedFrozen("PART_PRICE_REFRESH");
            return;
        }
        jobRunRecorder.run("PART_PRICE_REFRESH", () -> {
            Map<String, Object> result = naverShoppingOfferService.refreshDailyOffers();
            partVectorCalculator.recalculateAll();
            LOGGER.info("Daily part price refresh finished: {}", result);
            return result;
        });
    }
}
