CREATE TABLE entitlements (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    metric VARCHAR(64) NOT NULL,
    allowance BIGINT NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_entitlements_allowance CHECK (allowance >= 0),
    CONSTRAINT ck_entitlements_period CHECK (period_end > period_start),
    CONSTRAINT ux_entitlements_period UNIQUE (organization_id, metric, period_start)
);

CREATE INDEX ix_entitlements_org_active ON entitlements(organization_id, metric, period_end);

CREATE TABLE usage_reservations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    entitlement_id UUID NOT NULL REFERENCES entitlements(id),
    metric VARCHAR(64) NOT NULL,
    reserved_units BIGINT NOT NULL,
    committed_units BIGINT,
    status VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_usage_reservations_units CHECK (
        reserved_units > 0
        AND (committed_units IS NULL OR committed_units BETWEEN 0 AND reserved_units)
    ),
    CONSTRAINT ck_usage_reservations_status CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ux_usage_reservations_idempotency UNIQUE (organization_id, metric, idempotency_key)
);

CREATE INDEX ix_usage_reservations_active
    ON usage_reservations(organization_id, metric, expires_at)
    WHERE status = 'RESERVED';

CREATE TABLE usage_ledger (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    entitlement_id UUID NOT NULL REFERENCES entitlements(id),
    reservation_id UUID REFERENCES usage_reservations(id),
    event_type VARCHAR(24) NOT NULL,
    units BIGINT NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_usage_ledger_event CHECK (event_type IN ('RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED', 'ADJUSTED')),
    CONSTRAINT ck_usage_ledger_units CHECK (units >= 0)
);

CREATE INDEX ix_usage_ledger_org_time ON usage_ledger(organization_id, occurred_at DESC, id DESC);

CREATE TABLE cost_ledger (
    id UUID PRIMARY KEY,
    organization_id UUID REFERENCES organizations(id),
    operation VARCHAR(80) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(120) NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    thought_tokens BIGINT NOT NULL DEFAULT 0,
    actual_cost_microusd BIGINT NOT NULL,
    shadow_cost_microusd BIGINT NOT NULL,
    latency_ms BIGINT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    correlation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_cost_ledger_non_negative CHECK (
        input_tokens >= 0
        AND output_tokens >= 0
        AND thought_tokens >= 0
        AND actual_cost_microusd >= 0
        AND shadow_cost_microusd >= 0
        AND latency_ms >= 0
        AND retry_count >= 0
    )
);

CREATE INDEX ix_cost_ledger_org_time ON cost_ledger(organization_id, occurred_at DESC, id DESC);
CREATE INDEX ix_cost_ledger_provider_model ON cost_ledger(provider, model, occurred_at DESC);
