package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.RabbitQueueConfig;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "recommendation.events.worker.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RecommendationEventWorker {
    private static final Logger log = LoggerFactory.getLogger(RecommendationEventWorker.class);

    private final RecommendationLearningService recommendationLearningService;

    public RecommendationEventWorker(RecommendationLearningService recommendationLearningService) {
        this.recommendationLearningService = recommendationLearningService;
    }

    @RabbitListener(queues = RabbitQueueConfig.RECOMMENDATION_EVENTS_QUEUE)
    public void recordBulkEvents(Map<String, Object> payload) {
        Map<String, Object> request = mapValue(payload.get("request"), "request");
        CurrentUserService.CurrentUser user = currentUser(mapValue(payload.get("user"), "user"));

        try {
            Map<String, Object> result = recommendationLearningService.recordEvents(request, user);
            log.debug("Recommendation events recorded asynchronously: userId={}, count={}", user.id(), result.get("count"));
        } catch (RuntimeException error) {
            log.warn("Recommendation event async recording failed: userId={}, reason={}", user.id(), safeReason(error));
            throw error;
        }
    }

    private static CurrentUserService.CurrentUser currentUser(Map<String, Object> row) {
        return new CurrentUserService.CurrentUser(
                longValue(row.get("internalId")),
                requiredText(row.get("id"), "user.id"),
                requiredText(row.get("email"), "user.email"),
                requiredText(row.get("name"), "user.name"),
                requiredText(row.get("role"), "user.role"),
                row.get("createdAt")
        );
    }

    private static Map<String, Object> mapValue(Object value, String fieldName) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(fieldName + " must be an object.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
        return result;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = requiredText(value, "user.internalId");
        return Long.valueOf(text);
    }

    private static String requiredText(Object value, String fieldName) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return text;
    }

    private static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
