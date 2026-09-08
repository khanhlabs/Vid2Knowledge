package com.vid2knowledge.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;

@Validated
@ConfigurationProperties(prefix = "notifications")
public record NotificationProperties(
        boolean enabled,
        String apiKey,
        String from,
        @NotNull URI resendBaseUrl,
        URI frontendBaseUrl,
        @Email String salesAlertRecipient,
        String encryptionKey,
        @Min(1) @Max(100) int batchSize,
        @Min(1) @Max(20) int maxAttempts,
        @NotNull Duration leaseDuration
) {
    public NotificationProperties {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Notification lease duration must be positive");
        }
        if (enabled) {
            if (blank(apiKey) || blank(from) || frontendBaseUrl == null || blank(salesAlertRecipient)
                    || blank(encryptionKey)) {
                throw new IllegalArgumentException(
                        "Notification credentials, sender, frontend URL, sales recipient and encryption key are required"
                );
            }
            try {
                if (Base64.getDecoder().decode(encryptionKey).length != 32) {
                    throw new IllegalArgumentException("Notification encryption key must decode to 32 bytes");
                }
            } catch (IllegalArgumentException invalidKey) {
                throw new IllegalArgumentException("Notification encryption key must be base64-encoded 32 bytes", invalidKey);
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
