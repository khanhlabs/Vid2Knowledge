CREATE TABLE assessment_snapshots (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    package_revision_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    mode VARCHAR(24) NOT NULL,
    start_idempotency_key VARCHAR(160) NOT NULL,
    questions_json JSONB NOT NULL,
    answer_key_json JSONB NOT NULL,
    question_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_assessment_snapshot_progress FOREIGN KEY (assignment_id, user_id)
        REFERENCES learner_progress(assignment_id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_snapshot_revision FOREIGN KEY (organization_id, package_revision_id)
        REFERENCES package_revisions(organization_id, id),
    CONSTRAINT ck_assessment_mode CHECK (mode IN ('PRACTICE', 'DELAYED_RECALL')),
    CONSTRAINT ck_assessment_question_count CHECK (question_count > 0),
    CONSTRAINT ck_assessment_expiry CHECK (expires_at > created_at),
    CONSTRAINT ux_assessment_start_idempotency
        UNIQUE (assignment_id, user_id, start_idempotency_key),
    CONSTRAINT ux_assessment_snapshot_scope UNIQUE (organization_id, id),
    CONSTRAINT ux_assessment_snapshot_owner UNIQUE (id, assignment_id, user_id)
);

CREATE INDEX ix_assessment_snapshots_learner
    ON assessment_snapshots(organization_id, user_id, created_at DESC, id DESC);

CREATE TABLE assessment_attempts (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    submission_idempotency_key VARCHAR(160) NOT NULL,
    answers_json JSONB NOT NULL,
    score_percent INTEGER NOT NULL,
    correct_count INTEGER NOT NULL,
    question_count INTEGER NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_assessment_attempt_snapshot FOREIGN KEY (organization_id, snapshot_id)
        REFERENCES assessment_snapshots(organization_id, id),
    CONSTRAINT fk_assessment_attempt_owner FOREIGN KEY (snapshot_id, assignment_id, user_id)
        REFERENCES assessment_snapshots(id, assignment_id, user_id),
    CONSTRAINT ck_assessment_attempt_score CHECK (score_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_assessment_attempt_counts CHECK (
        question_count > 0 AND correct_count BETWEEN 0 AND question_count
    ),
    CONSTRAINT ux_assessment_attempt_snapshot UNIQUE (snapshot_id),
    CONSTRAINT ux_assessment_submit_idempotency
        UNIQUE (assignment_id, user_id, submission_idempotency_key)
);

CREATE INDEX ix_assessment_attempts_learner
    ON assessment_attempts(organization_id, user_id, submitted_at DESC, id DESC);

CREATE TABLE assessment_attempt_answers (
    attempt_id UUID NOT NULL REFERENCES assessment_attempts(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    question_id VARCHAR(120) NOT NULL,
    question_text VARCHAR(1000) NOT NULL,
    selected_answer_index INTEGER NOT NULL,
    correct_answer_index INTEGER NOT NULL,
    is_correct BOOLEAN NOT NULL,
    source_timestamp_seconds BIGINT NOT NULL,
    PRIMARY KEY (attempt_id, position),
    CONSTRAINT ck_assessment_answer_position CHECK (position >= 0),
    CONSTRAINT ck_assessment_answer_indexes CHECK (
        selected_answer_index BETWEEN 0 AND 3 AND correct_answer_index BETWEEN 0 AND 3
    ),
    CONSTRAINT ck_assessment_source_timestamp CHECK (source_timestamp_seconds >= 0)
);

CREATE INDEX ix_assessment_wrong_answers
    ON assessment_attempt_answers(question_id, is_correct) WHERE is_correct = FALSE;
