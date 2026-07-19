package com.buildgraph.prototype.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.buildgraph.prototype.part.price.DanawaPriceSnapshotService;
import com.buildgraph.prototype.part.price.NaverShoppingOfferService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class PriceJobWorkerWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerContextConfig.class);

    @Test
    void workerEnabledByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(PriceJobWorker.class));
    }

    @Test
    void workerDisabledWhenPropertyFalse() {
        contextRunner.withPropertyValues("part.price-refresh.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(PriceJobWorker.class));
    }

    @TestConfiguration
    @Import(PriceJobWorker.class)
    static class WorkerContextConfig {
        @Bean
        PriceQueryService priceQueryService() {
            return mock(PriceQueryService.class);
        }

        @Bean
        NaverShoppingOfferService naverShoppingOfferService() {
            return mock(NaverShoppingOfferService.class);
        }

        @Bean
        DanawaPriceSnapshotService danawaPriceSnapshotService() {
            return mock(DanawaPriceSnapshotService.class);
        }
    }
}
