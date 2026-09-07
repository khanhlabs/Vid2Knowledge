package com.vid2knowledge.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "object-storage")
public record ObjectStorageProperties(
        boolean enabled,
        URI endpoint,
        String bucket,
        String accessKeyId,
        String secretAccessKey,
        long maxUploadBytes,
        long maxVideoDurationSeconds,
        Duration presignDuration
) {
    @PostConstruct
    void validate() {
        if (!enabled) return;
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalStateException("OBJECT_STORAGE_ENDPOINT must be HTTPS when storage is enabled");
        }
        if (blank(bucket) || blank(accessKeyId) || blank(secretAccessKey)) {
            throw new IllegalStateException("R2 bucket and credentials are required when storage is enabled");
        }
        if (maxUploadBytes < 1_024 || maxUploadBytes > 2_000_000_000L
                || maxVideoDurationSeconds < 60 || maxVideoDurationSeconds > 86_400
                || presignDuration == null || presignDuration.isNegative() || presignDuration.isZero()
                || presignDuration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("Object storage size/expiry limits are invalid");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
