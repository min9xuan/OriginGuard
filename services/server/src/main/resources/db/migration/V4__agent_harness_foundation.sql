CREATE TABLE agent_task (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    case_id UUID NOT NULL REFERENCES investigation_case(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES sys_user(id),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    goal VARCHAR(500) NOT NULL,
    selected_skill_code VARCHAR(100),
    selected_skill_version VARCHAR(32),
    remaining_step_budget INTEGER NOT NULL CHECK (remaining_step_budget >= 0),
    conclusion JSONB,
    failure_code VARCHAR(80),
    failure_message VARCHAR(1000),
    checkpoint_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_step (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    task_id UUID NOT NULL REFERENCES agent_task(id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL CHECK (sequence_number > 0),
    step_type VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    skill_code VARCHAR(100),
    tool_code VARCHAR(100),
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    output JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_step_sequence UNIQUE (task_id, sequence_number)
);

CREATE TABLE agent_observation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    task_id UUID NOT NULL REFERENCES agent_task(id) ON DELETE CASCADE,
    case_id UUID NOT NULL REFERENCES investigation_case(id) ON DELETE CASCADE,
    asset_id UUID REFERENCES media_asset(id),
    evidence_type VARCHAR(40) NOT NULL CHECK (evidence_type IN ('MEDIA_METADATA')),
    summary VARCHAR(1000) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_checkpoint (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    task_id UUID NOT NULL REFERENCES agent_task(id) ON DELETE CASCADE,
    checkpoint_version BIGINT NOT NULL CHECK (checkpoint_version > 0),
    state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_checkpoint_version UNIQUE (task_id, checkpoint_version)
);

CREATE INDEX idx_agent_task_tenant_created
    ON agent_task(tenant_id, created_at DESC);
CREATE INDEX idx_agent_task_case
    ON agent_task(tenant_id, case_id, created_at DESC);
CREATE INDEX idx_agent_step_task
    ON agent_step(tenant_id, task_id, sequence_number);
CREATE INDEX idx_agent_observation_task
    ON agent_observation(tenant_id, task_id, created_at);
CREATE INDEX idx_agent_checkpoint_task
    ON agent_checkpoint(tenant_id, task_id, checkpoint_version DESC);
