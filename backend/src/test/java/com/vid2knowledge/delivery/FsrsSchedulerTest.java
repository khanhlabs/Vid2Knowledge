package com.vid2knowledge.delivery;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FsrsSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-09-06T04:00:00Z");

    @Test
    void initialRatingsProduceOrderedFsrsSixStability() {
        var again = FsrsScheduler.schedule(null, FsrsScheduler.Rating.AGAIN, NOW);
        var hard = FsrsScheduler.schedule(null, FsrsScheduler.Rating.HARD, NOW);
        var good = FsrsScheduler.schedule(null, FsrsScheduler.Rating.GOOD, NOW);
        var easy = FsrsScheduler.schedule(null, FsrsScheduler.Rating.EASY, NOW);

        assertThat(again.stabilityDays()).isEqualTo(0.212);
        assertThat(hard.stabilityDays()).isEqualTo(1.2931);
        assertThat(good.stabilityDays()).isEqualTo(2.3065);
        assertThat(easy.stabilityDays()).isEqualTo(8.2956);
        assertThat(again.dueAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(hard.dueAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(good.dueAt()).isAfter(hard.dueAt());
        assertThat(easy.dueAt()).isAfter(good.dueAt());
    }

    @Test
    void delayedRecallUsesRetrievabilityAndForgettingCreatesALapse() {
        var initial = FsrsScheduler.schedule(null, FsrsScheduler.Rating.GOOD, NOW);
        var state = new FsrsScheduler.MemoryState(
                initial.stabilityDays(), initial.difficulty(), initial.dueAt(), NOW,
                initial.state(), initial.reviewCount(), initial.lapseCount()
        );
        Instant delayed = NOW.plus(Duration.ofDays(7));

        var recalled = FsrsScheduler.schedule(state, FsrsScheduler.Rating.GOOD, delayed);
        var forgotten = FsrsScheduler.schedule(state, FsrsScheduler.Rating.AGAIN, delayed);

        assertThat(recalled.retrievability()).isBetween(0.0, 0.9);
        assertThat(recalled.stabilityDays()).isGreaterThan(state.stabilityDays());
        assertThat(forgotten.stabilityDays()).isLessThan(state.stabilityDays());
        assertThat(forgotten.state()).isEqualTo("RELEARNING");
        assertThat(forgotten.lapseCount()).isEqualTo(1);
    }

    @Test
    void forgettingCurveDefinesStabilityAtNinetyPercentRecall() {
        assertThat(FsrsScheduler.forgettingCurve(12.5, 12.5)).isCloseTo(0.9,
                org.assertj.core.data.Offset.offset(0.0000001));
    }
}
