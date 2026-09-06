ALTER TABLE memberships DROP CONSTRAINT ck_memberships_role;
ALTER TABLE memberships ADD CONSTRAINT ck_memberships_role
    CHECK (role IN ('OWNER', 'ADMIN', 'INSTRUCTOR', 'REVIEWER', 'LEARNER', 'SUPPORT_READONLY'));

ALTER TABLE sources
    ADD COLUMN duration_seconds BIGINT,
    ADD COLUMN metadata_verified_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_sources_duration CHECK (duration_seconds IS NULL OR duration_seconds > 0);
