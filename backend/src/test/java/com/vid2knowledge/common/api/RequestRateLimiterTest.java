package com.vid2knowledge.common.api;

import com.vid2knowledge.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RequestRateLimiterTest {
    private final RateLimitProperties properties = new RateLimitProperties(
            true, 10, 2, 3, Duration.ofMinutes(1), 1000
    );

    @Test
    void rejectsOnlyAfterTheConfiguredWindowLimit() {
        var limiter = new RequestRateLimiter(
                properties, Clock.fixed(Instant.parse("2026-09-06T10:00:00Z"), ZoneOffset.UTC)
        );

        assertThat(limiter.consume("account:one|expensive", 2).allowed()).isTrue();
        assertThat(limiter.consume("account:one|expensive", 2).remaining()).isZero();
        var rejected = limiter.consume("account:one|expensive", 2);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(60);
        assertThat(limiter.consume("account:two|expensive", 2).allowed()).isTrue();
    }

    @Test
    void disabledLimiterDoesNotRetainOrRejectRequests() {
        var disabled = new RequestRateLimiter(
                new RateLimitProperties(false, 1, 1, 1, Duration.ofMinutes(1), 1000),
                Clock.systemUTC()
        );

        assertThat(disabled.consume("same", 1).allowed()).isTrue();
        assertThat(disabled.consume("same", 1).allowed()).isTrue();
    }
}
