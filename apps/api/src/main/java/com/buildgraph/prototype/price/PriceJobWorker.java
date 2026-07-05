package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.RabbitQueueConfig;
import com.buildgraph.prototype.part.DanawaPriceSnapshotService;
import com.buildgraph.prototype.part.NaverShoppingOfferService;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PriceJobWorker {
    private final PriceQueryService priceQueryService;
    private final NaverShoppingOfferService naverShoppingOfferService;
    private final DanawaPriceSnapshotService danawaPriceSnapshotService;
    private final boolean danawaEnabled;

    public PriceJobWorker(
            PriceQueryService priceQueryService,
            NaverShoppingOfferService naverShoppingOfferService,
            DanawaPriceSnapshotService danawaPriceSnapshotService,
            @Value("${part.danawa-refresh.enabled:false}") boolean danawaEnabled
    ) {
        this.priceQueryService = priceQueryService;
        this.naverShoppingOfferService = naverShoppingOfferService;
        this.danawaPriceSnapshotService = danawaPriceSnapshotService;
        this.danawaEnabled = danawaEnabled;
    }

    @RabbitListener(queues = RabbitQueueConfig.PRICE_REFRESH_QUEUE)
    public void runPriceRefresh(Map<String, Object> payload) {
        String priceJobId = requiredText(payload.get("priceJobId"));
        try {
            priceQueryService.startPriceJob(priceJobId);
            // 이전 구현은 refreshOffers(null,null,true)로 첫 20행(정렬상 사실상 CASE만)만 갱신하고 SUCCEEDED로 표시했다.
            // 전 카테고리를 순회하는 일일 갱신 경로를 호출해 관리자 '실행' 버튼이 실제로 전체 가격을 최신화하게 한다.
            naverShoppingOfferService.refreshDailyOffers();
            // 다나와 수집은 스케줄러 플래그(part.danawa-refresh.enabled)를 이 경로에서도 존중한다(OFF면 스크래핑 안 함).
            if (danawaEnabled) {
                danawaPriceSnapshotService.refreshDailySnapshots();
            }
            priceQueryService.completePriceJob(priceJobId);
        } catch (RuntimeException error) {
            priceQueryService.failPriceJob(priceJobId, safeReason(error));
            throw error;
        }
    }

    private static String requiredText(Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("priceJobId가 필요합니다.");
        }
        return text;
    }

    private static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
