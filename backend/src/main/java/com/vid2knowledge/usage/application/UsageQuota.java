package com.vid2knowledge.usage.application;

import com.vid2knowledge.usage.domain.UsageMetric;
import com.vid2knowledge.usage.domain.UsageReservation;

import java.time.Duration;
import java.util.UUID;

public interface UsageQuota {

    UsageReservation reserve(
            UUID organizationId,
            UsageMetric metric,
            long units,
            String idempotencyKey,
            Duration ttl,
            String correlationId
    );

    UsageReservation commit(UUID reservationId, long actualUnits, String correlationId);

    UsageReservation release(UUID reservationId, String correlationId);
}
