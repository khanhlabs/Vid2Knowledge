package com.vid2knowledge.common.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxStore {

    List<OutboxEvent> claim(String workerId, int limit, Duration leaseDuration, Instant now);

    boolean markPublished(UUID eventId, String workerId, Instant publishedAt);

    boolean markFailed(
            UUID eventId,
            String workerId,
            String error,
            Instant retryAt,
            boolean deadLetter,
            Instant now
    );
}
