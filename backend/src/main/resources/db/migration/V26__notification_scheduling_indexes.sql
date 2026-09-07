CREATE INDEX ix_assignments_notification_availability
    ON assignments(published_at, available_at, id)
    WHERE state = 'PUBLISHED';

CREATE INDEX ix_assignments_notification_deadline
    ON assignments(due_at, id)
    WHERE state = 'PUBLISHED' AND due_at IS NOT NULL;

CREATE INDEX ix_flashcard_notification_due
    ON flashcard_memory_states(due_at, organization_id, user_id, assignment_id);
