INSERT INTO sys_permission(code, description)
VALUES ('result:confirm', 'Confirm or return an Agent-assisted investigation result')
ON CONFLICT (code) DO NOTHING;

INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM sys_role r, sys_permission p
WHERE r.code = 'INVESTIGATOR' AND p.code = 'result:confirm'
ON CONFLICT DO NOTHING;

ALTER TABLE investigation_case DROP CONSTRAINT investigation_case_status_check;
UPDATE investigation_case SET status = 'WAITING_CONFIRMATION' WHERE status = 'WAITING_REVIEW';
UPDATE investigation_case SET status = 'COMPLETED' WHERE status = 'CONFIRMED';
UPDATE investigation_case SET status = 'INVESTIGATING' WHERE status = 'REJECTED';
ALTER TABLE investigation_case ADD CONSTRAINT investigation_case_status_check
    CHECK (status IN (
        'DRAFT', 'READY', 'INVESTIGATING', 'WAITING_CONFIRMATION',
        'COMPLETED', 'FAILED', 'ARCHIVED'
    ));

ALTER TABLE review_evidence_reference RENAME TO result_evidence_reference;
ALTER TABLE result_evidence_reference RENAME COLUMN review_task_id TO decision_id;
ALTER TABLE review_task RENAME TO case_decision;
ALTER TABLE case_decision RENAME COLUMN reviewer_id TO confirmer_id;

DROP INDEX IF EXISTS idx_review_task_reviewer;
DROP INDEX IF EXISTS uk_review_task_pending_case;
ALTER TABLE case_decision DROP CONSTRAINT IF EXISTS review_task_status_check;
ALTER TABLE case_decision DROP CONSTRAINT IF EXISTS ck_review_task_final_conclusion;
UPDATE case_decision SET status = 'CONFIRMED' WHERE status = 'APPROVED';
UPDATE case_decision SET status = 'RETURNED' WHERE status = 'REJECTED';
ALTER TABLE case_decision ADD CONSTRAINT ck_case_decision_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'RETURNED'));
ALTER TABLE case_decision ADD CONSTRAINT ck_case_decision_final_conclusion
    CHECK (final_conclusion IS NULL OR final_conclusion IN (
        'LIKELY_AUTHENTIC', 'LIKELY_SYNTHETIC', 'INCONCLUSIVE'
    ));
CREATE INDEX idx_case_decision_confirmer
    ON case_decision(tenant_id, confirmer_id, status, created_at DESC);
CREATE UNIQUE INDEX uk_case_decision_pending_case
    ON case_decision(case_id) WHERE status = 'PENDING';

ALTER TABLE investigation_case DROP COLUMN assigned_reviewer_id;
ALTER TABLE case_assignment DROP COLUMN reviewer_id;

DELETE FROM sys_user_role
WHERE role_id = (SELECT id FROM sys_role WHERE code = 'REVIEWER');
DELETE FROM sys_role_permission
WHERE role_id = (SELECT id FROM sys_role WHERE code = 'REVIEWER');
DELETE FROM sys_role WHERE code = 'REVIEWER';
UPDATE sys_user SET enabled = FALSE, token_version = token_version + 1
WHERE username = 'reviewer';
