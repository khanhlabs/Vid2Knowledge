ALTER TABLE source_uploads
    ADD COLUMN source_id UUID,
    ADD COLUMN target_source_id UUID,
    ADD COLUMN rights_basis VARCHAR(64),
    ADD COLUMN terms_version VARCHAR(32),
    ADD COLUMN language_hint VARCHAR(16),
    ADD COLUMN processing_attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN lease_owner VARCHAR(160),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT fk_source_upload_source_scope
        FOREIGN KEY (organization_id, source_id) REFERENCES sources(organization_id, id),
    ADD CONSTRAINT ck_source_upload_rights_basis
        CHECK (rights_basis IS NULL OR rights_basis IN ('OWNER', 'LICENSED', 'PERMISSION', 'PUBLIC_DOMAIN')),
    ADD CONSTRAINT ck_source_upload_processing_attempt CHECK (processing_attempt >= 0);

CREATE UNIQUE INDEX ux_source_upload_target_source
    ON source_uploads(target_source_id) WHERE target_source_id IS NOT NULL;

CREATE INDEX ix_source_uploads_processing
    ON source_uploads(state, lease_expires_at)
    WHERE state = 'PROCESSING';
