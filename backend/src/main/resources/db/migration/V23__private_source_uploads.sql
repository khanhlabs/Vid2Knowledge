CREATE TABLE source_uploads (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    object_key VARCHAR(500) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    declared_content_type VARCHAR(80) NOT NULL,
    declared_size_bytes BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    object_etag VARCHAR(160),
    failure_reason VARCHAR(500),
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_source_upload_size CHECK (declared_size_bytes > 0),
    CONSTRAINT ck_source_upload_state CHECK (state IN ('REQUESTED', 'STORAGE_VERIFIED', 'PROCESSING', 'READY', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ux_source_upload_scope UNIQUE (organization_id, id)
);

CREATE INDEX ix_source_uploads_org_created ON source_uploads(organization_id, created_at DESC);
CREATE INDEX ix_source_uploads_pending ON source_uploads(expires_at) WHERE state = 'REQUESTED';
