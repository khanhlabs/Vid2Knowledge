INSERT INTO pricing_plans(
    id, code, version, name, billing_interval, amount_vnd,
    processed_video_seconds, instructor_seats, active_learners, qa_queries,
    effective_from
) VALUES
    ('00000000-0000-7000-8000-000000000401', 'BUSINESS_MONTHLY', 1, 'Business', 'MONTH',
     7990000, 300000, 50, 5000, 15000, CURRENT_TIMESTAMP),
    ('00000000-0000-7000-8000-000000000402', 'BUSINESS_ANNUAL', 1, 'Business Annual', 'YEAR',
     79900000, 3600000, 50, 5000, 180000, CURRENT_TIMESTAMP);

CREATE TABLE integration_api_keys (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(120) NOT NULL,
    token_prefix VARCHAR(32) NOT NULL UNIQUE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    scopes TEXT[] NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_integration_api_key_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_integration_api_key_scopes CHECK (cardinality(scopes) > 0),
    CONSTRAINT ck_integration_api_key_expiry CHECK (expires_at > created_at),
    CONSTRAINT ux_integration_api_key_scope UNIQUE (organization_id, id)
);

CREATE INDEX ix_integration_api_keys_active
    ON integration_api_keys(token_hash) WHERE revoked_at IS NULL;
CREATE INDEX ix_integration_api_keys_org
    ON integration_api_keys(organization_id, created_at DESC);

CREATE TABLE webhook_endpoints (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(120) NOT NULL,
    url TEXT NOT NULL,
    event_types TEXT[] NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    current_secret_version INTEGER NOT NULL DEFAULT 1,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_webhook_endpoint_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_webhook_endpoint_url CHECK (url LIKE 'https://%'),
    CONSTRAINT ck_webhook_endpoint_events CHECK (cardinality(event_types) > 0),
    CONSTRAINT ck_webhook_endpoint_state CHECK (state IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_webhook_secret_version CHECK (current_secret_version > 0),
    CONSTRAINT ux_webhook_endpoint_scope UNIQUE (organization_id, id)
);

CREATE INDEX ix_webhook_endpoints_active
    ON webhook_endpoints(organization_id, state) WHERE state = 'ACTIVE';

CREATE TABLE webhook_endpoint_secrets (
    endpoint_id UUID NOT NULL REFERENCES webhook_endpoints(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    encrypted_secret TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (endpoint_id, version),
    CONSTRAINT ck_webhook_endpoint_secret_version CHECK (version > 0)
);

CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    outbox_event_id UUID NOT NULL REFERENCES outbox_events(id),
    event_type VARCHAR(120) NOT NULL,
    event_version INTEGER NOT NULL,
    secret_version INTEGER NOT NULL,
    payload_json JSONB NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    response_status INTEGER,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_webhook_delivery_endpoint FOREIGN KEY (organization_id, endpoint_id)
        REFERENCES webhook_endpoints(organization_id, id),
    CONSTRAINT fk_webhook_delivery_secret FOREIGN KEY (endpoint_id, secret_version)
        REFERENCES webhook_endpoint_secrets(endpoint_id, version),
    CONSTRAINT ck_webhook_delivery_state CHECK (state IN ('PENDING', 'DELIVERED', 'DEAD_LETTER')),
    CONSTRAINT ck_webhook_delivery_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ux_webhook_delivery_event UNIQUE (endpoint_id, outbox_event_id)
);

CREATE INDEX ix_webhook_deliveries_dispatch
    ON webhook_deliveries(available_at, created_at)
    WHERE state = 'PENDING';
CREATE INDEX ix_webhook_deliveries_org
    ON webhook_deliveries(organization_id, created_at DESC);
