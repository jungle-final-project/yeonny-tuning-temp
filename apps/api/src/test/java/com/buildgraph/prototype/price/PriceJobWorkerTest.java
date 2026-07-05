package com.buildgraph.prototype.price;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.buildgraph.prototype.part.DanawaPriceSnapshotService;
import com.buildgraph.prototype.part.NaverShoppingOfferService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PriceJobWorkerTest {
    private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
    private final NaverShoppingOfferService naverShoppingOfferService = mock(NaverShoppingOfferService.class);
    private final DanawaPriceSnapshotService danawaPriceSnapshotService = mock(DanawaPriceSnapshotService.class);
    private final PriceJobWorker worker = new PriceJobWorker(
            priceQueryService,
            naverShoppingOfferService,
            danawaPriceSnapshotService,
            true
    );

    @Test
    void priceJobRefreshesAllNaverOffersAndDanawaSnapshotsBeforeCompleting() {
        worker.runPriceRefresh(Map.of("priceJobId", "job-public-id"));

        // 관리자 '실행'은 첫 20행이 아니라 전 카테고리를 순회하는 일일 갱신 경로를 타야 한다.
        InOrder order = inOrder(priceQueryService, naverShoppingOfferService, danawaPriceSnapshotService);
        order.verify(priceQueryService).startPriceJob("job-public-id");
        order.verify(naverShoppingOfferService).refreshDailyOffers();
        order.verify(danawaPriceSnapshotService).refreshDailySnapshots();
        order.verify(priceQueryService).completePriceJob("job-public-id");
        verify(priceQueryService, never()).failPriceJob(eq("job-public-id"), anyString());
    }

    @Test
    void priceJobSkipsDanawaSnapshotsWhenDanawaDisabled() {
        PriceJobWorker disabledWorker = new PriceJobWorker(
                priceQueryService,
                naverShoppingOfferService,
                danawaPriceSnapshotService,
                false
        );

        disabledWorker.runPriceRefresh(Map.of("priceJobId", "job-public-id"));

        verify(naverShoppingOfferService).refreshDailyOffers();
        verify(danawaPriceSnapshotService, never()).refreshDailySnapshots();
        verify(priceQueryService).completePriceJob("job-public-id");
    }

    @Test
    void priceJobStoresFailedStatusWhenCollectionFails() {
        doThrow(new RuntimeException("naver down"))
                .when(naverShoppingOfferService)
                .refreshDailyOffers();

        assertThatThrownBy(() -> worker.runPriceRefresh(Map.of("priceJobId", "job-public-id")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("naver down");

        verify(priceQueryService).startPriceJob("job-public-id");
        verify(priceQueryService).failPriceJob("job-public-id", "naver down");
        verify(priceQueryService, never()).completePriceJob("job-public-id");
    }
}
