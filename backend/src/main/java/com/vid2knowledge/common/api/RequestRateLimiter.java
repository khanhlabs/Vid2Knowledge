package com.vid2knowledge.common.api;

import com.vid2knowledge.config.RateLimitProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class RequestRateLimiter {
    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();

    @Autowired
    public RequestRateLimiter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RequestRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Decision consume(String key, int limit) {
        if (!properties.enabled()) {
            return new Decision(true, limit, limit, 0);
        }
        Instant now = clock.instant();
        if (windows.size() >= properties.maxKeys() && !windows.containsKey(key)) {
            windows.entrySet().removeIf(entry -> !entry.getValue().resetAt().isAfter(now));
            if (windows.size() >= properties.maxKeys()) {
                return new Decision(false, limit, 0, Math.max(1, properties.window().toSeconds()));
            }
        }
        Window current = windows.compute(key, (ignored, existing) -> {
            if (existing == null || !existing.resetAt().isAfter(now)) {
                return new Window(1, now.plus(properties.window()));
            }
            return new Window(existing.count() + 1, existing.resetAt());
        });
        if ((requestCount.incrementAndGet() & 1023) == 0) {
            windows.entrySet().removeIf(entry -> !entry.getValue().resetAt().isAfter(now));
        }
        boolean allowed = current.count() <= limit;
        long retryAfter = allowed ? 0 : Math.max(1, current.resetAt().getEpochSecond() - now.getEpochSecond());
        return new Decision(allowed, limit, Math.max(0, limit - current.count()), retryAfter);
    }

    public RateLimitProperties properties() {
        return properties;
    }

    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {
    }

    private record Window(int count, Instant resetAt) {
    }
}
