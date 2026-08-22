CREATE TABLE detection_evaluation_sample (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    asset_id UUID NOT NULL REFERENCES media_asset(id) ON DELETE CASCADE,
    ground_truth VARCHAR(16) NOT NULL CHECK (ground_truth IN ('AUTHENTIC', 'SYNTHETIC')),
    media_category VARCHAR(24) NOT NULL CHECK (media_category IN ('PHOTOGRAPH', 'CARTOON', 'ILLUSTRATION', 'OTHER')),
    generator_name VARCHAR(100) NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_detection_evaluation_sample_asset UNIQUE (tenant_id, asset_id)
);

CREATE TABLE detection_evaluation_run (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    model_code VARCHAR(100) NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    evaluation_threshold DOUBLE PRECISION NOT NULL,
    recommended_threshold DOUBLE PRECISION NOT NULL,
    sample_count INTEGER NOT NULL,
    true_positive INTEGER NOT NULL,
    true_negative INTEGER NOT NULL,
    false_positive INTEGER NOT NULL,
    false_negative INTEGER NOT NULL,
    accuracy DOUBLE PRECISION NOT NULL,
    precision_score DOUBLE PRECISION NOT NULL,
    recall_score DOUBLE PRECISION NOT NULL,
    f1_score DOUBLE PRECISION NOT NULL,
    category_metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID NOT NULL REFERENCES sys_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE detection_evaluation_result (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    run_id UUID NOT NULL REFERENCES detection_evaluation_run(id) ON DELETE CASCADE,
    sample_id UUID REFERENCES detection_evaluation_sample(id) ON DELETE SET NULL,
    asset_id UUID REFERENCES media_asset(id) ON DELETE SET NULL,
    asset_filename VARCHAR(255) NOT NULL,
    ground_truth VARCHAR(16) NOT NULL,
    synthetic_probability DOUBLE PRECISION NOT NULL,
    predicted_label VARCHAR(16) NOT NULL,
    correct BOOLEAN NOT NULL,
    processing_milliseconds BIGINT NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_detection_evaluation_result_sample UNIQUE (run_id, sample_id)
);

CREATE INDEX idx_detection_evaluation_sample_tenant
    ON detection_evaluation_sample(tenant_id, created_at DESC);
CREATE INDEX idx_detection_evaluation_run_tenant
    ON detection_evaluation_run(tenant_id, created_at DESC);
