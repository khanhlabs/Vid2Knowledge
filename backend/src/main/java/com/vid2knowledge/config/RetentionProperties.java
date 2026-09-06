package com.vid2knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "retention")
public record RetentionProperties(
        Duration generationPayload,
        Duration paymentWebhookPayload,
        Duration terminalOutbox,
        Duration terminalNotification,
        Duration terminalInvitation
) {
    public RetentionProperties {
        requirePositive(generationPayload, "generation payload");
        requirePositive(paymentWebhookPayload, "payment webhook payload");
        requirePositive(terminalOutbox, "terminal outbox");
        requirePositive(terminalNotification, "terminal notification");
        requirePositive(terminalInvitation, "terminal invitation");
    }

    private static void requirePositive(Duration value, String label) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " retention must be positive");
        }
    }
}
