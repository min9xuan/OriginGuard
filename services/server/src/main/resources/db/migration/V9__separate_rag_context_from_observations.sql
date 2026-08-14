CREATE TABLE agent_knowledge_retrieval (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    task_id UUID NOT NULL REFERENCES agent_task(id) ON DELETE CASCADE,
    case_id UUID NOT NULL REFERENCES investigation_case(id) ON DELETE CASCADE,
    skill_code VARCHAR(128) NOT NULL,
    tool_code VARCHAR(128) NOT NULL,
    query TEXT NOT NULL,
    retrieval_mode VARCHAR(64) NOT NULL,
    embedding_provider VARCHAR(64) NOT NULL,
    knowledge_available BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_knowledge_citation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    retrieval_id UUID NOT NULL REFERENCES agent_knowledge_retrieval(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES knowledge_document(id),
    chunk_id UUID NOT NULL REFERENCES knowledge_chunk(id),
    document_title VARCHAR(200) NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    document_version INTEGER NOT NULL CHECK (document_version > 0),
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    quote TEXT NOT NULL,
    semantic_score DOUBLE PRECISION NOT NULL,
    keyword_score DOUBLE PRECISION NOT NULL,
    hybrid_score DOUBLE PRECISION NOT NULL,
    citation_order INTEGER NOT NULL CHECK (citation_order > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_knowledge_citation_order UNIQUE (retrieval_id, citation_order)
);

CREATE INDEX idx_agent_knowledge_retrieval_task
    ON agent_knowledge_retrieval(tenant_id, task_id, created_at);
CREATE INDEX idx_agent_knowledge_citation_retrieval
    ON agent_knowledge_citation(tenant_id, retrieval_id, citation_order);

INSERT INTO agent_knowledge_retrieval(
    id, tenant_id, task_id, case_id, skill_code, tool_code, query,
    retrieval_mode, embedding_provider, knowledge_available, created_at
)
SELECT o.id, o.tenant_id, o.task_id, o.case_id,
       'retrieve_forensic_guidance', 'rag.retrieve_forensic_guidance',
       COALESCE(o.payload->>'query', ''),
       COALESCE(o.payload->>'retrievalMode', 'POSTGRES_FTS_PGVECTOR_HYBRID'),
       COALESCE(o.payload->>'embeddingProvider', 'LOCAL_DETERMINISTIC_HASH_V1'),
       COALESCE((o.payload->>'knowledgeAvailable')::boolean, false),
       o.created_at
FROM agent_observation o
WHERE o.evidence_type = 'RAG_GUIDANCE';

INSERT INTO agent_knowledge_citation(
    id, tenant_id, retrieval_id, document_id, chunk_id, document_title,
    document_type, document_version, chunk_index, quote, semantic_score,
    keyword_score, hybrid_score, citation_order, created_at
)
SELECT gen_random_uuid(), o.tenant_id, o.id,
       (citation.value->>'documentId')::uuid,
       (citation.value->>'chunkId')::uuid,
       citation.value->>'documentTitle',
       citation.value->>'documentType',
       (citation.value->>'documentVersion')::integer,
       (citation.value->>'chunkIndex')::integer,
       citation.value->>'quote',
       COALESCE((citation.value->>'semanticScore')::double precision, 0),
       COALESCE((citation.value->>'keywordScore')::double precision, 0),
       COALESCE((citation.value->>'hybridScore')::double precision, 0),
       citation.ordinality::integer,
       o.created_at
FROM agent_observation o
CROSS JOIN LATERAL jsonb_array_elements(COALESCE(o.payload->'citations', '[]'::jsonb))
    WITH ORDINALITY AS citation(value, ordinality)
WHERE o.evidence_type = 'RAG_GUIDANCE';

DELETE FROM agent_observation o
WHERE o.evidence_type = 'RAG_GUIDANCE'
  AND NOT EXISTS (
      SELECT 1 FROM investigation_evidence e WHERE e.source_observation_id = o.id
  );

UPDATE agent_observation o
SET evidence_type = 'LEGACY_RAG_GUIDANCE'
WHERE o.evidence_type = 'RAG_GUIDANCE';

ALTER TABLE agent_observation DROP CONSTRAINT agent_observation_evidence_type_check;
ALTER TABLE agent_observation
    ADD CONSTRAINT agent_observation_evidence_type_check
        CHECK (evidence_type IN (
            'MEDIA_METADATA',
            'BASIC_MEDIA_FORENSICS',
            'FILE_INTEGRITY',
            'IMAGE_METADATA',
            'PERCEPTUAL_SIMILARITY',
            'LEGACY_RAG_GUIDANCE'
        ));
