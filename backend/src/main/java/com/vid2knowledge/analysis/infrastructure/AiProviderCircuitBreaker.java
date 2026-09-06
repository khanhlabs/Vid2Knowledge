package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.AiProviderException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AiProviderCircuitBreaker {
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilEpochMilli = new AtomicLong();

    public AiProviderCircuitBreaker() {
        this(5, Duration.ofSeconds(30), Clock.systemUTC());
    }

    AiProviderCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    public void beforeCall() {
        long openUntil = openUntilEpochMilli.get();
        if (openUntil > clock.millis()) {
            throw new AiProviderException("AI provider circuit is temporarily open", true);
        }
        if (openUntil != 0 && openUntilEpochMilli.compareAndSet(openUntil, 0)) {
            consecutiveFailures.set(0);
        }
    }

    public void success() {
        consecutiveFailures.set(0);
        openUntilEpochMilli.set(0);
    }

    public void transientFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilEpochMilli.set(clock.millis() + openDuration.toMillis());
        }
    }
}
