package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.buildgraph.prototype.common.RabbitQueueConfig;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RecommendationEventPublisherTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void springContextCanCreatePublisherWithExecutorConfig() {
        new ApplicationContextRunner()
                .withUserConfiguration(PublisherContextConfig.class)
                .run(context -> assertThat(context).hasSingleBean(RecommendationEventPublisher.class));
    }

    @Test
    void publishBulkEventsSubmitsRabbitPublishOutsideRequestThread() {
        CapturingExecutor executor = new CapturingExecutor();
        RecommendationEventPublisher publisher = new RecommendationEventPublisher(
                rabbitTemplate,
                executor,
                1,
                Duration.ZERO
        );
        Map<String, Object> request = Map.of("events", List.of(Map.of("eventType", "IMPRESSION")));

        Map<String, Object> response = publisher.publishBulkEvents(request, USER, 1);

        assertThat(response)
                .containsEntry("accepted", true)
                .containsEntry("queued", 1);
        verifyNoInteractions(rabbitTemplate);

        executor.runOnlyTask();

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitQueueConfig.JOBS_EXCHANGE),
                eq(RabbitQueueConfig.RECOMMENDATION_EVENTS_ROUTING_KEY),
                argThat((Map<?, ?> payload) -> request.equals(payload.get("request"))
                        && payload.get("user") instanceof Map<?, ?> user
                        && USER.id().equals(user.get("id")))
        );
    }

    @Test
    void publishBulkEventsReturnsServiceUnavailableWhenLocalQueueIsFull() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("full");
        };
        RecommendationEventPublisher publisher = new RecommendationEventPublisher(
                rabbitTemplate,
                rejectingExecutor,
                1,
                Duration.ZERO
        );

        assertThatThrownBy(() -> publisher.publishBulkEvents(Map.of("events", List.of()), USER, 1))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void backgroundPublishRetriesRabbitFailures() {
        RecommendationEventPublisher publisher = new RecommendationEventPublisher(
                rabbitTemplate,
                Runnable::run,
                2,
                Duration.ZERO
        );
        doThrow(new AmqpException("down"))
                .doNothing()
                .when(rabbitTemplate)
                .convertAndSend(
                        eq(RabbitQueueConfig.JOBS_EXCHANGE),
                        eq(RabbitQueueConfig.RECOMMENDATION_EVENTS_ROUTING_KEY),
                        anyMap()
                );

        Map<String, Object> response = publisher.publishBulkEvents(Map.of("events", List.of()), USER, 1);

        assertThat(response)
                .containsEntry("accepted", true)
                .containsEntry("queued", 1);
        verify(rabbitTemplate, times(2)).convertAndSend(
                eq(RabbitQueueConfig.JOBS_EXCHANGE),
                eq(RabbitQueueConfig.RECOMMENDATION_EVENTS_ROUTING_KEY),
                anyMap()
        );
    }

    private static final class CapturingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runOnlyTask() {
            assertThat(tasks).hasSize(1);
            tasks.get(0).run();
        }
    }

    @TestConfiguration
    @Import({RecommendationEventPublisher.class, RecommendationEventPublisherConfig.class})
    static class PublisherContextConfig {
        @Bean
        RabbitTemplate rabbitTemplate() {
            return mock(RabbitTemplate.class);
        }
    }
}
