ALTER TABLE generation_runs
    ALTER COLUMN output_json DROP NOT NULL,
    ADD COLUMN raw_output TEXT NOT NULL DEFAULT '',
    ADD COLUMN validation_state VARCHAR(24) NOT NULL DEFAULT 'VALID',
    ADD COLUMN validation_error VARCHAR(1000),
    ADD CONSTRAINT ck_generation_validation_state
        CHECK (validation_state IN ('VALID', 'INVALID')),
    ADD CONSTRAINT ck_generation_valid_output
        CHECK (validation_state <> 'VALID' OR output_json IS NOT NULL);

ALTER TABLE generation_runs ALTER COLUMN raw_output DROP DEFAULT;

ALTER TABLE analysis_jobs
    ADD COLUMN next_attempt_at TIMESTAMPTZ;

DROP INDEX ix_analysis_jobs_claim;
CREATE INDEX ix_analysis_jobs_claim ON analysis_jobs(state, next_attempt_at, queued_at)
    WHERE state IN ('QUEUED', 'RETRY_SCHEDULED');
