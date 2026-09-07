CREATE TABLE organization_acquisition_attributions (
    organization_id UUID PRIMARY KEY REFERENCES organizations(id),
    source VARCHAR(32) NOT NULL,
    attributed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_organization_acquisition_source CHECK (
        source IN ('DIRECT', 'SAMPLE_COURSE', 'LEGACY_UNKNOWN')
    )
);

INSERT INTO organization_acquisition_attributions(organization_id, source, attributed_at)
SELECT id, 'LEGACY_UNKNOWN', created_at FROM organizations;

CREATE INDEX ix_organization_acquisition_source
    ON organization_acquisition_attributions(source, attributed_at DESC, organization_id);
