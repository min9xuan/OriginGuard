package com.originguard.identity.infrastructure;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {
    private final JdbcClient jdbcClient;

    public RefreshTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void save(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        jdbcClient.sql("""
                        INSERT INTO auth_refresh_token(id, user_id, token_hash, expires_at)
                        VALUES (:id, :userId, :tokenHash, :expiresAt)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    public Optional<StoredRefreshToken> findActive(String tokenHash, Instant now) {
        return jdbcClient.sql("""
                        SELECT id, user_id, expires_at
                        FROM auth_refresh_token
                        WHERE token_hash = :tokenHash
                          AND revoked_at IS NULL
                          AND expires_at > :now
                        """)
                .param("tokenHash", tokenHash)
                .param("now", Timestamp.from(now))
                .query((rs, rowNum) -> new StoredRefreshToken(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    public void revoke(UUID id, Instant revokedAt, UUID replacedBy) {
        jdbcClient.sql("""
                        UPDATE auth_refresh_token
                        SET revoked_at = :revokedAt, replaced_by = :replacedBy
                        WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("replacedBy", replacedBy)
                .param("id", id)
                .update();
    }

    public void revokeByHash(String tokenHash, Instant revokedAt) {
        jdbcClient.sql("""
                        UPDATE auth_refresh_token
                        SET revoked_at = :revokedAt
                        WHERE token_hash = :tokenHash AND revoked_at IS NULL
                        """)
                .param("revokedAt", Timestamp.from(revokedAt))
                .param("tokenHash", tokenHash)
                .update();
    }

    public record StoredRefreshToken(UUID id, UUID userId, Instant expiresAt) {}
}
