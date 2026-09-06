package com.vid2knowledge.usage.domain;

import java.time.Instant;
import java.util.UUID;

public record UsageReservation(
        UUID id,
        UUID organizationId,
        UUID entitlementId,
        UsageMetric metric,
        long reservedUnits,
        Long committedUnits,
        Status status,
        String idempotencyKey,
        Instant expiresAt
) {
    public enum Status {
        RESERVED,
        COMMITTED,
        RELEASED,
        EXPIRED
    }
}
