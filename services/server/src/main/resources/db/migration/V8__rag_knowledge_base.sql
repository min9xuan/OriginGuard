CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    title VARCHAR(200) NOT NULL,
    document_type VARCHAR(32) NOT NULL
        CHECK (document_type IN ('FORENSIC_GUIDE', 'POLICY', 'MODEL_CARD', 'OTHER')),
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED')),
    published_version INTEGER NOT NULL DEFAULT 0 CHECK (published_version >= 0),
    created_by UUID NOT NULL REFERENCES sys_user(id),
    updated_by UUID NOT NULL REFERENCES sys_user(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ
);

CREATE TABLE knowledge_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    document_id UUID NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    document_version INTEGER NOT NULL CHECK (document_version > 0),
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL,
    character_count INTEGER NOT NULL CHECK (character_count > 0),
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', content)) STORED,
    embedding VECTOR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_chunk_version UNIQUE (document_id, document_version, chunk_index)
);

CREATE INDEX idx_knowledge_document_tenant_status
    ON knowledge_document(tenant_id, status, updated_at DESC);
CREATE INDEX idx_knowledge_chunk_tenant_document
    ON knowledge_chunk(tenant_id, document_id, document_version, chunk_index);
CREATE INDEX idx_knowledge_chunk_fts ON knowledge_chunk USING GIN(search_vector);
CREATE INDEX idx_knowledge_chunk_embedding
    ON knowledge_chunk USING HNSW (embedding vector_cosine_ops);

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
            'RAG_GUIDANCE'
        ));

UPDATE agent_task
SET remaining_step_budget = 9,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING'
  AND remaining_step_budget < 9;
