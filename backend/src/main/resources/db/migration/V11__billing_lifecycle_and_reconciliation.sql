ALTER TABLE subscriptions DROP CONSTRAINT ck_subscription_status;
ALTER TABLE subscriptions ADD CONSTRAINT ck_subscription_status
    CHECK (status IN ('SCHEDULED', 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED'));

CREATE UNIQUE INDEX ux_subscriptions_one_current
    ON subscriptions(organization_id)
    WHERE status IN ('ACTIVE', 'PAST_DUE');

ALTER TABLE entitlements
    ADD COLUMN subscription_id UUID REFERENCES subscriptions(id);

ALTER TABLE billing_orders
    ADD COLUMN checkout_claim_token UUID,
    ADD COLUMN checkout_claim_expires_at TIMESTAMPTZ;

ALTER TABLE billing_orders ADD CONSTRAINT ck_billing_order_checkout_claim
    CHECK ((checkout_claim_token IS NULL) = (checkout_claim_expires_at IS NULL));

CREATE INDEX ix_entitlements_subscription ON entitlements(subscription_id, period_start);

CREATE TABLE subscription_billing_periods (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    billing_order_id UUID NOT NULL UNIQUE REFERENCES billing_orders(id),
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_subscription_billing_period CHECK (period_end > period_start)
);

CREATE INDEX ix_subscription_periods_org_start
    ON subscription_billing_periods(organization_id, period_start DESC);

CREATE SEQUENCE invoice_number_seq START WITH 100000 INCREMENT BY 1;

CREATE TABLE invoices (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    subscription_id UUID NOT NULL REFERENCES subscriptions(id),
    billing_order_id UUID NOT NULL UNIQUE REFERENCES billing_orders(id),
    invoice_number VARCHAR(40) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    amount_due_vnd BIGINT NOT NULL,
    amount_paid_vnd BIGINT NOT NULL DEFAULT 0,
    due_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_invoice_state CHECK (state IN ('OPEN', 'PAID', 'VOID', 'REFUNDED')),
    CONSTRAINT ck_invoice_amounts CHECK (
        amount_due_vnd > 0 AND amount_paid_vnd >= 0 AND amount_paid_vnd <= amount_due_vnd
    )
);

CREATE INDEX ix_invoices_org_created ON invoices(organization_id, created_at DESC, id DESC);
CREATE INDEX ix_billing_orders_reconcile
    ON billing_orders(state, updated_at)
    WHERE state = 'PENDING' AND provider_payment_link_id IS NOT NULL;
