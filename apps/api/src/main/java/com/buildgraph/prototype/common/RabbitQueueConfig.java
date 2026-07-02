package com.buildgraph.prototype.common;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitQueueConfig {
    public static final String AGENT_EXCHANGE = "buildgraph.agent.exchange";
    public static final String AGENT_RUN_QUEUE = "buildgraph.agent.run";
    public static final String AGENT_RUN_DLQ = "buildgraph.agent.run.dlq";
    public static final String AGENT_RUN_ROUTING_KEY = "agent.run";

    public static final String JOBS_EXCHANGE = "buildgraph.jobs.exchange";
    public static final String PRICE_REFRESH_QUEUE = "buildgraph.price.refresh";
    public static final String PRICE_REFRESH_DLQ = "buildgraph.price.refresh.dlq";
    public static final String PRICE_REFRESH_ROUTING_KEY = "price.refresh";

    @Bean
    MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    DirectExchange agentExchange() {
        return new DirectExchange(AGENT_EXCHANGE, true, false);
    }

    @Bean
    Queue agentRunQueue() {
        return QueueBuilder.durable(AGENT_RUN_QUEUE)
                .deadLetterExchange(AGENT_EXCHANGE)
                .deadLetterRoutingKey(AGENT_RUN_DLQ)
                .build();
    }

    @Bean
    Queue agentRunDlq() {
        return QueueBuilder.durable(AGENT_RUN_DLQ).build();
    }

    @Bean
    Binding agentRunBinding() {
        return BindingBuilder.bind(agentRunQueue()).to(agentExchange()).with(AGENT_RUN_ROUTING_KEY);
    }

    @Bean
    Binding agentRunDlqBinding() {
        return BindingBuilder.bind(agentRunDlq()).to(agentExchange()).with(AGENT_RUN_DLQ);
    }

    @Bean
    DirectExchange jobsExchange() {
        return new DirectExchange(JOBS_EXCHANGE, true, false);
    }

    @Bean
    Queue priceRefreshQueue() {
        return QueueBuilder.durable(PRICE_REFRESH_QUEUE)
                .deadLetterExchange(JOBS_EXCHANGE)
                .deadLetterRoutingKey(PRICE_REFRESH_DLQ)
                .build();
    }

    @Bean
    Queue priceRefreshDlq() {
        return QueueBuilder.durable(PRICE_REFRESH_DLQ).build();
    }

    @Bean
    Binding priceRefreshBinding() {
        return BindingBuilder.bind(priceRefreshQueue()).to(jobsExchange()).with(PRICE_REFRESH_ROUTING_KEY);
    }

    @Bean
    Binding priceRefreshDlqBinding() {
        return BindingBuilder.bind(priceRefreshDlq()).to(jobsExchange()).with(PRICE_REFRESH_DLQ);
    }
}
