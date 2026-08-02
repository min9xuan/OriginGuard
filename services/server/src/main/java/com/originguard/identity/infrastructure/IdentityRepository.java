package com.originguard.identity.infrastructure;

import com.originguard.identity.domain.UserAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityRepository {
    private final JdbcClient jdbcClient;

    public IdentityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UserAccount> findByTenantAndUsername(String tenantCode, String username) {
        return jdbcClient.sql("""
                        SELECT u.id, u.tenant_id, t.code AS tenant_code, u.username, u.display_name,
                               u.password_hash, u.enabled, u.token_version
                        FROM sys_user u
                        JOIN tenant t ON t.id = u.tenant_id
                        WHERE lower(t.code) = lower(:tenantCode)
                          AND lower(u.username) = lower(:username)
                          AND t.enabled = TRUE
                        """)
                .param("tenantCode", tenantCode)
                .param("username", username)
                .query(this::mapUser)
                .optional()
                .map(this::withAuthorities);
    }

    public Optional<UserAccount> findById(UUID userId) {
        return jdbcClient.sql("""
                        SELECT u.id, u.tenant_id, t.code AS tenant_code, u.username, u.display_name,
                               u.password_hash, u.enabled, u.token_version
                        FROM sys_user u
                        JOIN tenant t ON t.id = u.tenant_id
                        WHERE u.id = :userId AND t.enabled = TRUE
                        """)
                .param("userId", userId)
                .query(this::mapUser)
                .optional()
                .map(this::withAuthorities);
    }

    public IdentitySummary countForTenant(UUID tenantId) {
        Integer users = jdbcClient.sql("SELECT count(*) FROM sys_user WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
        Integer enabledUsers = jdbcClient.sql(
                        "SELECT count(*) FROM sys_user WHERE tenant_id = :tenantId AND enabled = TRUE")
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
        return new IdentitySummary(users, enabledUsers);
    }

    private UserAccount withAuthorities(UserAccount user) {
        Set<String> roles = new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT r.code
                        FROM sys_role r
                        JOIN sys_user_role ur ON ur.role_id = r.id
                        WHERE ur.user_id = :userId
                        ORDER BY r.code
                        """)
                .param("userId", user.id())
                .query(String.class)
                .list());
        Set<String> permissions = new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT DISTINCT p.code
                        FROM sys_permission p
                        JOIN sys_role_permission rp ON rp.permission_id = p.id
                        JOIN sys_user_role ur ON ur.role_id = rp.role_id
                        WHERE ur.user_id = :userId
                        ORDER BY p.code
                        """)
                .param("userId", user.id())
                .query(String.class)
                .list());
        return new UserAccount(
                user.id(),
                user.tenantId(),
                user.tenantCode(),
                user.username(),
                user.displayName(),
                user.passwordHash(),
                user.enabled(),
                user.tokenVersion(),
                roles,
                permissions);
    }

    private UserAccount mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_code"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getBoolean("enabled"),
                rs.getInt("token_version"),
                Set.of(),
                Set.of());
    }

    public record IdentitySummary(int users, int enabledUsers) {}
}

