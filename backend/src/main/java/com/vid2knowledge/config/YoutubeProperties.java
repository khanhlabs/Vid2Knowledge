package com.vid2knowledge.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "youtube")
public record YoutubeProperties(
        String apiKey,
        @NotNull URI baseUrl,
        @NotNull Duration timeout,
        @Positive long maxDurationSeconds
) {
}
