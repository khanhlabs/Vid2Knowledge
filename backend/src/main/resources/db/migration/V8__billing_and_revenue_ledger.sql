CREATE TABLE pricing_plans (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    version INTEGER NOT NULL,
    name VARCHAR(120) NOT NULL,
    billing_interval VARCHAR(16) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    processed_video_seconds BIGINT NOT NULL,
    instructor_seats INTEGER NOT NULL,
    active_learners INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_pricing_plan_version UNIQUE (code, version),
    CONSTRAINT ck_pricing_plan_interval CHECK (billing_interval IN ('MONTH', 'YEAR', 'ONE_TIME')),
    CONSTRAINT ck_pricing_plan_values CHECK (
        version > 0 AND amount_vnd > 0 AND processed_video_seconds >= 0
        AND instructor_seats > 0 AND active_learners > 0
    )
);

CREATE INDEX ix_pricing_plans_active ON pricing_plans(active, code, version DESC);

CREATE SEQUENCE billing_order_code_seq START WITH 100000000 INCREMENT BY 1;

CREATE TABLE billing_orders (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    plan_id UUID NOT NULL REFERENCES pricing_plans(id),
    order_code BIGINT NOT NULL UNIQUE,
    amount_vnd BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(160) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    provider_payment_link_id VARCHAR(160),
    checkout_url TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_billing_order_idempotency UNIQUE (organization_id, idempotency_key),
    CONSTRAINT ck_billing_order_amount CHECK (amount_vnd > 0),
    CONSTRAINT ck_billing_order_state CHECK (state IN (
        'PENDING', 'PAID', 'EXPIRED', 'CANCELLED', 'FAILED', 'PARTIALLY_REFUNDED', 'REFUNDED'
    ))
);

CREATE INDEX ix_billing_orders_org_created ON billing_orders(organization_id, created_at DESC, id DESC);

CREATE TABLE payments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    billing_order_id UUID NOT NULL REFERENCES billing_orders(id),
    provider VARCHAR(32) NOT NULL,
    provider_reference VARCHAR(200) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    state VARCHAR(24) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_payment_provider_reference UNIQUE (provider, provider_reference),
    CONSTRAINT ck_payment_amount CHECK (amount_vnd > 0),
    CONSTRAINT ck_payment_state CHECK (state IN ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED'))
);

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    plan_id UUID NOT NULL REFERENCES pricing_plans(id),
    billing_order_id UUID NOT NULL REFERENCES billing_orders(id),
    status VARCHAR(24) NOT NULL,
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end TIMESTAMPTZ NOT NULL,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_subscription_status CHECK (status IN ('ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_subscription_period CHECK (current_period_end > current_period_start),
    CONSTRAINT ux_subscription_order UNIQUE (billing_order_id)
);

CREATE INDEX ix_subscriptions_org_status ON subscriptions(organization_id, status, current_period_end DESC);

CREATE TABLE payment_webhook_inbox (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    event_key VARCHAR(240) NOT NULL,
    signature VARCHAR(256) NOT NULL,
    payload_json JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    CONSTRAINT ux_webhook_inbox_event UNIQUE (provider, event_key)
);

INSERT INTO pricing_plans(
    id, code, version, name, billing_interval, amount_vnd,
    processed_video_seconds, instructor_seats, active_learners, effective_from
) VALUES
    ('00000000-0000-7000-8000-000000000101', 'CREATOR_MONTHLY', 1, 'Creator', 'MONTH',
     790000, 18000, 3, 200, CURRENT_TIMESTAMP),
    ('00000000-0000-7000-8000-000000000102', 'CREATOR_ANNUAL', 1, 'Creator Annual', 'YEAR',
     7900000, 216000, 3, 200, CURRENT_TIMESTAMP),
    ('00000000-0000-7000-8000-000000000201', 'TRAINING_TEAM_MONTHLY', 1, 'Training Team', 'MONTH',
     2490000, 90000, 10, 1000, CURRENT_TIMESTAMP),
    ('00000000-0000-7000-8000-000000000202', 'TRAINING_TEAM_ANNUAL', 1, 'Training Team Annual', 'YEAR',
     24900000, 1080000, 10, 1000, CURRENT_TIMESTAMP);
