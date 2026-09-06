CREATE TABLE privacy_deletion_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    state VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    requested_at TIMESTAMPTZ NOT NULL,
    scheduled_for TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_privacy_deletion_state CHECK (state IN ('REQUESTED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT ck_privacy_deletion_schedule CHECK (scheduled_for > requested_at),
    CONSTRAINT ck_privacy_deletion_terminal CHECK (
        (state = 'REQUESTED' AND cancelled_at IS NULL AND completed_at IS NULL)
        OR (state = 'CANCELLED' AND cancelled_at IS NOT NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND cancelled_at IS NULL)
    )
);

CREATE UNIQUE INDEX ux_privacy_deletion_active
    ON privacy_deletion_requests(user_id) WHERE state = 'REQUESTED';
CREATE INDEX ix_privacy_deletion_due
    ON privacy_deletion_requests(scheduled_for) WHERE state = 'REQUESTED';

CREATE TABLE deleted_identity_blocks (
    subject_hash VARCHAR(64) PRIMARY KEY,
    deletion_request_id UUID NOT NULL UNIQUE REFERENCES privacy_deletion_requests(id),
    blocked_at TIMESTAMPTZ NOT NULL
);
