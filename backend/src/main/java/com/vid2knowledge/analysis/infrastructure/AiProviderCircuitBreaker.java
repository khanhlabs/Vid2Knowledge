package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.AiProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AiProviderCircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(AiProviderCircuitBreaker.class);
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
            log.info("AI_PROVIDER_CIRCUIT_HALF_OPEN provider=gemini");
        }
    }

    public void success() {
        boolean wasOpen = openUntilEpochMilli.getAndSet(0) != 0;
        consecutiveFailures.set(0);
        if (wasOpen) {
            log.info("AI_PROVIDER_CIRCUIT_CLOSED provider=gemini");
        }
    }

    public void transientFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            long newOpenUntil = clock.millis() + openDuration.toMillis();
            if (openUntilEpochMilli.getAndSet(newOpenUntil) == 0) {
                log.warn("AI_PROVIDER_CIRCUIT_OPEN provider=gemini consecutiveFailures={} openDurationMs={}",
                        failures, openDuration.toMillis());
            }
        }
    }
}
