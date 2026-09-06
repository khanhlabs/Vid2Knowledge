ALTER TABLE memberships DROP CONSTRAINT ck_memberships_role;
ALTER TABLE memberships ADD CONSTRAINT ck_memberships_role
    CHECK (role IN ('OWNER', 'ADMIN', 'INSTRUCTOR', 'REVIEWER', 'LEARNER', 'SUPPORT_READONLY'));

CREATE UNIQUE INDEX ux_memberships_one_active_owner
    ON memberships(organization_id)
    WHERE role = 'OWNER' AND status = 'ACTIVE';

CREATE INDEX ix_invitations_org_created
    ON invitations(organization_id, created_at DESC, id DESC);
