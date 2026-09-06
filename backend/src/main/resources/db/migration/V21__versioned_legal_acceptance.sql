CREATE TABLE legal_acceptances (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    policy_set_version VARCHAR(40) NOT NULL,
    terms_version VARCHAR(40) NOT NULL,
    privacy_version VARCHAR(40) NOT NULL,
    acceptable_use_version VARCHAR(40) NOT NULL,
    ai_notice_version VARCHAR(40) NOT NULL,
    request_evidence_hash VARCHAR(128) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ux_legal_acceptance_versions UNIQUE (
        user_id, policy_set_version, terms_version, privacy_version,
        acceptable_use_version, ai_notice_version
    )
);

CREATE INDEX ix_legal_acceptance_user_time ON legal_acceptances(user_id, accepted_at DESC);
