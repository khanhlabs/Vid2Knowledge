CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    product_guidance_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    assignment_reminders_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    marketing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE notification_jobs DROP CONSTRAINT ck_notification_state;
ALTER TABLE notification_jobs ADD CONSTRAINT ck_notification_state
    CHECK (state IN ('PENDING', 'PROCESSING', 'SENT', 'DEAD', 'CANCELLED'));
ALTER TABLE notification_jobs ADD COLUMN cancellation_reason VARCHAR(160);
ALTER TABLE notification_jobs ADD COLUMN cancelled_at TIMESTAMPTZ;
ALTER TABLE notification_jobs ADD CONSTRAINT ck_notification_cancellation
    CHECK ((state = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL)
        OR (state <> 'CANCELLED' AND cancelled_at IS NULL AND cancellation_reason IS NULL));

CREATE TABLE notification_preference_changes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_guidance_enabled BOOLEAN NOT NULL,
    assignment_reminders_enabled BOOLEAN NOT NULL,
    marketing_enabled BOOLEAN NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_notification_preference_changes_user
    ON notification_preference_changes(user_id, changed_at DESC, id DESC);
