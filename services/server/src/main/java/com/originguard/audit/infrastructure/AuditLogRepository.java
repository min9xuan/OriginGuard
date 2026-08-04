package com.originguard.audit.infrastructure;

import com.originguard.audit.domain.AuditEntry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AuditLogRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AuditLogRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void append(
            UUID tenantId,
            UUID actorUserId,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, ?> details) {
        jdbcClient.sql("""
                        INSERT INTO audit_log(
                            id, tenant_id, actor_user_id, action, resource_type, resource_id, details
                        ) VALUES (
                            :id, :tenantId, :actorUserId, :action, :resourceType, :resourceId,
                            CAST(:details AS jsonb)
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("actorUserId", actorUserId)
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("details", toJson(details))
                .update();
    }

    public List<AuditEntry> findForResource(UUID tenantId, String resourceType, UUID resourceId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, actor_user_id, action, resource_type, resource_id,
                               details::text AS details, created_at
                        FROM audit_log
                        WHERE tenant_id = :tenantId
                          AND resource_type = :resourceType
                          AND resource_id = :resourceId
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .query(this::map)
                .list();
    }

    private AuditEntry map(ResultSet rs, int rowNum) throws SQLException {
        try {
            Map<String, Object> details = objectMapper.readValue(
                    rs.getString("details"), new TypeReference<>() {});
            return new AuditEntry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("actor_user_id", UUID.class),
                    rs.getString("action"),
                    rs.getString("resource_type"),
                    rs.getObject("resource_id", UUID.class),
                    details,
                    rs.getTimestamp("created_at").toInstant());
        } catch (JacksonException exception) {
            throw new SQLException("Invalid audit JSON", exception);
        }
    }

    private String toJson(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Audit details are not serializable", exception);
        }
    }
}
