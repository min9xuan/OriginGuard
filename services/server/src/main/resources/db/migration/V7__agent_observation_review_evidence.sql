ALTER TABLE investigation_evidence
    DROP CONSTRAINT investigation_evidence_evidence_type_check;

ALTER TABLE investigation_evidence
    ADD CONSTRAINT investigation_evidence_evidence_type_check
        CHECK (evidence_type IN ('HUMAN_OBSERVATION', 'AGENT_OBSERVATION'));

ALTER TABLE investigation_evidence
    ADD COLUMN source_observation_id UUID REFERENCES agent_observation(id);

CREATE UNIQUE INDEX uk_evidence_source_observation
    ON investigation_evidence(source_observation_id)
    WHERE source_observation_id IS NOT NULL;

CREATE TABLE review_evidence_reference (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    review_task_id UUID NOT NULL REFERENCES review_task(id) ON DELETE CASCADE,
    evidence_id UUID NOT NULL REFERENCES investigation_evidence(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (review_task_id, evidence_id)
);

CREATE INDEX idx_review_evidence_reference_tenant
    ON review_evidence_reference(tenant_id, review_task_id);
