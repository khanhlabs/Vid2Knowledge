package com.vid2knowledge.common.outbox;

import java.time.Clock;
import java.time.Duration;

public class OutboxDispatcher {

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

    private final OutboxStore store;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration leaseDuration;

    public OutboxDispatcher(
            OutboxStore store,
            EventPublisher publisher,
            Clock clock,
            int batchSize,
            int maxAttempts,
            Duration leaseDuration
    ) {
        if (batchSize <= 0 || maxAttempts <= 0 || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Outbox dispatcher limits must be positive");
        }
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.leaseDuration = leaseDuration;
    }

    public DispatchResult dispatch(String workerId) {
        var events = store.claim(workerId, batchSize, leaseDuration, clock.instant());
        int published = 0;
        int failed = 0;
        int deadLettered = 0;

        for (OutboxEvent event : events) {
            try {
                publisher.publish(event);
                if (store.markPublished(event.id(), workerId, clock.instant())) {
                    published++;
                }
            } catch (RuntimeException exception) {
                int nextAttempt = event.attemptCount() + 1;
                boolean deadLetter = nextAttempt >= maxAttempts;
                Duration backoff = backoff(nextAttempt);
                store.markFailed(
                        event.id(),
                        workerId,
                        safeMessage(exception),
                        clock.instant().plus(backoff),
                        deadLetter,
                        clock.instant()
                );
                failed++;
                if (deadLetter) {
                    deadLettered++;
                }
            }
        }
        return new DispatchResult(events.size(), published, failed, deadLettered);
    }

    private static Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(attempt - 1, 10);
        Duration calculated = Duration.ofSeconds(Math.multiplyExact(30, multiplier));
        return calculated.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : calculated;
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.substring(0, Math.min(message.length(), 1000));
    }

    public record DispatchResult(int claimed, int published, int failed, int deadLettered) {
    }
}
