package com.buildgraph.prototype.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SchedulingConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingEnabledByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void schedulingEnabledWhenPropertyTrue() {
        runner.withPropertyValues("buildgraph.scheduling.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    void schedulingDisabledWhenPropertyFalse() {
        // web-facing(스케일) 태스크: BUILDGRAPH_SCHEDULING_ENABLED=false → @EnableScheduling 미등록
        runner.withPropertyValues("buildgraph.scheduling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SchedulingConfig.class));
    }
}
