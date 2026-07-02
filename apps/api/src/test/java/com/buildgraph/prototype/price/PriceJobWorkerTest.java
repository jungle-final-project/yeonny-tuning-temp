package com.buildgraph.prototype.price;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
            danawaPriceSnapshotService
    );

    @Test
    void priceJobRefreshesNaverOffersAndDanawaSnapshotsBeforeCompleting() {
        worker.runPriceRefresh(Map.of("priceJobId", "job-public-id"));

        InOrder order = inOrder(priceQueryService, naverShoppingOfferService, danawaPriceSnapshotService);
        order.verify(priceQueryService).startPriceJob("job-public-id");
        order.verify(naverShoppingOfferService).refreshOffers(isNull(), isNull(), eq(true));
        order.verify(danawaPriceSnapshotService).refreshSnapshots(isNull(), isNull(), eq(true));
        order.verify(priceQueryService).completePriceJob("job-public-id");
        verify(priceQueryService, never()).failPriceJob(eq("job-public-id"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void priceJobStoresFailedStatusWhenCollectionFails() {
        doThrow(new RuntimeException("naver down"))
                .when(naverShoppingOfferService)
                .refreshOffers(isNull(), isNull(), eq(true));

        assertThatThrownBy(() -> worker.runPriceRefresh(Map.of("priceJobId", "job-public-id")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("naver down");

        verify(priceQueryService).startPriceJob("job-public-id");
        verify(priceQueryService).failPriceJob("job-public-id", "naver down");
        verify(priceQueryService, never()).completePriceJob("job-public-id");
    }
}
