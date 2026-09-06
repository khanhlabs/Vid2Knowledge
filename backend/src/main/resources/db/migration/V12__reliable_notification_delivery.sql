CREATE TABLE notification_jobs (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    notification_type VARCHAR(48) NOT NULL,
    dedupe_key VARCHAR(180) NOT NULL UNIQUE,
    recipient_email VARCHAR(320) NOT NULL,
    encrypted_payload TEXT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL,
    lease_owner VARCHAR(160),
    lease_expires_at TIMESTAMPTZ,
    provider_message_id VARCHAR(160),
    last_error VARCHAR(1000),
    sent_at TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_notification_state CHECK (state IN ('PENDING', 'PROCESSING', 'SENT', 'DEAD')),
    CONSTRAINT ck_notification_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_lease CHECK (
        (lease_owner IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_notification_jobs_dispatch
    ON notification_jobs(available_at, created_at)
    WHERE state IN ('PENDING', 'PROCESSING');
