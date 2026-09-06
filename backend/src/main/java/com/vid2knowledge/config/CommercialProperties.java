package com.vid2knowledge.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "commercial")
public record CommercialProperties(
        @Min(0) long trialProcessedVideoSeconds,
        @Min(0) long trialQaQueries,
        Duration trialDuration,
        Duration invitationTtl
) {
    public CommercialProperties {
        if (trialDuration == null || trialDuration.isZero() || trialDuration.isNegative()) {
            throw new IllegalArgumentException("Trial duration must be positive");
        }
        if (invitationTtl == null || invitationTtl.isZero() || invitationTtl.isNegative()) {
            throw new IllegalArgumentException("Invitation TTL must be positive");
        }
    }
}
