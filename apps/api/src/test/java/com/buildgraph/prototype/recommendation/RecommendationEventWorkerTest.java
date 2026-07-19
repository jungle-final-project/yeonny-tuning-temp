package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class RecommendationEventWorkerTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerContextConfig.class);

    @Test
    void workerEnabledByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(RecommendationEventWorker.class));
    }

    @Test
    void workerEnabledWhenPropertyTrue() {
        contextRunner.withPropertyValues("recommendation.events.worker.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RecommendationEventWorker.class));
    }

    @Test
    void workerDisabledWhenPropertyFalse() {
        contextRunner.withPropertyValues("recommendation.events.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RecommendationEventWorker.class));
    }

    @TestConfiguration
    @Import(RecommendationEventWorker.class)
    static class WorkerContextConfig {
        @Bean
        RecommendationLearningService recommendationLearningService() {
            return mock(RecommendationLearningService.class);
        }
    }
}
