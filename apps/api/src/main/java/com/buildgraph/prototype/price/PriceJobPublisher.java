package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.RabbitQueueConfig;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class PriceJobPublisher {
    private final RabbitTemplate rabbitTemplate;

    public PriceJobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishRefresh(String priceJobId) {
        Map<String, Object> payload = MockData.map("priceJobId", priceJobId);
        rabbitTemplate.convertAndSend(
                RabbitQueueConfig.JOBS_EXCHANGE,
                RabbitQueueConfig.PRICE_REFRESH_ROUTING_KEY,
                payload
        );
    }
}
