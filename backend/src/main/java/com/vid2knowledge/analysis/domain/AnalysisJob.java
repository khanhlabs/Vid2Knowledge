package com.vid2knowledge.analysis.domain;

import java.time.Instant;
import java.util.UUID;

public record AnalysisJob(
        UUID id,
        UUID organizationId,
        UUID sourceId,
        UUID usageReservationId,
        State state,
        String requestFingerprint,
        String idempotencyKey,
        String provider,
        String model,
        int attempt,
        Instant queuedAt
) {
    public enum State {
        QUEUED,
        PROCESSING,
        VALIDATING,
        RETRY_SCHEDULED,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
