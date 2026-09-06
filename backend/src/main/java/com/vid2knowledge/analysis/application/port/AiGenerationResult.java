package com.vid2knowledge.analysis.application.port;

public record AiGenerationResult(
        String provider,
        String model,
        String modelVersion,
        long inputTokens,
        long outputTokens,
        long thoughtTokens,
        long latencyMs,
        int retryCount,
        String output
) {
    public AiGenerationResult {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("AI provider and model are required");
        }
        if (inputTokens < 0 || outputTokens < 0 || thoughtTokens < 0 || latencyMs < 0 || retryCount < 0) {
            throw new IllegalArgumentException("AI usage metadata cannot be negative");
        }
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("AI output is required");
        }
    }
}
