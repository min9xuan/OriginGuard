ALTER TABLE agent_observation
    DROP CONSTRAINT agent_observation_evidence_type_check;

ALTER TABLE agent_observation
    ADD CONSTRAINT agent_observation_evidence_type_check
        CHECK (evidence_type IN (
            'MEDIA_METADATA',
            'BASIC_MEDIA_FORENSICS',
            'FILE_INTEGRITY',
            'IMAGE_METADATA',
            'PERCEPTUAL_SIMILARITY'
        ));

ALTER TABLE agent_observation
    ADD COLUMN sequence_number INTEGER NOT NULL DEFAULT 1
        CHECK (sequence_number > 0);

CREATE UNIQUE INDEX uk_agent_observation_task_sequence
    ON agent_observation(tenant_id, task_id, sequence_number);

UPDATE agent_task
SET remaining_step_budget = 7,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'PENDING'
  AND remaining_step_budget < 7;
