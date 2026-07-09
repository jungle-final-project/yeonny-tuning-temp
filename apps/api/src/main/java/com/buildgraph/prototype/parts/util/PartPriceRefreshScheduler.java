package com.buildgraph.prototype.parts.util;

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

    public PartPriceRefreshScheduler(NaverShoppingOfferService naverShoppingOfferService) {
        this.naverShoppingOfferService = naverShoppingOfferService;
    }

    @Scheduled(cron = "${part.price-refresh.cron:0 0 4 * * *}", zone = "${part.price-refresh.zone:Asia/Seoul}")
    public void refreshDailyExternalOffers() {
        Map<String, Object> result = naverShoppingOfferService.refreshDailyOffers();
        LOGGER.info("Daily part price refresh finished: {}", result);
    }
}
