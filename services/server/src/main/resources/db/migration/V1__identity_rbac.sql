CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    token_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_tenant_username UNIQUE (tenant_id, username)
);

CREATE TABLE sys_role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_permission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
    user_id UUID NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_role_permission (
    role_id UUID NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES sys_permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE auth_refresh_token (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    replaced_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_tenant ON sys_user(tenant_id);
CREATE INDEX idx_refresh_token_user ON auth_refresh_token(user_id, expires_at);

INSERT INTO sys_permission(code, description) VALUES
    ('asset:upload', 'Upload media assets'),
    ('asset:read', 'Read authorized media assets'),
    ('case:create', 'Create investigation cases'),
    ('case:read', 'Read authorized investigation cases'),
    ('case:update', 'Update assigned investigation cases'),
    ('case:submit', 'Submit cases for human review'),
    ('case:archive', 'Archive confirmed cases'),
    ('agent:run', 'Start investigation agent tasks'),
    ('agent:cancel', 'Cancel owned investigation agent tasks'),
    ('agent:trace:read', 'Read authorized agent traces'),
    ('review:read', 'Read assigned review tasks'),
    ('review:approve', 'Approve assigned review tasks'),
    ('review:reject', 'Reject assigned review tasks'),
    ('report:read', 'Read authorized forensic reports'),
    ('report:edit', 'Edit report drafts during review'),
    ('report:finalize', 'Sign and finalize reviewed reports'),
    ('knowledge:read', 'Read published knowledge'),
    ('knowledge:upload', 'Upload knowledge documents'),
    ('knowledge:publish', 'Publish reviewed knowledge documents'),
    ('model:read', 'Read model registry and model cards'),
    ('model:manage', 'Manage model registry and availability'),
    ('tool:read', 'Read agent tool definitions'),
    ('tool:manage', 'Manage agent tool availability'),
    ('audit:case:read', 'Read authorized case audit events'),
    ('audit:system:read', 'Read system audit and security events'),
    ('user:manage', 'Manage users'),
    ('role:manage', 'Manage roles and permission assignments')
ON CONFLICT (code) DO NOTHING;

