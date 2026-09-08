ALTER TABLE privacy_deletion_requests DROP CONSTRAINT ck_privacy_deletion_state;
ALTER TABLE privacy_deletion_requests DROP CONSTRAINT ck_privacy_deletion_terminal;

ALTER TABLE privacy_deletion_requests
    ADD COLUMN auth_provider_user_id UUID,
    ADD COLUMN provider_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN provider_last_error VARCHAR(500),
    ADD COLUMN provider_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN locally_erased_at TIMESTAMPTZ,
    ADD COLUMN provider_deleted_at TIMESTAMPTZ;

-- Legacy completion only proved local erasure. The subject has already been
-- hashed; an operator must reconcile it with the IdP before claiming completion.
UPDATE privacy_deletion_requests
SET state = 'IDENTITY_REVIEW', locally_erased_at = completed_at, completed_at = NULL,
    provider_last_error = 'LEGACY_PROVIDER_DELETION_UNVERIFIED'
WHERE state = 'COMPLETED';

ALTER TABLE privacy_deletion_requests
    ADD CONSTRAINT ck_privacy_deletion_state
        CHECK (state IN ('REQUESTED', 'IDENTITY_PENDING', 'IDENTITY_REVIEW', 'CANCELLED', 'COMPLETED')),
    ADD CONSTRAINT ck_privacy_provider_attempts CHECK (provider_attempt_count >= 0),
    ADD CONSTRAINT ck_privacy_deletion_terminal CHECK (
        (state = 'REQUESTED' AND cancelled_at IS NULL AND completed_at IS NULL
            AND auth_provider_user_id IS NULL AND provider_deleted_at IS NULL AND locally_erased_at IS NULL)
        OR (state = 'IDENTITY_PENDING' AND cancelled_at IS NULL AND completed_at IS NULL
            AND auth_provider_user_id IS NOT NULL AND provider_deleted_at IS NULL AND locally_erased_at IS NOT NULL)
        OR (state = 'IDENTITY_REVIEW' AND cancelled_at IS NULL AND completed_at IS NULL
            AND auth_provider_user_id IS NULL AND provider_deleted_at IS NULL AND locally_erased_at IS NOT NULL)
        OR (state = 'CANCELLED' AND cancelled_at IS NOT NULL AND completed_at IS NULL
            AND auth_provider_user_id IS NULL AND provider_deleted_at IS NULL AND locally_erased_at IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND cancelled_at IS NULL
            AND auth_provider_user_id IS NOT NULL AND provider_deleted_at IS NOT NULL AND locally_erased_at IS NOT NULL)
    );

DROP INDEX ux_privacy_deletion_active;
CREATE UNIQUE INDEX ux_privacy_deletion_active
    ON privacy_deletion_requests(user_id) WHERE state IN ('REQUESTED', 'IDENTITY_PENDING', 'IDENTITY_REVIEW');
CREATE INDEX ix_privacy_identity_pending
    ON privacy_deletion_requests(provider_next_attempt_at, scheduled_for, id)
    WHERE state IN ('REQUESTED', 'IDENTITY_PENDING');
