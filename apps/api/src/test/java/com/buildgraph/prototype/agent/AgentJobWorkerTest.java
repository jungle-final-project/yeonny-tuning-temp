package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class AgentJobWorkerTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerContextConfig.class);

    @Test
    void workerEnabledByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(AgentJobWorker.class));
    }

    @Test
    void workerDisabledWhenPropertyFalse() {
        contextRunner.withPropertyValues("agent.worker.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AgentJobWorker.class));
    }

    @TestConfiguration
    @Import(AgentJobWorker.class)
    static class WorkerContextConfig {
        @Bean
        AgentTraceService agentTraceService() {
            return mock(AgentTraceService.class);
        }

        @Bean
        AgentRunner agentRunner() {
            return mock(AgentRunner.class);
        }
    }
}
