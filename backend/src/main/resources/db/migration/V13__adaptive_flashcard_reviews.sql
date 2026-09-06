CREATE TABLE flashcard_memory_states (
    organization_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    package_revision_id UUID NOT NULL,
    card_id VARCHAR(120) NOT NULL,
    state VARCHAR(16) NOT NULL,
    stability_days NUMERIC(14, 6) NOT NULL,
    difficulty NUMERIC(8, 6) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    last_reviewed_at TIMESTAMPTZ NOT NULL,
    review_count INTEGER NOT NULL,
    lapse_count INTEGER NOT NULL,
    algorithm_version VARCHAR(48) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (assignment_id, user_id, card_id),
    CONSTRAINT fk_flashcard_state_progress FOREIGN KEY (assignment_id, user_id)
        REFERENCES learner_progress(assignment_id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_flashcard_state_revision FOREIGN KEY (organization_id, package_revision_id)
        REFERENCES package_revisions(organization_id, id),
    CONSTRAINT ck_flashcard_state CHECK (state IN ('LEARNING', 'REVIEW', 'RELEARNING')),
    CONSTRAINT ck_flashcard_memory_values CHECK (
        stability_days > 0 AND difficulty BETWEEN 1 AND 10
        AND review_count > 0 AND lapse_count >= 0 AND lapse_count <= review_count
    )
);

CREATE INDEX ix_flashcard_states_due
    ON flashcard_memory_states(organization_id, user_id, due_at, assignment_id);

CREATE TABLE flashcard_review_log (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    card_id VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    rating VARCHAR(8) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL,
    previous_due_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ NOT NULL,
    scheduled_seconds BIGINT NOT NULL,
    elapsed_days NUMERIC(14, 6) NOT NULL,
    retrievability NUMERIC(10, 8) NOT NULL,
    stability_before NUMERIC(14, 6),
    stability_after NUMERIC(14, 6) NOT NULL,
    difficulty_before NUMERIC(8, 6),
    difficulty_after NUMERIC(8, 6) NOT NULL,
    state_before VARCHAR(16),
    state_after VARCHAR(16) NOT NULL,
    review_count_after INTEGER NOT NULL,
    lapse_count_after INTEGER NOT NULL,
    algorithm_version VARCHAR(48) NOT NULL,
    CONSTRAINT fk_flashcard_review_state FOREIGN KEY (assignment_id, user_id, card_id)
        REFERENCES flashcard_memory_states(assignment_id, user_id, card_id) ON DELETE CASCADE,
    CONSTRAINT ck_flashcard_rating CHECK (rating IN ('AGAIN', 'HARD', 'GOOD', 'EASY')),
    CONSTRAINT ck_flashcard_review_values CHECK (
        scheduled_seconds > 0 AND elapsed_days >= 0 AND retrievability BETWEEN 0 AND 1
        AND review_count_after > 0 AND lapse_count_after >= 0
        AND lapse_count_after <= review_count_after
    ),
    CONSTRAINT ux_flashcard_review_idempotency UNIQUE (assignment_id, user_id, idempotency_key)
);

CREATE INDEX ix_flashcard_reviews_learner_time
    ON flashcard_review_log(organization_id, user_id, reviewed_at DESC, id DESC);
