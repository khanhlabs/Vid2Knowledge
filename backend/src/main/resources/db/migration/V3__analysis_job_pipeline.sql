CREATE TABLE sources (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    type VARCHAR(32) NOT NULL,
    canonical_uri TEXT NOT NULL,
    external_id VARCHAR(160),
    content_hash VARCHAR(128),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_sources_type CHECK (type IN ('YOUTUBE', 'UPLOAD', 'TEXT')),
    CONSTRAINT ux_sources_canonical UNIQUE (organization_id, canonical_uri)
);

CREATE INDEX ix_sources_org_created ON sources(organization_id, created_at DESC, id DESC);

CREATE TABLE rights_attestations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    source_id UUID NOT NULL REFERENCES sources(id),
    attested_by UUID NOT NULL REFERENCES users(id),
    basis VARCHAR(64) NOT NULL,
    terms_version VARCHAR(32) NOT NULL,
    attested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_rights_basis CHECK (basis IN ('OWNER', 'LICENSED', 'PERMISSION', 'PUBLIC_DOMAIN')),
    CONSTRAINT ux_rights_attestation_version UNIQUE (source_id, terms_version, attested_by)
);

CREATE INDEX ix_rights_active ON rights_attestations(organization_id, source_id)
    WHERE revoked_at IS NULL;

CREATE TABLE analysis_jobs (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    source_id UUID NOT NULL REFERENCES sources(id),
    usage_reservation_id UUID NOT NULL REFERENCES usage_reservations(id),
    state VARCHAR(32) NOT NULL,
    output_profile_json JSONB NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(120) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    error_detail VARCHAR(1000),
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    queued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_analysis_jobs_state CHECK (state IN (
        'QUEUED', 'PROCESSING', 'VALIDATING', 'RETRY_SCHEDULED', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_analysis_jobs_attempt CHECK (attempt >= 0),
    CONSTRAINT ux_analysis_jobs_idempotency UNIQUE (organization_id, idempotency_key)
);

CREATE INDEX ix_analysis_jobs_org_time ON analysis_jobs(organization_id, queued_at DESC, id DESC);
CREATE INDEX ix_analysis_jobs_claim ON analysis_jobs(state, queued_at)
    WHERE state IN ('QUEUED', 'RETRY_SCHEDULED');

CREATE TABLE generation_runs (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    job_id UUID NOT NULL REFERENCES analysis_jobs(id),
    attempt INTEGER NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(120) NOT NULL,
    model_version VARCHAR(160) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    thought_tokens BIGINT NOT NULL,
    latency_ms BIGINT NOT NULL,
    retry_count INTEGER NOT NULL,
    actual_cost_microusd BIGINT NOT NULL,
    shadow_cost_microusd BIGINT NOT NULL,
    output_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_generation_job_attempt UNIQUE (job_id, attempt),
    CONSTRAINT ck_generation_usage_non_negative CHECK (
        attempt > 0 AND input_tokens >= 0 AND output_tokens >= 0 AND thought_tokens >= 0
        AND latency_ms >= 0 AND retry_count >= 0
        AND actual_cost_microusd >= 0 AND shadow_cost_microusd >= 0
    )
);

CREATE INDEX ix_generation_runs_org_time ON generation_runs(organization_id, created_at DESC, id DESC);

CREATE TABLE learning_packages (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    source_id UUID NOT NULL REFERENCES sources(id),
    current_revision_id UUID,
    publication_state VARCHAR(24) NOT NULL DEFAULT 'GENERATED',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_packages_state CHECK (publication_state IN (
        'DRAFT', 'GENERATED', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'PUBLISHED', 'ARCHIVED'
    )),
    CONSTRAINT ux_packages_source UNIQUE (organization_id, source_id)
);

CREATE TABLE package_revisions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    package_id UUID NOT NULL REFERENCES learning_packages(id),
    revision_no INTEGER NOT NULL,
    based_on_generation_id UUID REFERENCES generation_runs(id),
    content_json JSONB NOT NULL,
    edited_by UUID REFERENCES users(id),
    verification_state VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_package_revision_no CHECK (revision_no > 0),
    CONSTRAINT ck_package_verification CHECK (verification_state IN ('UNVERIFIED', 'AUTO_VALIDATED', 'HUMAN_VERIFIED', 'REJECTED')),
    CONSTRAINT ux_package_revision UNIQUE (package_id, revision_no),
    CONSTRAINT ux_package_revision_id_scope UNIQUE (package_id, id)
);

ALTER TABLE learning_packages
    ADD CONSTRAINT fk_packages_current_revision
    FOREIGN KEY (id, current_revision_id)
    REFERENCES package_revisions(package_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX ix_package_revisions_org_time ON package_revisions(organization_id, created_at DESC, id DESC);
