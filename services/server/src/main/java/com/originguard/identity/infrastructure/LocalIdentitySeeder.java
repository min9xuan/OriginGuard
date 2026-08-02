package com.originguard.identity.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "originguard.bootstrap", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(BootstrapProperties.class)
public class LocalIdentitySeeder implements ApplicationRunner {
    private static final Map<String, List<String>> ROLE_PERMISSIONS = Map.of(
            "INVESTIGATOR",
            List.of(
                    "asset:upload",
                    "asset:read",
                    "case:create",
                    "case:read",
                    "case:update",
                    "case:submit",
                    "agent:run",
                    "agent:cancel",
                    "agent:trace:read",
                    "report:read",
                    "knowledge:read",
                    "audit:case:read"),
            "REVIEWER",
            List.of(
                    "asset:read",
                    "case:read",
                    "agent:run",
                    "agent:trace:read",
                    "review:read",
                    "review:approve",
                    "review:reject",
                    "case:archive",
                    "report:read",
                    "report:edit",
                    "report:finalize",
                    "knowledge:read",
                    "audit:case:read"),
            "ADMIN",
            List.of(
                    "asset:read",
                    "case:read",
                    "agent:trace:read",
                    "report:read",
                    "knowledge:read",
                    "knowledge:upload",
                    "knowledge:publish",
                    "model:read",
                    "model:manage",
                    "tool:read",
                    "tool:manage",
                    "audit:case:read",
                    "audit:system:read",
                    "user:manage",
                    "role:manage"));

    private final JdbcClient jdbcClient;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapProperties properties;

    public LocalIdentitySeeder(
            JdbcClient jdbcClient, PasswordEncoder passwordEncoder, BootstrapProperties properties) {
        this.jdbcClient = jdbcClient;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            return;
        }
        seedTenant();
        ROLE_PERMISSIONS.forEach(this::seedRole);
        seedUser("investigator", "Demo Investigator", "INVESTIGATOR");
        seedUser("reviewer", "Demo Reviewer", "REVIEWER");
        seedUser("admin", "Demo Administrator", "ADMIN");
    }

    private void seedTenant() {
        jdbcClient.sql("""
                        INSERT INTO tenant(code, name)
                        VALUES (:code, :name)
                        ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name
                        """)
                .param("code", properties.tenantCode())
                .param("name", properties.tenantName())
                .update();
    }

    private void seedRole(String roleCode, List<String> permissions) {
        jdbcClient.sql("""
                        INSERT INTO sys_role(code, name, description)
                        VALUES (:code, :name, :description)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", roleCode)
                .param("name", roleCode)
                .param("description", "OriginGuard built-in " + roleCode + " role")
                .update();
        jdbcClient.sql("""
                        DELETE FROM sys_role_permission
                        WHERE role_id = (SELECT id FROM sys_role WHERE code = :roleCode)
                        """)
                .param("roleCode", roleCode)
                .update();
        for (String permission : permissions) {
            jdbcClient.sql("""
                            INSERT INTO sys_role_permission(role_id, permission_id)
                            SELECT r.id, p.id FROM sys_role r, sys_permission p
                            WHERE r.code = :roleCode AND p.code = :permissionCode
                            ON CONFLICT DO NOTHING
                            """)
                    .param("roleCode", roleCode)
                    .param("permissionCode", permission)
                    .update();
        }
    }

    private void seedUser(String username, String displayName, String roleCode) {
        UUID userId = jdbcClient.sql("""
                        SELECT u.id FROM sys_user u
                        JOIN tenant t ON t.id = u.tenant_id
                        WHERE t.code = :tenantCode AND u.username = :username
                        """)
                .param("tenantCode", properties.tenantCode())
                .param("username", username)
                .query(UUID.class)
                .optional()
                .orElseGet(() -> createUser(username, displayName));
        jdbcClient.sql("DELETE FROM sys_user_role WHERE user_id = :userId")
                .param("userId", userId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO sys_user_role(user_id, role_id)
                        SELECT :userId, id FROM sys_role WHERE code = :roleCode
                        ON CONFLICT DO NOTHING
                        """)
                .param("userId", userId)
                .param("roleCode", roleCode)
                .update();
    }

    private UUID createUser(String username, String displayName) {
        UUID userId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO sys_user(id, tenant_id, username, display_name, password_hash)
                        SELECT :userId, id, :username, :displayName, :passwordHash
                        FROM tenant WHERE code = :tenantCode
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("displayName", displayName)
                .param("passwordHash", passwordEncoder.encode(properties.password()))
                .param("tenantCode", properties.tenantCode())
                .update();
        return userId;
    }
}
