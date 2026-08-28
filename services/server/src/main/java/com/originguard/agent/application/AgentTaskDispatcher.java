package com.originguard.agent.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "originguard.agent.async.enabled", havingValue = "true")
public class AgentTaskDispatcher {
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AgentTaskDispatcher(RabbitTemplate rabbitTemplate, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public boolean enqueue(UUID taskId, UUID userId, long version, UUID conversationId, UUID assetId, String question) {
        String key = "originguard:agent:dispatch:" + taskId;
        boolean redisLockAcquired = false;
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "queued", Duration.ofMinutes(30));
            if (!Boolean.TRUE.equals(acquired)) return false;
            redisLockAcquired = true;
        } catch (RuntimeException exception) {
            // Fail open: RabbitMQ remains the durable source of work when Redis is unavailable.
        }
        try {
            AgentExecutionMessage message = new AgentExecutionMessage(
                    taskId, userId, version, conversationId, assetId, question, Instant.now().toEpochMilli());
            rabbitTemplate.convertAndSend(
                    AgentMessagingConfiguration.EXCHANGE,
                    AgentMessagingConfiguration.ROUTING_KEY,
                    objectMapper.writeValueAsString(message));
            return true;
        } catch (RuntimeException exception) {
            if (redisLockAcquired) {
                try {
                    redis.delete(key);
                } catch (RuntimeException ignored) {
                    // Preserve the original broker exception.
                }
            }
            throw exception;
        }
    }
}
