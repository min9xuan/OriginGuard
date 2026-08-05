INSERT INTO sys_permission(code, description)
VALUES ('case:assign', 'Assign investigators and independent reviewers')
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code = 'ADMIN' AND p.code = 'case:assign'
ON CONFLICT DO NOTHING;

CREATE TABLE case_assignment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    case_id UUID NOT NULL REFERENCES investigation_case(id) ON DELETE CASCADE,
    investigator_id UUID NOT NULL REFERENCES sys_user(id),
    reviewer_id UUID NOT NULL REFERENCES sys_user(id),
    assigned_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE investigation_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    case_id UUID NOT NULL REFERENCES investigation_case(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES media_asset(id),
    evidence_type VARCHAR(32) NOT NULL DEFAULT 'HUMAN_OBSERVATION'
        CHECK (evidence_type IN ('HUMAN_OBSERVATION')),
    title VARCHAR(200) NOT NULL,
    observation VARCHAR(4000) NOT NULL,
    conclusion VARCHAR(32) NOT NULL
        CHECK (conclusion IN ('LIKELY_AUTHENTIC', 'LIKELY_SYNTHETIC', 'INCONCLUSIVE')),
    confidence VARCHAR(16) NOT NULL
        CHECK (confidence IN ('LOW', 'MEDIUM', 'HIGH')),
    created_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE review_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    case_id UUID NOT NULL REFERENCES investigation_case(id) ON DELETE CASCADE,
    reviewer_id UUID NOT NULL REFERENCES sys_user(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    decision_reason VARCHAR(2000) NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES sys_user(id),
    decided_by UUID REFERENCES sys_user(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ
);

CREATE INDEX idx_case_assignment_case
    ON case_assignment(tenant_id, case_id, created_at DESC);
CREATE INDEX idx_evidence_case
    ON investigation_evidence(tenant_id, case_id, created_at, id);
CREATE INDEX idx_review_task_reviewer
    ON review_task(tenant_id, reviewer_id, status, created_at DESC);
CREATE UNIQUE INDEX uk_review_task_pending_case
    ON review_task(case_id) WHERE status = 'PENDING';
