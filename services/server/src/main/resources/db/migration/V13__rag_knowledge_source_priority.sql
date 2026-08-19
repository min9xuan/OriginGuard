ALTER TABLE knowledge_document
    ADD COLUMN source_scope VARCHAR(16) NOT NULL DEFAULT 'TENANT'
        CHECK (source_scope IN ('TENANT', 'BUILTIN', 'EXTERNAL')),
    ADD COLUMN source_priority SMALLINT NOT NULL DEFAULT 100
        CHECK (source_priority BETWEEN 0 AND 100),
    ADD COLUMN source_provider VARCHAR(32),
    ADD COLUMN source_identifier VARCHAR(200),
    ADD COLUMN source_url TEXT,
    ADD COLUMN source_venue VARCHAR(200),
    ADD COLUMN source_year INTEGER
        CHECK (source_year IS NULL OR source_year BETWEEN 1900 AND 2100);

CREATE UNIQUE INDEX uk_knowledge_external_source
    ON knowledge_document(tenant_id, source_provider, source_identifier)
    WHERE source_identifier IS NOT NULL;

CREATE INDEX idx_knowledge_document_tenant_scope_priority
    ON knowledge_document(tenant_id, source_scope, source_priority DESC, status);

ALTER TABLE agent_observation
    DROP CONSTRAINT agent_observation_evidence_type_check;

ALTER TABLE agent_observation
    ADD CONSTRAINT agent_observation_evidence_type_check
        CHECK (evidence_type IN (
            'MEDIA_METADATA',
            'BASIC_MEDIA_FORENSICS',
            'FILE_INTEGRITY',
            'IMAGE_METADATA',
            'PERCEPTUAL_SIMILARITY',
            'AIGC_DETECTION',
            'MEDIA_TYPE_CLASSIFICATION',
            'LEGACY_RAG_GUIDANCE'
        ));
