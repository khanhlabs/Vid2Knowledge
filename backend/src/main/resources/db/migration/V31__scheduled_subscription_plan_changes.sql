ALTER TABLE subscriptions
    ADD COLUMN next_plan_id UUID REFERENCES pricing_plans(id),
    ADD COLUMN plan_change_scheduled_at TIMESTAMPTZ,
    ADD COLUMN plan_change_scheduled_by UUID REFERENCES users(id);

ALTER TABLE subscriptions ADD CONSTRAINT ck_subscription_plan_change
    CHECK (
        (next_plan_id IS NULL AND plan_change_scheduled_at IS NULL AND plan_change_scheduled_by IS NULL)
        OR
        (next_plan_id IS NOT NULL AND plan_change_scheduled_at IS NOT NULL AND plan_change_scheduled_by IS NOT NULL)
    );

ALTER TABLE subscriptions ADD CONSTRAINT ck_subscription_cancel_or_change
    CHECK (cancel_at_period_end = FALSE OR next_plan_id IS NULL);

CREATE INDEX ix_subscriptions_scheduled_plan_change
    ON subscriptions(current_period_end)
    WHERE next_plan_id IS NOT NULL AND status = 'ACTIVE';
