package com.originguard.agent.application;

import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "originguard.agent.async.enabled", havingValue = "true")
public class AgentDeadLetterService {
    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final String deadQueue;

    public AgentDeadLetterService(RabbitTemplate rabbit, ObjectMapper objectMapper,
            @Value("${originguard.agent.async.queue:originguard.agent.analysis}") String queue) {
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.deadQueue = queue + ".failed";
    }

    public int replay(int limit) {
        int replayed = 0;
        while (replayed < Math.max(1, Math.min(limit, 100))) {
            Message dead = rabbit.receive(deadQueue);
            if (dead == null) break;
            AgentExecutionMessage original = objectMapper.readValue(dead.getBody(), AgentExecutionMessage.class);
            UUID messageId = original.messageId() == null ? UUID.randomUUID() : original.messageId();
            AgentExecutionMessage replay = new AgentExecutionMessage(
                    messageId, original.taskId(), original.userId(), original.expectedVersion(),
                    original.conversationId(), original.assetId(), original.question(),
                    System.currentTimeMillis(), original.deliveryAttempt() + 1);
            rabbit.convertAndSend(AgentMessagingConfiguration.EXCHANGE, AgentMessagingConfiguration.ROUTING_KEY,
                    objectMapper.writeValueAsString(replay), outbound -> {
                        outbound.getMessageProperties().setMessageId(messageId.toString());
                        outbound.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        outbound.getMessageProperties().setHeader("x-agent-delivery-attempt", replay.deliveryAttempt());
                        return outbound;
                    });
            replayed++;
        }
        return replayed;
    }
}
