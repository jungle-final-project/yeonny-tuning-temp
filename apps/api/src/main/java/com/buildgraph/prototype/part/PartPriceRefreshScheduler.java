package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DemoFreezeGuard;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "part.price-refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PartPriceRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PartPriceRefreshScheduler.class);

    private final NaverShoppingOfferService naverShoppingOfferService;
    private final DemoFreezeGuard demoFreezeGuard;

    public PartPriceRefreshScheduler(NaverShoppingOfferService naverShoppingOfferService, DemoFreezeGuard demoFreezeGuard) {
        this.naverShoppingOfferService = naverShoppingOfferService;
        this.demoFreezeGuard = demoFreezeGuard;
    }

    @Scheduled(cron = "${part.price-refresh.cron:0 0 4 * * *}", zone = "${part.price-refresh.zone:Asia/Seoul}")
    public void refreshDailyExternalOffers() {
        if (demoFreezeGuard.frozen()) {
            LOGGER.info("Daily part price refresh skipped: demo freeze is on");
            return;
        }
        Map<String, Object> result = naverShoppingOfferService.refreshDailyOffers();
        LOGGER.info("Daily part price refresh finished: {}", result);
    }
}
