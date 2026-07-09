package com.buildgraph.prototype.price;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.buildgraph.prototype.verification.util.NaverShoppingOfferService;

class PriceJobWorkerTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NaverShoppingOfferService naverShoppingOfferService = mock(NaverShoppingOfferService.class);
    private final DanawaBackupCollector danawaBackupCollector = mock(DanawaBackupCollector.class);
    private final PriceAlertEvaluator priceAlertEvaluator = mock(PriceAlertEvaluator.class);
    private final PriceJobWorker worker = new PriceJobWorker(
            jdbcTemplate,
            naverShoppingOfferService,
            danawaBackupCollector,
            priceAlertEvaluator
    );

    @Test
    void runJobRefreshesNaverAndEvaluatesAlerts() {
        when(jdbcTemplate.update(anyString(), eq(1L))).thenReturn(1);
        when(naverShoppingOfferService.refreshDailyOffers()).thenReturn(Map.of(
                "configured", true,
                "attempted", 1,
                "updated", 1,
                "skipped", 0,
                "failed", 0
        ));
        when(priceAlertEvaluator.evaluateActiveAlerts()).thenReturn(Map.of("triggered", 0, "failed", 0));

        worker.runJob(1L);

        verify(naverShoppingOfferService).refreshDailyOffers();
        verify(danawaBackupCollector, never()).collectBackupPrices();
        verify(priceAlertEvaluator).evaluateActiveAlerts();
        verify(jdbcTemplate).update(anyString(), eq("SUCCEEDED"), anyString(), eq(1L));
    }

    @Test
    void runJobUsesBackupWhenNaverIsUnavailable() {
        when(jdbcTemplate.update(anyString(), eq(2L))).thenReturn(1);
        when(naverShoppingOfferService.refreshDailyOffers()).thenReturn(Map.of(
                "configured", false,
                "attempted", 0,
                "updated", 0,
                "skipped", 0,
                "failed", 0
        ));
        when(danawaBackupCollector.collectBackupPrices()).thenReturn(Map.of("updated", 1, "failed", 0));
        when(priceAlertEvaluator.evaluateActiveAlerts()).thenReturn(Map.of("triggered", 0, "failed", 0));

        worker.runJob(2L);

        verify(danawaBackupCollector).collectBackupPrices();
        verify(jdbcTemplate).update(anyString(), eq("SUCCEEDED"), anyString(), eq(2L));
    }
}
