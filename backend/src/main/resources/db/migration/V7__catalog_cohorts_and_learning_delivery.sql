ALTER TABLE sources ADD CONSTRAINT ux_sources_org_id UNIQUE (organization_id, id);
ALTER TABLE learning_packages ADD CONSTRAINT ux_packages_org_id UNIQUE (organization_id, id);
ALTER TABLE package_revisions ADD CONSTRAINT ux_revisions_org_id UNIQUE (organization_id, id);

CREATE TABLE courses (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    title VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by UUID NOT NULL REFERENCES users(id),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_courses_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ux_courses_org_id UNIQUE (organization_id, id)
);

CREATE INDEX ix_courses_org_updated ON courses(organization_id, updated_at DESC, id DESC);

CREATE TABLE course_modules (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    course_id UUID NOT NULL,
    title VARCHAR(240) NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_modules_course FOREIGN KEY (organization_id, course_id)
        REFERENCES courses(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_modules_position CHECK (position > 0),
    CONSTRAINT ux_modules_position UNIQUE (course_id, position),
    CONSTRAINT ux_modules_org_id UNIQUE (organization_id, id)
);

CREATE TABLE lessons (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    module_id UUID NOT NULL,
    package_id UUID,
    title VARCHAR(240) NOT NULL,
    position INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_lessons_module FOREIGN KEY (organization_id, module_id)
        REFERENCES course_modules(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_lessons_package FOREIGN KEY (organization_id, package_id)
        REFERENCES learning_packages(organization_id, id),
    CONSTRAINT ck_lessons_position CHECK (position > 0),
    CONSTRAINT ux_lessons_position UNIQUE (module_id, position),
    CONSTRAINT ux_lessons_org_id UNIQUE (organization_id, id)
);

CREATE TABLE cohorts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(240) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_cohorts_status CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'ARCHIVED')),
    CONSTRAINT ck_cohorts_window CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ux_cohorts_org_id UNIQUE (organization_id, id)
);

CREATE TABLE cohort_members (
    organization_id UUID NOT NULL,
    cohort_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (cohort_id, user_id),
    CONSTRAINT fk_cohort_members_cohort FOREIGN KEY (organization_id, cohort_id)
        REFERENCES cohorts(organization_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_cohort_members_user ON cohort_members(organization_id, user_id, cohort_id);

CREATE TABLE assignments (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    cohort_id UUID NOT NULL,
    lesson_id UUID NOT NULL,
    package_revision_id UUID NOT NULL,
    title VARCHAR(240) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ,
    state VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_by UUID NOT NULL REFERENCES users(id),
    published_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_assignments_cohort FOREIGN KEY (organization_id, cohort_id)
        REFERENCES cohorts(organization_id, id),
    CONSTRAINT fk_assignments_lesson FOREIGN KEY (organization_id, lesson_id)
        REFERENCES lessons(organization_id, id),
    CONSTRAINT fk_assignments_revision FOREIGN KEY (organization_id, package_revision_id)
        REFERENCES package_revisions(organization_id, id),
    CONSTRAINT ck_assignments_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_assignments_window CHECK (due_at IS NULL OR due_at > available_at),
    CONSTRAINT ux_assignments_org_id UNIQUE (organization_id, id)
);

CREATE INDEX ix_assignments_cohort ON assignments(organization_id, cohort_id, available_at, id);

CREATE TABLE assignment_recipients (
    organization_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (assignment_id, user_id),
    CONSTRAINT fk_recipients_assignment FOREIGN KEY (organization_id, assignment_id)
        REFERENCES assignments(organization_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_assignment_recipients_user ON assignment_recipients(user_id, assigned_at DESC, assignment_id);

CREATE TABLE learner_progress (
    organization_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(24) NOT NULL DEFAULT 'ASSIGNED',
    progress_percent INTEGER NOT NULL DEFAULT 0,
    best_score_percent INTEGER,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (assignment_id, user_id),
    CONSTRAINT fk_progress_recipient FOREIGN KEY (assignment_id, user_id)
        REFERENCES assignment_recipients(assignment_id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_progress_status CHECK (status IN ('ASSIGNED', 'STARTED', 'COMPLETED')),
    CONSTRAINT ck_progress_percent CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_progress_score CHECK (best_score_percent IS NULL OR best_score_percent BETWEEN 0 AND 100)
);

CREATE TABLE quiz_attempts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(160) NOT NULL,
    answers_json JSONB NOT NULL,
    score_percent INTEGER NOT NULL,
    correct_count INTEGER NOT NULL,
    question_count INTEGER NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_attempt_progress FOREIGN KEY (assignment_id, user_id)
        REFERENCES learner_progress(assignment_id, user_id),
    CONSTRAINT ck_attempt_score CHECK (score_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_attempt_counts CHECK (question_count > 0 AND correct_count BETWEEN 0 AND question_count),
    CONSTRAINT ux_attempt_idempotency UNIQUE (assignment_id, user_id, idempotency_key)
);

CREATE TABLE quality_feedback (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    package_revision_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    kind VARCHAR(24) NOT NULL,
    item_type VARCHAR(24),
    item_id VARCHAR(120),
    detail VARCHAR(2000),
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT fk_feedback_revision FOREIGN KEY (organization_id, package_revision_id)
        REFERENCES package_revisions(organization_id, id),
    CONSTRAINT ck_feedback_kind CHECK (kind IN ('HELPFUL', 'NOT_HELPFUL', 'REPORT_ERROR')),
    CONSTRAINT ck_feedback_status CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED'))
);

CREATE INDEX ix_quality_feedback_org ON quality_feedback(organization_id, status, created_at DESC, id DESC);
