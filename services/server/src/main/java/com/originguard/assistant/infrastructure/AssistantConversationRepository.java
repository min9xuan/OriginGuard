package com.originguard.assistant.infrastructure;

import com.originguard.assistant.domain.AssistantConversation;
import com.originguard.assistant.domain.AssistantMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AssistantConversationRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AssistantConversationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public AssistantConversation insertConversation(UUID tenantId, UUID userId, String title) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO assistant_conversation(id, tenant_id, user_id, title)
                        VALUES (:id, :tenantId, :userId, :title)
                        """)
                .param("id", id).param("tenantId", tenantId).param("userId", userId).param("title", title)
                .update();
        return findConversation(tenantId, userId, id).orElseThrow();
    }

    public Optional<AssistantConversation> findConversation(UUID tenantId, UUID userId, UUID id) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, user_id, title, created_at, updated_at
                        FROM assistant_conversation
                        WHERE tenant_id = :tenantId AND user_id = :userId AND id = :id
                        """)
                .param("tenantId", tenantId).param("userId", userId).param("id", id)
                .query(this::mapConversation).optional();
    }

    public List<AssistantConversation> findConversations(UUID tenantId, UUID userId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, user_id, title, created_at, updated_at
                        FROM assistant_conversation
                        WHERE tenant_id = :tenantId AND user_id = :userId
                        ORDER BY updated_at DESC, id
                        LIMIT 100
                        """)
                .param("tenantId", tenantId).param("userId", userId)
                .query(this::mapConversation).list();
    }

    public void updateTitle(UUID tenantId, UUID userId, UUID id, String title) {
        jdbcClient.sql("""
                        UPDATE assistant_conversation
                        SET title = :title, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND user_id = :userId AND id = :id
                        """)
                .param("title", title).param("tenantId", tenantId).param("userId", userId).param("id", id)
                .update();
    }

    public boolean deleteConversation(UUID tenantId, UUID userId, UUID id) {
        return jdbcClient.sql("""
                        DELETE FROM assistant_conversation
                        WHERE tenant_id = :tenantId AND user_id = :userId AND id = :id
                        """)
                .param("tenantId", tenantId).param("userId", userId).param("id", id)
                .update() == 1;
    }

    public AssistantMessage insertMessage(
            UUID tenantId,
            UUID conversationId,
            String role,
            String messageType,
            String content,
            UUID assetId,
            UUID agentTaskId,
            Map<String, ?> grounding) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO assistant_message(
                            id, tenant_id, conversation_id, role, message_type,
                            content, asset_id, agent_task_id, grounding
                        ) VALUES (
                            :id, :tenantId, :conversationId, :role, :messageType,
                            :content, :assetId, :agentTaskId, CAST(:grounding AS jsonb)
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("conversationId", conversationId)
                .param("role", role).param("messageType", messageType).param("content", content)
                .param("assetId", assetId).param("agentTaskId", agentTaskId)
                .param("grounding", toJson(grounding)).update();
        jdbcClient.sql("""
                        UPDATE assistant_conversation SET updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :conversationId
                        """)
                .param("tenantId", tenantId).param("conversationId", conversationId).update();
        return findMessage(tenantId, id).orElseThrow();
    }

    public List<AssistantMessage> findMessages(UUID tenantId, UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, conversation_id, role, message_type, content,
                               asset_id, agent_task_id, grounding, created_at
                        FROM assistant_message
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId).param("conversationId", conversationId)
                .query(this::mapMessage).list();
    }

    public boolean hasTerminalAgentMessage(UUID tenantId, UUID conversationId, UUID taskId) {
        Integer count = jdbcClient.sql("""
                        SELECT count(*) FROM assistant_message
                        WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                          AND agent_task_id = :taskId AND message_type IN ('AGENT_RESULT', 'ERROR')
                        """)
                .param("tenantId", tenantId).param("conversationId", conversationId).param("taskId", taskId)
                .query(Integer.class).single();
        return count != null && count > 0;
    }

    private Optional<AssistantMessage> findMessage(UUID tenantId, UUID id) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, conversation_id, role, message_type, content,
                               asset_id, agent_task_id, grounding, created_at
                        FROM assistant_message WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", id).query(this::mapMessage).optional();
    }

    private AssistantConversation mapConversation(ResultSet rs, int row) throws SQLException {
        return new AssistantConversation(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private AssistantMessage mapMessage(ResultSet rs, int row) throws SQLException {
        return new AssistantMessage(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("role"),
                rs.getString("message_type"), rs.getString("content"),
                rs.getObject("asset_id", UUID.class), rs.getObject("agent_task_id", UUID.class),
                fromJson(rs.getString("grounding")), rs.getTimestamp("created_at").toInstant());
    }

    private String toJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to serialize assistant grounding", exception);
        }
    }

    private Map<String, Object> fromJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Unable to deserialize assistant grounding", exception);
        }
    }
}
