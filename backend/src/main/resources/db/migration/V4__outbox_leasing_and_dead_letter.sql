ALTER TABLE outbox_events
    ADD COLUMN lease_owner VARCHAR(160),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

DROP INDEX ix_outbox_dispatch;

CREATE INDEX ix_outbox_dispatch
    ON outbox_events(available_at, occurred_at, id)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;
