package com.originguard.media.infrastructure;

import com.originguard.media.domain.MediaAsset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MediaAssetRepository {
    private final JdbcClient jdbcClient;

    public MediaAssetRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public MediaAsset insert(
            UUID id,
            UUID tenantId,
            String originalFilename,
            String contentType,
            long byteSize,
            String sha256,
            UUID createdBy) {
        jdbcClient.sql("""
                        INSERT INTO media_asset(
                            id, tenant_id, original_filename, content_type, byte_size, sha256, created_by
                        ) VALUES (
                            :id, :tenantId, :originalFilename, :contentType, :byteSize, :sha256, :createdBy
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("originalFilename", originalFilename)
                .param("contentType", contentType)
                .param("byteSize", byteSize)
                .param("sha256", sha256)
                .param("createdBy", createdBy)
                .update();
        return findById(tenantId, id).orElseThrow();
    }

    public Optional<MediaAsset> findById(UUID tenantId, UUID id) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, original_filename, content_type, byte_size, sha256,
                               storage_status, created_by, created_at
                        FROM media_asset
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<MediaAsset> findBySha256(UUID tenantId, String sha256) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, original_filename, content_type, byte_size, sha256,
                               storage_status, created_by, created_at
                        FROM media_asset
                        WHERE tenant_id = :tenantId AND sha256 = :sha256
                        """)
                .param("tenantId", tenantId)
                .param("sha256", sha256)
                .query(this::map)
                .optional();
    }

    public List<MediaAsset> findAll(UUID tenantId) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, original_filename, content_type, byte_size, sha256,
                               storage_status, created_by, created_at
                        FROM media_asset
                        WHERE tenant_id = :tenantId
                        ORDER BY created_at DESC, id
                        """)
                .param("tenantId", tenantId)
                .query(this::map)
                .list();
    }

    private MediaAsset map(ResultSet rs, int rowNum) throws SQLException {
        return new MediaAsset(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getLong("byte_size"),
                rs.getString("sha256"),
                rs.getString("storage_status"),
                rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
