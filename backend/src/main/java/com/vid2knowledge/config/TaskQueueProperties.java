package com.vid2knowledge.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "task-queue")
public record TaskQueueProperties(
        @NotNull Mode mode,
        String project,
        String location,
        String analysisQueue,
        URI workerBaseUrl,
        String serviceAccountEmail,
        String oidcAudience,
        @NotNull Duration dispatchInterval,
        @Min(1) int dispatchBatchSize,
        @Min(1) int outboxMaxAttempts
) {
    public TaskQueueProperties {
        if (dispatchInterval.isZero() || dispatchInterval.isNegative()) {
            throw new IllegalArgumentException("Task dispatch interval must be positive");
        }
        if (mode == Mode.CLOUD_TASKS) {
            require(project, "GCP project");
            require(location, "GCP location");
            require(analysisQueue, "analysis queue");
            if (workerBaseUrl == null) {
                throw new IllegalArgumentException("Worker base URL is required for Cloud Tasks");
            }
            require(serviceAccountEmail, "task service account email");
            require(oidcAudience, "task OIDC audience");
        }
    }

    private static void require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required for Cloud Tasks");
        }
    }

    public enum Mode {
        INLINE,
        CLOUD_TASKS
    }
}
