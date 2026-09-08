CREATE TABLE pilot_leads (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(128) NOT NULL,
    contact_name VARCHAR(160) NOT NULL,
    work_email VARCHAR(320) NOT NULL,
    normalized_email VARCHAR(320) NOT NULL,
    organization_name VARCHAR(240) NOT NULL,
    buyer_role VARCHAR(32) NOT NULL,
    monthly_video_minutes VARCHAR(24) NOT NULL,
    learner_count VARCHAR(24) NOT NULL,
    primary_goal VARCHAR(40) NOT NULL,
    note VARCHAR(1000),
    acquisition_source VARCHAR(32) NOT NULL,
    acquisition_campaign VARCHAR(80),
    contact_consent_version VARCHAR(80) NOT NULL,
    contact_consent_at TIMESTAMPTZ NOT NULL,
    submitter_hash VARCHAR(128) NOT NULL,
    fit_score INTEGER NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'NEW',
    organization_id UUID REFERENCES organizations(id),
    lost_reason VARCHAR(500),
    redacted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_pilot_lead_role CHECK (
        buyer_role IN ('OWNER', 'TRAINING_MANAGER', 'INSTRUCTOR', 'OPERATIONS', 'OTHER')
    ),
    CONSTRAINT ck_pilot_lead_minutes CHECK (
        monthly_video_minutes IN ('UNDER_100', 'BETWEEN_100_299', 'BETWEEN_300_599', 'BETWEEN_600_1499', 'OVER_1500')
    ),
    CONSTRAINT ck_pilot_lead_learners CHECK (
        learner_count IN ('UNDER_50', 'BETWEEN_50_199', 'BETWEEN_200_499', 'BETWEEN_500_999', 'OVER_1000')
    ),
    CONSTRAINT ck_pilot_lead_goal CHECK (
        primary_goal IN ('SAVE_AUTHORING_TIME', 'IMPROVE_COMPLETION', 'PROVE_LEARNING', 'SCALE_COHORTS', 'OTHER')
    ),
    CONSTRAINT ck_pilot_lead_source CHECK (
        acquisition_source IN ('DIRECT', 'SAMPLE_COURSE', 'FOUNDER_OUTREACH', 'PARTNER', 'REFERRAL')
    ),
    CONSTRAINT ck_pilot_lead_score CHECK (fit_score BETWEEN 0 AND 100),
    CONSTRAINT ck_pilot_lead_priority CHECK (priority IN ('HOT', 'WARM', 'NURTURE')),
    CONSTRAINT ck_pilot_lead_status CHECK (
        status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'PROPOSAL', 'WON', 'LOST')
    ),
    CONSTRAINT ck_pilot_lead_lost_reason CHECK (
        (status = 'LOST' AND lost_reason IS NOT NULL AND length(trim(lost_reason)) >= 5)
        OR (status <> 'LOST' AND lost_reason IS NULL)
    ),
    CONSTRAINT ck_pilot_lead_won_org CHECK (
        (status = 'WON' AND organization_id IS NOT NULL)
        OR (status <> 'WON' AND organization_id IS NULL)
    )
);

CREATE INDEX ix_pilot_leads_sales_queue
    ON pilot_leads(status, priority, created_at);
CREATE INDEX ix_pilot_leads_normalized_email
    ON pilot_leads(normalized_email, created_at DESC);
CREATE INDEX ix_pilot_leads_acquisition
    ON pilot_leads(acquisition_source, acquisition_campaign, created_at);
CREATE UNIQUE INDEX ux_pilot_leads_converted_organization
    ON pilot_leads(organization_id) WHERE organization_id IS NOT NULL;

CREATE TABLE pilot_lead_events (
    id UUID PRIMARY KEY,
    pilot_lead_id UUID NOT NULL REFERENCES pilot_leads(id),
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    actor_subject_hash VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_pilot_lead_event_status CHECK (
        (from_status IS NULL OR from_status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'PROPOSAL', 'WON', 'LOST'))
        AND to_status IN ('NEW', 'CONTACTED', 'QUALIFIED', 'PROPOSAL', 'WON', 'LOST')
    )
);

CREATE INDEX ix_pilot_lead_events_lead_time
    ON pilot_lead_events(pilot_lead_id, occurred_at);
