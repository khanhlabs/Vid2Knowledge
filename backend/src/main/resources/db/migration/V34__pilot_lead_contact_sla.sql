ALTER TABLE pilot_leads ADD COLUMN contact_due_at TIMESTAMPTZ;

UPDATE pilot_leads
SET contact_due_at = created_at + CASE priority
    WHEN 'HOT' THEN INTERVAL '4 hours'
    WHEN 'WARM' THEN INTERVAL '24 hours'
    ELSE INTERVAL '72 hours'
END;

ALTER TABLE pilot_leads ALTER COLUMN contact_due_at SET NOT NULL;

CREATE INDEX ix_pilot_leads_contact_sla
    ON pilot_leads(contact_due_at, priority, created_at)
    WHERE status = 'NEW' AND redacted_at IS NULL;
