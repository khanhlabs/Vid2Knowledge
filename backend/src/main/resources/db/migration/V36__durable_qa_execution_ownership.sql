ALTER TABLE usage_reservations ADD CONSTRAINT ux_usage_reservation_scope UNIQUE (organization_id, id);
ALTER TABLE cost_ledger ADD CONSTRAINT ux_cost_ledger_scope UNIQUE (organization_id, id);

CREATE TABLE qa_execution_requests (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(160) NOT NULL,
    question_fingerprint VARCHAR(64) NOT NULL,
    usage_reservation_id UUID NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_qa_execution_assignment FOREIGN KEY (organization_id, assignment_id)
        REFERENCES assignments(organization_id, id),
    CONSTRAINT fk_qa_execution_reservation FOREIGN KEY (organization_id, usage_reservation_id)
        REFERENCES usage_reservations(organization_id, id),
    CONSTRAINT ux_qa_execution_scope UNIQUE (organization_id, id),
    CONSTRAINT ux_qa_execution_request UNIQUE (organization_id, assignment_id, user_id, idempotency_key),
    CONSTRAINT ck_qa_execution_state CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'UNCERTAIN')),
    CONSTRAINT ck_qa_execution_completion CHECK ((state = 'RUNNING') = (completed_at IS NULL))
);

CREATE INDEX ix_qa_execution_expired ON qa_execution_requests(expires_at, id) WHERE state = 'RUNNING';

CREATE TABLE qa_provider_calls (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    execution_request_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'STARTED',
    cost_ledger_id UUID UNIQUE,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_qa_call_execution FOREIGN KEY (organization_id, execution_request_id)
        REFERENCES qa_execution_requests(organization_id, id),
    CONSTRAINT fk_qa_call_cost FOREIGN KEY (organization_id, cost_ledger_id)
        REFERENCES cost_ledger(organization_id, id),
    CONSTRAINT ux_qa_call_operation UNIQUE (execution_request_id, operation),
    CONSTRAINT ck_qa_call_operation CHECK (operation IN ('QUERY_EMBEDDING', 'ANSWER')),
    CONSTRAINT ck_qa_call_state CHECK (state IN ('STARTED', 'SUCCEEDED', 'UNKNOWN')),
    CONSTRAINT ck_qa_call_cost CHECK ((state = 'SUCCEEDED') = (cost_ledger_id IS NOT NULL)),
    CONSTRAINT ck_qa_call_completion CHECK ((state = 'STARTED') = (completed_at IS NULL))
);

CREATE INDEX ix_qa_provider_calls_unknown ON qa_provider_calls(organization_id, started_at)
    WHERE state = 'UNKNOWN';
