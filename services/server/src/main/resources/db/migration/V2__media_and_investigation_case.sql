CREATE TABLE media_asset (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_status VARCHAR(32) NOT NULL DEFAULT 'REGISTERED'
        CHECK (storage_status IN ('REGISTERED', 'STORED', 'QUARANTINED')),
    created_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_media_asset_tenant_sha256 UNIQUE (tenant_id, sha256)
);

CREATE TABLE investigation_case (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    case_number VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL'
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'CRITICAL')),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN (
            'DRAFT', 'READY', 'INVESTIGATING', 'WAITING_REVIEW',
            'CONFIRMED', 'REJECTED', 'FAILED', 'ARCHIVED'
        )),
    created_by UUID NOT NULL REFERENCES sys_user(id),
    assigned_investigator_id UUID REFERENCES sys_user(id),
    assigned_reviewer_id UUID REFERENCES sys_user(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_case_tenant_number UNIQUE (tenant_id, case_number)
);

CREATE TABLE case_asset (
    case_id UUID NOT NULL REFERENCES investigation_case(id) ON DELETE CASCADE,
    asset_id UUID NOT NULL REFERENCES media_asset(id),
    added_by UUID NOT NULL REFERENCES sys_user(id),
    added_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (case_id, asset_id)
);

CREATE TABLE audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    actor_user_id UUID REFERENCES sys_user(id),
    action VARCHAR(80) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id UUID NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_media_asset_tenant_created
    ON media_asset(tenant_id, created_at DESC);
CREATE INDEX idx_case_tenant_status_created
    ON investigation_case(tenant_id, status, created_at DESC);
CREATE INDEX idx_case_created_by
    ON investigation_case(tenant_id, created_by, created_at DESC);
CREATE INDEX idx_case_asset_asset
    ON case_asset(asset_id, case_id);
CREATE INDEX idx_audit_resource
    ON audit_log(tenant_id, resource_type, resource_id, created_at);
