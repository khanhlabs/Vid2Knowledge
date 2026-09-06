CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE pricing_plans ADD COLUMN qa_queries BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pricing_plans ADD CONSTRAINT ck_pricing_plan_qa_queries CHECK (qa_queries >= 0);

UPDATE pricing_plans SET qa_queries = CASE code
    WHEN 'CREATOR_MONTHLY' THEN 1000
    WHEN 'CREATOR_ANNUAL' THEN 12000
    WHEN 'TRAINING_TEAM_MONTHLY' THEN 5000
    WHEN 'TRAINING_TEAM_ANNUAL' THEN 60000
    ELSE 0
END;

CREATE TABLE embedding_chunks (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    package_revision_id UUID NOT NULL,
    item_type VARCHAR(24) NOT NULL,
    item_id VARCHAR(120) NOT NULL,
    content TEXT NOT NULL,
    source_timestamp_seconds BIGINT NOT NULL,
    source_evidence VARCHAR(1000) NOT NULL,
    embedding vector(768) NOT NULL,
    embedding_model VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_embedding_revision FOREIGN KEY (organization_id, package_revision_id)
        REFERENCES package_revisions(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_embedding_item_type CHECK (item_type IN ('SECTION', 'TAKEAWAY', 'FLASHCARD', 'QUIZ')),
    CONSTRAINT ck_embedding_timestamp CHECK (source_timestamp_seconds >= 0),
    CONSTRAINT ux_embedding_chunk_item UNIQUE (package_revision_id, item_type, item_id, embedding_model)
);

CREATE INDEX ix_embedding_chunks_revision
    ON embedding_chunks(organization_id, package_revision_id, item_type, item_id);
CREATE INDEX ix_embedding_chunks_vector_hnsw
    ON embedding_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE qa_threads (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(160) NOT NULL,
    title VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_qa_thread_progress FOREIGN KEY (assignment_id, user_id)
        REFERENCES learner_progress(assignment_id, user_id) ON DELETE CASCADE,
    CONSTRAINT ux_qa_thread_scope UNIQUE (organization_id, id),
    CONSTRAINT ux_qa_thread_idempotency UNIQUE (assignment_id, user_id, idempotency_key)
);

CREATE TABLE qa_messages (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    role VARCHAR(12) NOT NULL,
    content TEXT NOT NULL,
    idempotency_key VARCHAR(160),
    insufficient_evidence BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_qa_message_thread FOREIGN KEY (organization_id, thread_id)
        REFERENCES qa_threads(organization_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_qa_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ux_qa_message_idempotency UNIQUE (thread_id, idempotency_key),
    CONSTRAINT ck_qa_idempotency_role CHECK (
        (role = 'USER' AND idempotency_key IS NOT NULL)
        OR (role = 'ASSISTANT' AND idempotency_key IS NULL)
    )
);

CREATE INDEX ix_qa_messages_thread_time ON qa_messages(thread_id, created_at, id);

CREATE TABLE qa_citations (
    message_id UUID NOT NULL REFERENCES qa_messages(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    embedding_chunk_id UUID NOT NULL REFERENCES embedding_chunks(id),
    PRIMARY KEY (message_id, position),
    CONSTRAINT ck_qa_citation_position CHECK (position > 0),
    CONSTRAINT ux_qa_citation_chunk UNIQUE (message_id, embedding_chunk_id)
);

CREATE TABLE qa_query_runs (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    thread_id UUID NOT NULL,
    question_message_id UUID NOT NULL REFERENCES qa_messages(id),
    answer_message_id UUID REFERENCES qa_messages(id),
    usage_reservation_id UUID NOT NULL REFERENCES usage_reservations(id),
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(80) NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    latency_ms BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_qa_run_thread FOREIGN KEY (organization_id, thread_id)
        REFERENCES qa_threads(organization_id, id),
    CONSTRAINT ck_qa_run_usage CHECK (input_tokens >= 0 AND output_tokens >= 0 AND latency_ms >= 0),
    CONSTRAINT ck_qa_run_status CHECK (status IN ('ANSWERED', 'REFUSED', 'FAILED'))
);

CREATE INDEX ix_qa_runs_org_time ON qa_query_runs(organization_id, created_at DESC, id DESC);
