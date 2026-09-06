package com.vid2knowledge.usage.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageBalanceTest {

    @Test
    void reservesCommitsAndReleasesWithoutLeakingAllowance() {
        UsageBalance balance = new UsageBalance(1_000, 200, 100);

        UsageBalance reserved = balance.reserve(300);
        UsageBalance committed = reserved.commit(300, 240);
        UsageBalance released = committed.reserve(100).release(100);

        assertThat(reserved.available()).isEqualTo(400);
        assertThat(committed).isEqualTo(new UsageBalance(1_000, 440, 100));
        assertThat(released).isEqualTo(committed);
    }

    @Test
    void rejectsReservationWhenQuotaIsInsufficient() {
        UsageBalance balance = new UsageBalance(1_000, 800, 150);

        assertThatThrownBy(() -> balance.reserve(51))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(error -> {
                    QuotaExceededException quotaError = (QuotaExceededException) error;
                    assertThat(quotaError.requested()).isEqualTo(51);
                    assertThat(quotaError.available()).isEqualTo(50);
                });
    }

    @Test
    void preventsCommittingMoreThanTheReservation() {
        UsageBalance balance = new UsageBalance(1_000, 200, 300);

        assertThatThrownBy(() -> balance.commit(300, 301))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
