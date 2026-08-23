package com.vid2knowledge.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "gemini")
public record GeminiProperties(
        @NotBlank String apiKey,
        @NotBlank String model,
        @NotNull URI baseUrl,
        @NotNull Duration timeout
) {
}
