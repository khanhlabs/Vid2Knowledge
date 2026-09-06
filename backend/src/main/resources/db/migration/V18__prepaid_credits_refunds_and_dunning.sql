ALTER TABLE pricing_plans
    ADD COLUMN product_type VARCHAR(24) NOT NULL DEFAULT 'SUBSCRIPTION';

ALTER TABLE pricing_plans ADD CONSTRAINT ck_pricing_plan_product_type
    CHECK (product_type IN ('SUBSCRIPTION', 'TOP_UP'));
ALTER TABLE pricing_plans ADD CONSTRAINT ck_pricing_plan_product_interval
    CHECK ((product_type = 'SUBSCRIPTION' AND billing_interval IN ('MONTH', 'YEAR'))
        OR (product_type = 'TOP_UP' AND billing_interval = 'ONE_TIME'));
ALTER TABLE pricing_plans ADD CONSTRAINT ck_top_up_has_credits
    CHECK (product_type <> 'TOP_UP' OR processed_video_seconds + qa_queries > 0);

ALTER TABLE invoices ALTER COLUMN subscription_id DROP NOT NULL;
ALTER TABLE invoices ADD COLUMN invoice_type VARCHAR(24) NOT NULL DEFAULT 'SUBSCRIPTION';
ALTER TABLE invoices ADD CONSTRAINT ck_invoice_type
    CHECK (invoice_type IN ('SUBSCRIPTION', 'TOP_UP'));

CREATE TABLE credit_grants (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    billing_order_id UUID NOT NULL REFERENCES billing_orders(id),
    entitlement_id UUID NOT NULL REFERENCES entitlements(id),
    metric VARCHAR(64) NOT NULL,
    granted_units BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    granted_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ux_credit_grant_order_metric UNIQUE (billing_order_id, metric),
    CONSTRAINT ck_credit_grant_units CHECK (granted_units > 0),
    CONSTRAINT ck_credit_grant_state CHECK (state IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_credit_grant_revocation CHECK (
        (state = 'ACTIVE' AND revoked_at IS NULL) OR (state = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);

CREATE INDEX ix_credit_grants_org_time ON credit_grants(organization_id, granted_at DESC);

CREATE TABLE refund_requests (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    payment_id UUID NOT NULL REFERENCES payments(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    amount_vnd BIGINT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    resolution_reason VARCHAR(1000),
    state VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    provider_reference VARCHAR(200),
    requested_by UUID NOT NULL REFERENCES users(id),
    requested_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ux_refund_invoice UNIQUE (invoice_id),
    CONSTRAINT ux_refund_provider_reference UNIQUE (provider_reference),
    CONSTRAINT ck_refund_amount CHECK (amount_vnd > 0),
    CONSTRAINT ck_refund_state CHECK (state IN ('REQUESTED', 'SUCCEEDED', 'REJECTED')),
    CONSTRAINT ck_refund_resolution CHECK (
        (state = 'REQUESTED' AND resolved_at IS NULL AND provider_reference IS NULL AND resolution_reason IS NULL)
        OR (state = 'SUCCEEDED' AND resolved_at IS NOT NULL AND provider_reference IS NOT NULL)
        OR (state = 'REJECTED' AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL)
    )
);

CREATE INDEX ix_refunds_org_time ON refund_requests(organization_id, requested_at DESC);

CREATE TABLE billing_adjustments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    refund_request_id UUID NOT NULL REFERENCES refund_requests(id),
    entitlement_id UUID NOT NULL REFERENCES entitlements(id),
    metric VARCHAR(64) NOT NULL,
    units_delta BIGINT NOT NULL,
    reason VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_billing_adjustment_refund_metric UNIQUE (refund_request_id, metric),
    CONSTRAINT ck_billing_adjustment_nonzero CHECK (units_delta <> 0)
);

CREATE TABLE renewal_attempts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    billing_order_id UUID NOT NULL UNIQUE REFERENCES billing_orders(id),
    invoice_id UUID NOT NULL UNIQUE REFERENCES invoices(id),
    period_end TIMESTAMPTZ NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'AWAITING_PAYMENT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_renewal_subscription_period UNIQUE (subscription_id, period_end),
    CONSTRAINT ck_renewal_state CHECK (state IN ('AWAITING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX ix_renewal_attempts_pending ON renewal_attempts(state, period_end);

INSERT INTO pricing_plans(
    id, code, version, name, billing_interval, amount_vnd,
    processed_video_seconds, instructor_seats, active_learners, effective_from,
    qa_queries, product_type
) VALUES
    ('00000000-0000-7000-8000-000000000301', 'CREDIT_120', 1, 'Nạp thêm 120 phút', 'ONE_TIME',
     390000, 7200, 1, 1, CURRENT_TIMESTAMP, 250, 'TOP_UP'),
    ('00000000-0000-7000-8000-000000000302', 'CREDIT_600', 1, 'Nạp thêm 600 phút', 'ONE_TIME',
     1690000, 36000, 1, 1, CURRENT_TIMESTAMP, 1500, 'TOP_UP');
