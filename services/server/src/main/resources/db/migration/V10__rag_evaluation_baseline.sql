CREATE TABLE rag_evaluation_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    name VARCHAR(200) NOT NULL,
    query TEXT NOT NULL,
    expected_document_id UUID NOT NULL REFERENCES knowledge_document(id),
    expected_chunk_id UUID REFERENCES knowledge_chunk(id),
    created_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rag_evaluation_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    top_k INTEGER NOT NULL CHECK (top_k BETWEEN 1 AND 20),
    embedding_provider VARCHAR(64) NOT NULL,
    case_count INTEGER NOT NULL CHECK (case_count > 0),
    recall_at_k DOUBLE PRECISION NOT NULL,
    mrr DOUBLE PRECISION NOT NULL,
    tenant_isolation_passed BOOLEAN NOT NULL,
    draft_exclusion_passed BOOLEAN NOT NULL,
    citation_integrity_passed BOOLEAN NOT NULL,
    created_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rag_evaluation_result (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    run_id UUID NOT NULL REFERENCES rag_evaluation_run(id) ON DELETE CASCADE,
    evaluation_case_id UUID NOT NULL REFERENCES rag_evaluation_case(id),
    first_relevant_rank INTEGER,
    recalled BOOLEAN NOT NULL,
    reciprocal_rank DOUBLE PRECISION NOT NULL,
    returned_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rag_evaluation_run_case UNIQUE (run_id, evaluation_case_id)
);

CREATE INDEX idx_rag_evaluation_case_tenant
    ON rag_evaluation_case(tenant_id, created_at, id);
CREATE INDEX idx_rag_evaluation_run_tenant
    ON rag_evaluation_run(tenant_id, created_at DESC, id);
