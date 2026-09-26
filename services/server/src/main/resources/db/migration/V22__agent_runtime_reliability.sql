CREATE TABLE agent_progress_event (
    id BIGSERIAL PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_task(id) ON DELETE CASCADE,
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_progress_event_task_id
    ON agent_progress_event(task_id, id);

CREATE TABLE agent_task_dispatch (
    task_id UUID PRIMARY KEY REFERENCES agent_task(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES sys_user(id),
    conversation_id UUID,
    asset_id UUID,
    question TEXT,
    message_id UUID NOT NULL,
    delivery_attempt INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempt >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE agent_task
    ADD COLUMN last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0 CHECK (recovery_count >= 0);

CREATE INDEX idx_agent_task_recovery
    ON agent_task(status, last_heartbeat_at, updated_at);
