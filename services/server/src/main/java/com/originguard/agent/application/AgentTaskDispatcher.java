package com.originguard.agent.application;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "originguard.agent.async.enabled", havingValue = "true")
public class AgentTaskDispatcher {
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;

    public AgentTaskDispatcher(RabbitTemplate rabbitTemplate, StringRedisTemplate redis, ObjectMapper objectMapper,
            JdbcClient jdbcClient) {
        this.rabbitTemplate = rabbitTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
    }

    public boolean enqueue(UUID taskId, UUID userId, long version, UUID conversationId, UUID assetId, String question) {
        return enqueue(taskId, userId, version, conversationId, assetId, question, UUID.randomUUID(), 0, false);
    }

    public boolean recover(UUID taskId, UUID userId, long version) {
        DispatchContext context = findDispatch(taskId);
        UUID messageId = context == null ? UUID.randomUUID() : context.messageId();
        int attempt = context == null ? 1 : context.deliveryAttempt() + 1;
        return enqueue(taskId, userId, version,
                context == null ? null : context.conversationId(),
                context == null ? null : context.assetId(),
                context == null ? null : context.question(), messageId, attempt, true);
    }

    private boolean enqueue(UUID taskId, UUID userId, long version, UUID conversationId, UUID assetId,
            String question, UUID messageId, int deliveryAttempt, boolean force) {
        String key = "originguard:agent:dispatch:" + taskId;
        boolean redisLockAcquired = false;
        try {
            if (force) redis.delete(key);
            Boolean acquired = redis.opsForValue().setIfAbsent(key, "queued", Duration.ofMinutes(30));
            if (!Boolean.TRUE.equals(acquired)) return false;
            redisLockAcquired = true;
        } catch (RuntimeException exception) {
            // Fail open: RabbitMQ remains the durable source of work when Redis is unavailable.
        }
        try {
            saveDispatch(taskId, userId, conversationId, assetId, question, messageId, deliveryAttempt);
            AgentExecutionMessage message = new AgentExecutionMessage(
                    messageId, taskId, userId, version, conversationId, assetId, question,
                    Instant.now().toEpochMilli(), deliveryAttempt);
            rabbitTemplate.convertAndSend(
                    AgentMessagingConfiguration.EXCHANGE,
                    AgentMessagingConfiguration.ROUTING_KEY,
                    objectMapper.writeValueAsString(message), outbound -> {
                        outbound.getMessageProperties().setMessageId(messageId.toString());
                        outbound.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        outbound.getMessageProperties().setHeader("x-agent-delivery-attempt", deliveryAttempt);
                        return outbound;
                    });
            jdbcClient.sql("""
                            UPDATE agent_task SET last_heartbeat_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                            WHERE id = :taskId AND status = 'PENDING'
                            """).param("taskId", taskId).update();
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

    private void saveDispatch(UUID taskId, UUID userId, UUID conversationId, UUID assetId, String question,
            UUID messageId, int deliveryAttempt) {
        jdbcClient.sql("""
                        INSERT INTO agent_task_dispatch(
                            task_id, user_id, conversation_id, asset_id, question, message_id, delivery_attempt)
                        VALUES (:taskId, :userId, :conversationId, :assetId, :question, :messageId, :deliveryAttempt)
                        ON CONFLICT (task_id) DO UPDATE SET
                            user_id = EXCLUDED.user_id, conversation_id = EXCLUDED.conversation_id,
                            asset_id = EXCLUDED.asset_id, question = EXCLUDED.question,
                            message_id = EXCLUDED.message_id, delivery_attempt = EXCLUDED.delivery_attempt,
                            updated_at = CURRENT_TIMESTAMP
                        """)
                .param("taskId", taskId).param("userId", userId).param("conversationId", conversationId)
                .param("assetId", assetId).param("question", question).param("messageId", messageId)
                .param("deliveryAttempt", deliveryAttempt).update();
    }

    private DispatchContext findDispatch(UUID taskId) {
        return jdbcClient.sql("""
                        SELECT conversation_id, asset_id, question, message_id, delivery_attempt
                        FROM agent_task_dispatch WHERE task_id = :taskId
                        """).param("taskId", taskId).query((rs, row) -> new DispatchContext(
                                rs.getObject("conversation_id", UUID.class), rs.getObject("asset_id", UUID.class),
                                rs.getString("question"), rs.getObject("message_id", UUID.class),
                                rs.getInt("delivery_attempt"))).optional().orElse(null);
    }

    private record DispatchContext(UUID conversationId, UUID assetId, String question,
            UUID messageId, int deliveryAttempt) {}
}
