package com.originguard.agent.application;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "originguard.agent.async.enabled", havingValue = "true")
public class AgentMessagingConfiguration {
    public static final String EXCHANGE = "originguard.agent";
    public static final String ROUTING_KEY = "analysis.requested";
    public static final String DEAD_LETTER_EXCHANGE = "originguard.agent.dlx";

    @Bean
    DirectExchange agentExchange() { return new DirectExchange(EXCHANGE, true, false); }

    @Bean
    DirectExchange agentDeadLetterExchange() { return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false); }

    @Bean
    Queue agentQueue(@Value("${originguard.agent.async.queue:originguard.agent.analysis}") String name) {
        return QueueBuilder.durable(name).deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(name + ".failed").build();
    }

    @Bean
    Queue agentDeadLetterQueue(@Value("${originguard.agent.async.queue:originguard.agent.analysis}") String name) {
        return QueueBuilder.durable(name + ".failed").build();
    }

    @Bean
    Binding agentBinding(Queue agentQueue, DirectExchange agentExchange) {
        return BindingBuilder.bind(agentQueue).to(agentExchange).with(ROUTING_KEY);
    }

    @Bean
    Binding agentDeadLetterBinding(Queue agentDeadLetterQueue, DirectExchange agentDeadLetterExchange,
            @Value("${originguard.agent.async.queue:originguard.agent.analysis}") String name) {
        return BindingBuilder.bind(agentDeadLetterQueue).to(agentDeadLetterExchange).with(name + ".failed");
    }
}
