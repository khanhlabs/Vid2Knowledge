CREATE TABLE program_launches (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    course_id UUID NOT NULL,
    cohort_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_program_launch_course FOREIGN KEY (organization_id, course_id)
        REFERENCES courses(organization_id, id),
    CONSTRAINT fk_program_launch_cohort FOREIGN KEY (organization_id, cohort_id)
        REFERENCES cohorts(organization_id, id),
    CONSTRAINT fk_program_launch_assignment FOREIGN KEY (organization_id, assignment_id)
        REFERENCES assignments(organization_id, id),
    CONSTRAINT ux_program_launch_idempotency UNIQUE (organization_id, idempotency_key)
);
