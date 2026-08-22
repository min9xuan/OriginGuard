ALTER TABLE review_task
    ADD COLUMN final_conclusion VARCHAR(32),
    ADD COLUMN agent_assessment_included BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN agent_task_id UUID REFERENCES agent_task(id) ON DELETE SET NULL,
    ADD COLUMN agent_assessment_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE review_task
    ADD CONSTRAINT ck_review_task_final_conclusion
        CHECK (final_conclusion IS NULL OR final_conclusion IN (
            'LIKELY_AUTHENTIC', 'LIKELY_SYNTHETIC', 'INCONCLUSIVE'
        ));
