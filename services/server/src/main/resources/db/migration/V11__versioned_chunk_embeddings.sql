CREATE TABLE knowledge_chunk_embedding (
    chunk_id UUID NOT NULL REFERENCES knowledge_chunk(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    provider_code VARCHAR(64) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0 AND dimensions <= 4096),
    embedding VECTOR NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (chunk_id, provider_code)
);

INSERT INTO knowledge_chunk_embedding(chunk_id, tenant_id, provider_code, dimensions, embedding)
SELECT id, tenant_id, 'LOCAL_DETERMINISTIC_HASH_V1', 64, embedding
FROM knowledge_chunk;

DROP INDEX idx_knowledge_chunk_embedding;
ALTER TABLE knowledge_chunk DROP COLUMN embedding;

CREATE INDEX idx_knowledge_chunk_embedding_tenant_provider
    ON knowledge_chunk_embedding(tenant_id, provider_code, chunk_id);
CREATE INDEX idx_knowledge_chunk_embedding_hash_hnsw
    ON knowledge_chunk_embedding USING HNSW ((embedding::vector(64)) vector_cosine_ops)
    WHERE provider_code = 'LOCAL_DETERMINISTIC_HASH_V1';
CREATE INDEX idx_knowledge_chunk_embedding_bge_small_zh_hnsw
    ON knowledge_chunk_embedding USING HNSW ((embedding::vector(512)) vector_cosine_ops)
    WHERE provider_code = 'LOCAL_BGE_SMALL_ZH_V1_5';
