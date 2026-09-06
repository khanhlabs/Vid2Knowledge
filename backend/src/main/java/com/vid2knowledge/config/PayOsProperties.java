package com.vid2knowledge.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "payos")
public record PayOsProperties(
        boolean enabled,
        String clientId,
        String apiKey,
        String checksumKey,
        @NotNull URI baseUrl,
        URI returnUrl,
        URI cancelUrl,
        @NotNull Duration checkoutTtl
) {
    public PayOsProperties {
        if (checkoutTtl.isZero() || checkoutTtl.isNegative()) {
            throw new IllegalArgumentException("Checkout TTL must be positive");
        }
        if (enabled && (blank(clientId) || blank(apiKey) || blank(checksumKey)
                || returnUrl == null || cancelUrl == null)) {
            throw new IllegalArgumentException("All payOS credentials and redirect URLs are required when enabled");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
