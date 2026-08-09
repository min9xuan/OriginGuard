CREATE TABLE media_object (
    asset_id UUID PRIMARY KEY REFERENCES media_asset(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    object_key VARCHAR(300) NOT NULL,
    detected_content_type VARCHAR(127) NOT NULL,
    pixel_width INTEGER NOT NULL CHECK (pixel_width > 0),
    pixel_height INTEGER NOT NULL CHECK (pixel_height > 0),
    perceptual_hash CHAR(16) NOT NULL CHECK (perceptual_hash ~ '^[0-9a-f]{16}$'),
    extracted_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    stored_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_media_object_key UNIQUE (object_key),
    CONSTRAINT uk_media_object_tenant_asset UNIQUE (tenant_id, asset_id)
);

CREATE INDEX idx_media_object_tenant_stored
    ON media_object(tenant_id, stored_at DESC);

ALTER TABLE agent_observation
    DROP CONSTRAINT agent_observation_evidence_type_check;
ALTER TABLE agent_observation
    ADD CONSTRAINT agent_observation_evidence_type_check
        CHECK (evidence_type IN ('MEDIA_METADATA', 'BASIC_MEDIA_FORENSICS'));
