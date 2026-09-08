ALTER TABLE notification_jobs
    ALTER COLUMN organization_id DROP NOT NULL,
    ADD COLUMN pilot_lead_id UUID REFERENCES pilot_leads(id);

ALTER TABLE notification_jobs
    ADD CONSTRAINT ck_notification_subject
    CHECK (num_nonnulls(organization_id, pilot_lead_id) = 1);

CREATE INDEX ix_notification_jobs_pilot_lead
    ON notification_jobs(pilot_lead_id)
    WHERE pilot_lead_id IS NOT NULL;
