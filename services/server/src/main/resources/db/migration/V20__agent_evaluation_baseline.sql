CREATE TABLE agent_evaluation_case (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    required_skill_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    forbidden_skill_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    required_evidence_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    forbidden_evidence_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    max_tool_calls INTEGER NOT NULL CHECK (max_tool_calls BETWEEN 1 AND 100),
    max_replans INTEGER NOT NULL CHECK (max_replans BETWEEN 0 AND 50),
    max_duration_milliseconds BIGINT NOT NULL CHECK (max_duration_milliseconds > 0),
    minimum_score INTEGER NOT NULL CHECK (minimum_score BETWEEN 0 AND 100),
    require_completed BOOLEAN NOT NULL DEFAULT TRUE,
    require_human_review BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_evaluation_run (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    evaluation_case_id UUID NOT NULL REFERENCES agent_evaluation_case(id) ON DELETE CASCADE,
    agent_task_id UUID REFERENCES agent_task(id) ON DELETE SET NULL,
    agent_task_status VARCHAR(32) NOT NULL,
    total_score DOUBLE PRECISION NOT NULL,
    passed BOOLEAN NOT NULL,
    critical_failure BOOLEAN NOT NULL,
    dimension_scores JSONB NOT NULL DEFAULT '{}'::jsonb,
    violations JSONB NOT NULL DEFAULT '[]'::jsonb,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_evaluation_case_tenant
    ON agent_evaluation_case(tenant_id, created_at DESC);
CREATE INDEX idx_agent_evaluation_run_tenant
    ON agent_evaluation_run(tenant_id, created_at DESC);
CREATE INDEX idx_agent_evaluation_run_task
    ON agent_evaluation_run(tenant_id, agent_task_id, created_at DESC);
