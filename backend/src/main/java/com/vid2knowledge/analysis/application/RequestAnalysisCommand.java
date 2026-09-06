package com.vid2knowledge.analysis.application;

import java.util.UUID;

public record RequestAnalysisCommand(
        UUID organizationId,
        UUID sourceId,
        long videoDurationSeconds,
        String outputProfileJson,
        String idempotencyKey,
        String provider,
        String model,
        String correlationId
) {
    public RequestAnalysisCommand {
        if (organizationId == null || sourceId == null) {
            throw new IllegalArgumentException("Organization and source are required");
        }
        if (videoDurationSeconds <= 0) {
            throw new IllegalArgumentException("Video duration must be positive");
        }
        requireText(outputProfileJson, "Output profile is required");
        requireText(idempotencyKey, "Idempotency key is required");
        requireText(provider, "Provider is required");
        requireText(model, "Model is required");
        requireText(correlationId, "Correlation ID is required");
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
