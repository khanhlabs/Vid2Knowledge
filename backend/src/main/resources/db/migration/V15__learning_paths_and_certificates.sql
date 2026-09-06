CREATE TABLE lesson_prerequisites (
    organization_id UUID NOT NULL,
    lesson_id UUID NOT NULL,
    prerequisite_lesson_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (lesson_id, prerequisite_lesson_id),
    CONSTRAINT fk_path_lesson FOREIGN KEY (organization_id, lesson_id)
        REFERENCES lessons(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_path_prerequisite FOREIGN KEY (organization_id, prerequisite_lesson_id)
        REFERENCES lessons(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_path_not_self CHECK (lesson_id <> prerequisite_lesson_id)
);

CREATE INDEX ix_lesson_prerequisites_org
    ON lesson_prerequisites(organization_id, prerequisite_lesson_id, lesson_id);

CREATE TABLE course_completion_rules (
    organization_id UUID NOT NULL,
    course_id UUID NOT NULL,
    passing_score_percent INTEGER NOT NULL DEFAULT 70,
    require_delayed_recall BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (course_id),
    CONSTRAINT fk_completion_rule_course FOREIGN KEY (organization_id, course_id)
        REFERENCES courses(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_completion_passing_score CHECK (passing_score_percent BETWEEN 1 AND 100)
);

CREATE TABLE completion_certificates (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    course_id UUID NOT NULL,
    cohort_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    verification_code VARCHAR(40) NOT NULL UNIQUE,
    criteria_snapshot_json JSONB NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(500),
    CONSTRAINT fk_certificate_course FOREIGN KEY (organization_id, course_id)
        REFERENCES courses(organization_id, id),
    CONSTRAINT fk_certificate_cohort FOREIGN KEY (organization_id, cohort_id)
        REFERENCES cohorts(organization_id, id),
    CONSTRAINT ux_certificate_active_path UNIQUE (course_id, cohort_id, user_id),
    CONSTRAINT ck_certificate_revocation CHECK (
        (revoked_at IS NULL AND revoke_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    )
);

CREATE INDEX ix_certificates_learner
    ON completion_certificates(organization_id, user_id, issued_at DESC, id DESC);
