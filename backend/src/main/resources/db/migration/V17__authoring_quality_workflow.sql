ALTER TABLE organizations
    ADD COLUMN approval_required BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE content_templates (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(120) NOT NULL,
    output_profile_json JSONB NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_content_template_state CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ux_content_template_scope UNIQUE (organization_id, id)
);

CREATE UNIQUE INDEX ux_content_template_active_name
    ON content_templates(organization_id, lower(name)) WHERE state = 'ACTIVE';

CREATE TABLE review_decisions (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    package_id UUID NOT NULL,
    package_revision_id UUID NOT NULL,
    reviewer_id UUID NOT NULL REFERENCES users(id),
    decision VARCHAR(16) NOT NULL,
    reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_review_package FOREIGN KEY (organization_id, package_id)
        REFERENCES learning_packages(organization_id, id),
    CONSTRAINT fk_review_revision FOREIGN KEY (organization_id, package_revision_id)
        REFERENCES package_revisions(organization_id, id),
    CONSTRAINT ck_review_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_rejection_reason CHECK (decision <> 'REJECTED' OR length(trim(reason)) >= 3)
);

CREATE INDEX ix_review_decisions_package
    ON review_decisions(organization_id, package_id, created_at DESC, id DESC);

CREATE TABLE question_bank_items (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    package_revision_id UUID NOT NULL,
    source_item_id VARCHAR(120) NOT NULL,
    question VARCHAR(1000) NOT NULL,
    options_json JSONB NOT NULL,
    correct_answer_index INTEGER NOT NULL,
    explanation VARCHAR(2000) NOT NULL,
    source_timestamp_seconds BIGINT NOT NULL,
    tags TEXT[] NOT NULL DEFAULT '{}',
    difficulty VARCHAR(16) NOT NULL DEFAULT 'INTERMEDIATE',
    validation_state VARCHAR(24) NOT NULL DEFAULT 'HUMAN_VERIFIED',
    usage_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_question_bank_revision FOREIGN KEY (organization_id, package_revision_id)
        REFERENCES package_revisions(organization_id, id),
    CONSTRAINT ck_question_bank_answer CHECK (correct_answer_index BETWEEN 0 AND 3),
    CONSTRAINT ck_question_bank_timestamp CHECK (source_timestamp_seconds >= 0),
    CONSTRAINT ck_question_bank_difficulty CHECK (difficulty IN ('BEGINNER','INTERMEDIATE','ADVANCED')),
    CONSTRAINT ck_question_bank_validation CHECK (validation_state IN ('HUMAN_VERIFIED','RETIRED')),
    CONSTRAINT ux_question_bank_source UNIQUE (package_revision_id, source_item_id),
    CONSTRAINT ux_question_bank_scope UNIQUE (organization_id, id)
);

CREATE INDEX ix_question_bank_org
    ON question_bank_items(organization_id, validation_state, created_at DESC, id DESC);

ALTER TABLE quality_feedback
    ADD COLUMN resolved_by UUID REFERENCES users(id),
    ADD COLUMN resolution_note VARCHAR(1000),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
