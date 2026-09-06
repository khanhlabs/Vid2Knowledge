package com.vid2knowledge.common.outbox;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-06T02:00:00Z");

    @Test
    void marksSuccessfulEventAsPublished() {
        FakeStore store = new FakeStore(event(0));
        var dispatcher = new OutboxDispatcher(
                store, ignored -> { }, Clock.fixed(NOW, ZoneOffset.UTC), 10, 3, Duration.ofMinutes(2)
        );

        var result = dispatcher.dispatch("worker-1");

        assertThat(result).isEqualTo(new OutboxDispatcher.DispatchResult(1, 1, 0, 0));
        assertThat(store.published).hasSize(1);
    }

    @Test
    void retriesWithBackoffThenDeadLettersAtTheAttemptLimit() {
        FakeStore retryStore = new FakeStore(event(0));
        FakeStore deadStore = new FakeStore(event(2));
        EventPublisher failing = ignored -> { throw new IllegalStateException("provider unavailable"); };

        var retryResult = new OutboxDispatcher(
                retryStore, failing, Clock.fixed(NOW, ZoneOffset.UTC), 10, 3, Duration.ofMinutes(2)
        ).dispatch("worker-1");
        var deadResult = new OutboxDispatcher(
                deadStore, failing, Clock.fixed(NOW, ZoneOffset.UTC), 10, 3, Duration.ofMinutes(2)
        ).dispatch("worker-2");

        assertThat(retryResult.failed()).isEqualTo(1);
        assertThat(retryStore.retryAt).isEqualTo(NOW.plusSeconds(30));
        assertThat(retryStore.deadLetter).isFalse();
        assertThat(deadResult.deadLettered()).isEqualTo(1);
        assertThat(deadStore.deadLetter).isTrue();
    }

    private static OutboxEvent event(int attempts) {
        return new OutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), "AnalysisRequested", 1,
                "AnalysisJob", UUID.randomUUID(), "correlation", null, "{}", NOW, attempts
        );
    }

    private static final class FakeStore implements OutboxStore {
        private final OutboxEvent event;
        private final List<UUID> published = new ArrayList<>();
        private Instant retryAt;
        private boolean deadLetter;

        private FakeStore(OutboxEvent event) {
            this.event = event;
        }

        @Override
        public List<OutboxEvent> claim(String workerId, int limit, Duration leaseDuration, Instant now) {
            return List.of(event);
        }

        @Override
        public boolean markPublished(UUID eventId, String workerId, Instant publishedAt) {
            published.add(eventId);
            return true;
        }

        @Override
        public boolean markFailed(UUID eventId, String workerId, String error, Instant retryAt, boolean deadLetter, Instant now) {
            this.retryAt = retryAt;
            this.deadLetter = deadLetter;
            return true;
        }
    }
}
