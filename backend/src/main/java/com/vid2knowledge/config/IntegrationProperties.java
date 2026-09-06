package com.vid2knowledge.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "integrations")
public record IntegrationProperties(
        boolean enabled,
        String encryptionKey,
        int dispatchBatchSize,
        int maxAttempts,
        Duration leaseDuration,
        Duration requestTimeout
) {
    @PostConstruct
    void validate() {
        if (!enabled) return;
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException("INTEGRATION_ENCRYPTION_KEY is required when integrations are enabled");
        }
        if (dispatchBatchSize <= 0 || maxAttempts <= 0 || leaseDuration.isZero()
                || leaseDuration.isNegative() || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalStateException("Integration delivery limits must be positive");
        }
    }
}
