package com.vid2knowledge.analysis.application.port;

import com.vid2knowledge.analysis.domain.AnalysisJob;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobStore {

    void requireActiveRights(UUID organizationId, UUID sourceId);

    Optional<AnalysisJob> findByIdempotencyKey(UUID organizationId, String idempotencyKey);

    AnalysisJob create(
            UUID organizationId,
            UUID sourceId,
            UUID usageReservationId,
            String outputProfileJson,
            String requestFingerprint,
            String idempotencyKey,
            String provider,
            String model,
            String correlationId,
            Instant now
    );

    Optional<AnalysisJob> claim(UUID jobId, String workerId, Duration leaseDuration, Instant now);
}
