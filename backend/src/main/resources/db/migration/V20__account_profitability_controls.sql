CREATE TABLE organization_economic_profiles (
    organization_id UUID PRIMARY KEY REFERENCES organizations(id),
    usd_vnd_rate BIGINT NOT NULL,
    payment_fee_bps INTEGER NOT NULL,
    payment_fixed_fee_vnd BIGINT NOT NULL,
    monthly_infrastructure_vnd BIGINT NOT NULL,
    monthly_support_minutes INTEGER NOT NULL,
    support_hourly_vnd BIGINT NOT NULL,
    tax_reserve_bps INTEGER NOT NULL,
    acquisition_cost_vnd BIGINT NOT NULL,
    monthly_logo_churn_bps INTEGER NOT NULL,
    assumptions_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_economics_nonnegative CHECK (
        usd_vnd_rate > 0 AND payment_fee_bps BETWEEN 0 AND 10000
        AND payment_fixed_fee_vnd >= 0 AND monthly_infrastructure_vnd >= 0
        AND monthly_support_minutes >= 0 AND support_hourly_vnd >= 0
        AND tax_reserve_bps BETWEEN 0 AND 10000 AND acquisition_cost_vnd >= 0
        AND monthly_logo_churn_bps BETWEEN 0 AND 10000
    )
);

CREATE TABLE account_cost_entries (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    category VARCHAR(24) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    incurred_at TIMESTAMPTZ NOT NULL,
    note VARCHAR(500) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    voided_at TIMESTAMPTZ,
    CONSTRAINT ck_account_cost_category CHECK (
        category IN ('ONBOARDING', 'SUPPORT', 'INFRASTRUCTURE', 'STORAGE', 'EMAIL', 'SALES', 'OTHER')
    ),
    CONSTRAINT ck_account_cost_amount CHECK (amount_vnd > 0)
);

CREATE INDEX ix_account_cost_org_time
    ON account_cost_entries(organization_id, incurred_at DESC) WHERE voided_at IS NULL;
