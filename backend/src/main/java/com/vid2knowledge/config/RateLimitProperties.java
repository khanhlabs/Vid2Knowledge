package com.vid2knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int generalMutations,
        int expensiveMutations,
        int paymentMutations,
        int leadSubmissions,
        Duration window,
        int maxKeys
) {
    public RateLimitProperties {
        if (generalMutations < 1 || expensiveMutations < 1 || paymentMutations < 1 || leadSubmissions < 1) {
            throw new IllegalArgumentException("Rate limits must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit window must be positive");
        }
        if (maxKeys < 1000) {
            throw new IllegalArgumentException("Rate-limit maxKeys must be at least 1000");
        }
    }
}
