CREATE TABLE support_access_grants (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    scope VARCHAR(24) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    ticket_reference VARCHAR(120),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES users(id),
    CONSTRAINT ck_support_grant_scope CHECK (scope IN ('DIAGNOSTICS')),
    CONSTRAINT ck_support_grant_window CHECK (
        expires_at > created_at AND expires_at <= created_at + INTERVAL '24 hours'
    ),
    CONSTRAINT ck_support_grant_revocation CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

CREATE INDEX ix_support_grants_org_created
    ON support_access_grants(organization_id, created_at DESC, id DESC);

CREATE TABLE support_access_events (
    id UUID PRIMARY KEY,
    support_access_grant_id UUID NOT NULL REFERENCES support_access_grants(id),
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    action VARCHAR(24) NOT NULL,
    actor_user_id UUID REFERENCES users(id),
    support_subject_hash VARCHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_support_event_action CHECK (action IN ('CREATED', 'ACCESSED', 'REVOKED')),
    CONSTRAINT ck_support_event_actor CHECK (
        (action IN ('CREATED', 'REVOKED') AND actor_user_id IS NOT NULL AND support_subject_hash IS NULL)
        OR (action = 'ACCESSED' AND actor_user_id IS NULL AND support_subject_hash IS NOT NULL)
    )
);

CREATE INDEX ix_support_events_grant_time
    ON support_access_events(support_access_grant_id, occurred_at DESC, id DESC);
