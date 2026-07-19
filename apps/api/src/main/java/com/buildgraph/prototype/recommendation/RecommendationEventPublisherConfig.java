package com.buildgraph.prototype.recommendation;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class RecommendationEventPublisherConfig {

    @Bean(name = "recommendationEventPublisherExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskExecutor recommendationEventPublisherExecutor(
            @Value("${recommendation.events.publisher.workers:2}") int workers,
            @Value("${recommendation.events.publisher.queue-capacity:2048}") int queueCapacity,
            @Value("${recommendation.events.publisher.shutdown-wait-seconds:5}") int shutdownWaitSeconds
    ) {
        int poolSize = Math.max(1, workers);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("recommendation-event-publisher-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, shutdownWaitSeconds));
        executor.initialize();
        return executor;
    }
}
