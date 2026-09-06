package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.analysis.domain.AnalysisJob;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import com.vid2knowledge.usage.application.UsageQuota;
import com.vid2knowledge.usage.domain.UsageMetric;
import com.vid2knowledge.usage.domain.UsageReservation;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class RequestAnalysisService {

    private static final Duration RESERVATION_TTL = Duration.ofMinutes(30);

    private final AnalysisJobStore jobs;
    private final UsageQuota quota;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public RequestAnalysisService(AnalysisJobStore jobs, UsageQuota quota, ObjectMapper objectMapper) {
        this(jobs, quota, objectMapper, Clock.systemUTC());
    }

    RequestAnalysisService(AnalysisJobStore jobs, UsageQuota quota, ObjectMapper objectMapper, Clock clock) {
        this.jobs = jobs;
        this.quota = quota;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AnalysisJob request(RequestAnalysisCommand command) {
        String normalizedProfile = normalizeJson(command.outputProfileJson());
        String fingerprint = fingerprint(command, normalizedProfile);

        var existing = jobs.findByIdempotencyKey(command.organizationId(), command.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException();
            }
            return existing.get();
        }

        jobs.requireActiveRights(command.organizationId(), command.sourceId());
        UsageReservation reservation = quota.reserve(
                command.organizationId(),
                UsageMetric.PROCESSED_VIDEO_SECOND,
                command.videoDurationSeconds(),
                "analysis:" + command.idempotencyKey(),
                RESERVATION_TTL,
                command.correlationId()
        );

        return jobs.create(
                command.organizationId(),
                command.sourceId(),
                reservation.id(),
                normalizedProfile,
                fingerprint,
                command.idempotencyKey(),
                command.provider(),
                command.model(),
                command.correlationId(),
                clock.instant()
        );
    }

    private String normalizeJson(String value) {
        return AnalysisOutputProfile.parse(value, objectMapper).normalizedJson(objectMapper);
    }

    private static String fingerprint(RequestAnalysisCommand command, String normalizedProfile) {
        return RequestFingerprint.sha256(String.join("|",
                command.organizationId().toString(),
                command.sourceId().toString(),
                Long.toString(command.videoDurationSeconds()),
                normalizedProfile,
                command.provider(),
                command.model()
        ));
    }
}
