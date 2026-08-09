package com.originguard.media.infrastructure;

import com.originguard.media.application.MediaContentAnalyzer;
import com.originguard.media.domain.MediaObject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MediaObjectRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public MediaObjectRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public MediaObject insert(
            UUID tenantId,
            UUID assetId,
            String objectKey,
            MediaContentAnalyzer.Analysis analysis) {
        jdbcClient.sql("""
                        INSERT INTO media_object(
                            asset_id, tenant_id, object_key, detected_content_type,
                            pixel_width, pixel_height, perceptual_hash, extracted_metadata
                        ) VALUES (
                            :assetId, :tenantId, :objectKey, :detectedContentType,
                            :width, :height, :perceptualHash, CAST(:metadata AS jsonb)
                        )
                        """)
                .param("assetId", assetId)
                .param("tenantId", tenantId)
                .param("objectKey", objectKey)
                .param("detectedContentType", analysis.detectedContentType())
                .param("width", analysis.width())
                .param("height", analysis.height())
                .param("perceptualHash", analysis.perceptualHash())
                .param("metadata", toJson(analysis.extractedMetadata()))
                .update();
        return find(tenantId, assetId).orElseThrow();
    }

    public Optional<MediaObject> find(UUID tenantId, UUID assetId) {
        return jdbcClient.sql("""
                        SELECT asset_id, tenant_id, object_key, detected_content_type,
                               pixel_width, pixel_height, perceptual_hash,
                               extracted_metadata::text AS extracted_metadata, stored_at
                        FROM media_object
                        WHERE tenant_id = :tenantId AND asset_id = :assetId
                        """)
                .param("tenantId", tenantId)
                .param("assetId", assetId)
                .query(this::map)
                .optional();
    }

    private MediaObject map(ResultSet rs, int rowNum) throws SQLException {
        return new MediaObject(
                rs.getObject("asset_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("object_key"),
                rs.getString("detected_content_type"),
                rs.getInt("pixel_width"),
                rs.getInt("pixel_height"),
                rs.getString("perceptual_hash"),
                fromJson(rs.getString("extracted_metadata")),
                rs.getTimestamp("stored_at").toInstant());
    }

    private String toJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize media metadata", exception);
        }
    }

    private Map<String, Object> fromJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to deserialize media metadata", exception);
        }
    }
}
