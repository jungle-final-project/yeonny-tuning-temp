package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.RabbitQueueConfig;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(RecommendationEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final Executor publisherExecutor;
    private final int retryMaxAttempts;
    private final Duration retryDelay;

    @Autowired
    public RecommendationEventPublisher(
            RabbitTemplate rabbitTemplate,
            @Qualifier("recommendationEventPublisherExecutor") Executor publisherExecutor,
            @Value("${recommendation.events.publisher.retry-max-attempts:3}") int retryMaxAttempts,
            @Value("${recommendation.events.publisher.retry-delay-ms:100}") long retryDelayMs
    ) {
        this(rabbitTemplate, publisherExecutor, retryMaxAttempts, Duration.ofMillis(Math.max(0, retryDelayMs)));
    }

    RecommendationEventPublisher(
            RabbitTemplate rabbitTemplate,
            Executor publisherExecutor,
            int retryMaxAttempts,
            Duration retryDelay
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.publisherExecutor = publisherExecutor;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryDelay = retryDelay == null || retryDelay.isNegative() ? Duration.ZERO : retryDelay;
    }

    public Map<String, Object> publishBulkEvents(
            Map<String, Object> request,
            CurrentUserService.CurrentUser user,
            int queuedCount
    ) {
        Map<String, Object> message = MockData.map(
                "request", request,
                "user", userPayload(user)
        );
        try {
            publisherExecutor.execute(() -> publishToRabbitWithRetry(message, user.id(), queuedCount));
            return MockData.map("accepted", true, "queued", queuedCount);
        } catch (RejectedExecutionException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recommendation event publish queue is full.", error);
        }
    }

    private void publishToRabbitWithRetry(Map<String, Object> message, String userId, int queuedCount) {
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt += 1) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitQueueConfig.JOBS_EXCHANGE,
                        RabbitQueueConfig.RECOMMENDATION_EVENTS_ROUTING_KEY,
                        message
                );
                if (attempt > 1) {
                    log.info(
                            "Recommendation events published after retry: userId={}, count={}, attempt={}",
                            userId,
                            queuedCount,
                            attempt
                    );
                }
                return;
            } catch (AmqpException error) {
                if (attempt >= retryMaxAttempts) {
                    log.warn(
                            "Recommendation event publish failed after retries: userId={}, count={}, attempts={}, reason={}",
                            userId,
                            queuedCount,
                            retryMaxAttempts,
                            safeReason(error)
                    );
                    return;
                }
                sleepBeforeRetry(userId, queuedCount, attempt, error);
            }
        }
    }

    private void sleepBeforeRetry(String userId, int queuedCount, int attempt, AmqpException error) {
        log.warn(
                "Recommendation event publish retry scheduled: userId={}, count={}, attempt={}, reason={}",
                userId,
                queuedCount,
                attempt,
                safeReason(error)
        );
        if (retryDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> userPayload(CurrentUserService.CurrentUser user) {
        return MockData.map(
                "internalId", user.internalId(),
                "id", user.id(),
                "email", user.email(),
                "name", user.name(),
                "role", user.role(),
                "createdAt", user.createdAt()
        );
    }

    private static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
