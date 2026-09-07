CREATE TABLE promotion_campaigns (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    discount_bps INTEGER NOT NULL,
    plan_code_prefix VARCHAR(40),
    attribution_channel VARCHAR(24) NOT NULL,
    partner_reference VARCHAR(120),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    max_redemptions INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_promotion_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{2,39}$'),
    CONSTRAINT ck_promotion_discount CHECK (discount_bps BETWEEN 1 AND 5000),
    CONSTRAINT ck_promotion_window CHECK (ends_at > starts_at),
    CONSTRAINT ck_promotion_capacity CHECK (max_redemptions > 0),
    CONSTRAINT ck_promotion_channel CHECK (
        attribution_channel IN ('REFERRAL', 'PARTNER', 'SALES', 'RETENTION')
    )
);

CREATE INDEX ix_promotion_campaigns_active_window
    ON promotion_campaigns(active, starts_at, ends_at);

CREATE TABLE promotion_campaign_events (
    id UUID PRIMARY KEY,
    promotion_campaign_id UUID NOT NULL REFERENCES promotion_campaigns(id),
    action VARCHAR(24) NOT NULL,
    actor_subject_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_promotion_campaign_event_action CHECK (action IN ('CREATED', 'DEACTIVATED'))
);

CREATE INDEX ix_promotion_campaign_events_campaign
    ON promotion_campaign_events(promotion_campaign_id, occurred_at, id);

ALTER TABLE billing_orders ADD COLUMN list_price_vnd BIGINT;
ALTER TABLE billing_orders ADD COLUMN discount_vnd BIGINT NOT NULL DEFAULT 0;
ALTER TABLE billing_orders ADD COLUMN promotion_campaign_id UUID REFERENCES promotion_campaigns(id);
UPDATE billing_orders SET list_price_vnd = amount_vnd;
ALTER TABLE billing_orders ADD CONSTRAINT ck_billing_order_pricing
    CHECK (list_price_vnd > 0 AND discount_vnd >= 0
        AND discount_vnd < list_price_vnd AND amount_vnd = list_price_vnd - discount_vnd);

ALTER TABLE invoices ADD COLUMN list_price_vnd BIGINT;
ALTER TABLE invoices ADD COLUMN discount_vnd BIGINT NOT NULL DEFAULT 0;
ALTER TABLE invoices ADD COLUMN promotion_campaign_id UUID REFERENCES promotion_campaigns(id);
UPDATE invoices SET list_price_vnd = amount_due_vnd;
ALTER TABLE invoices ADD CONSTRAINT ck_invoice_pricing
    CHECK (list_price_vnd > 0 AND discount_vnd >= 0
        AND discount_vnd < list_price_vnd AND amount_due_vnd = list_price_vnd - discount_vnd);

CREATE TABLE promotion_redemptions (
    id UUID PRIMARY KEY,
    promotion_campaign_id UUID NOT NULL REFERENCES promotion_campaigns(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    billing_order_id UUID NOT NULL UNIQUE REFERENCES billing_orders(id) ON DELETE CASCADE,
    list_price_vnd BIGINT NOT NULL,
    discount_vnd BIGINT NOT NULL,
    amount_vnd BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    reserved_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    redeemed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    CONSTRAINT ux_promotion_once_per_organization UNIQUE (promotion_campaign_id, organization_id),
    CONSTRAINT ck_promotion_redemption_amounts CHECK (
        list_price_vnd > 0 AND discount_vnd > 0 AND discount_vnd < list_price_vnd
        AND amount_vnd = list_price_vnd - discount_vnd
    ),
    CONSTRAINT ck_promotion_redemption_state CHECK (state IN ('RESERVED', 'REDEEMED', 'RELEASED')),
    CONSTRAINT ck_promotion_redemption_terminal CHECK (
        (state = 'RESERVED' AND redeemed_at IS NULL AND released_at IS NULL)
        OR (state = 'REDEEMED' AND redeemed_at IS NOT NULL AND released_at IS NULL)
        OR (state = 'RELEASED' AND redeemed_at IS NULL AND released_at IS NOT NULL)
    )
);

CREATE INDEX ix_promotion_redemptions_capacity
    ON promotion_redemptions(promotion_campaign_id, state, expires_at);
