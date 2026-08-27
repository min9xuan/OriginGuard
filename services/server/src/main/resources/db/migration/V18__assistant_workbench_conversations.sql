CREATE TABLE assistant_conversation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES sys_user(id),
    title VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE assistant_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    conversation_id UUID NOT NULL REFERENCES assistant_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    message_type VARCHAR(32) NOT NULL
        CHECK (message_type IN ('CHAT', 'AGENT_REQUEST', 'AGENT_RESULT', 'ATTACHMENT_REQUIRED', 'ERROR')),
    content TEXT NOT NULL,
    asset_id UUID REFERENCES media_asset(id) ON DELETE SET NULL,
    agent_task_id UUID REFERENCES agent_task(id) ON DELETE SET NULL,
    grounding JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_assistant_conversation_user
    ON assistant_conversation(tenant_id, user_id, updated_at DESC);
CREATE INDEX idx_assistant_message_conversation
    ON assistant_message(tenant_id, conversation_id, created_at, id);
