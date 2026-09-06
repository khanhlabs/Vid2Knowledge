package com.vid2knowledge.common.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID id,
        UUID organizationId,
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        String correlationId,
        UUID causationId,
        String payloadJson,
        Instant occurredAt,
        int attemptCount
) {
}
