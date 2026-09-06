package com.vid2knowledge.config;

import com.vid2knowledge.analysis.domain.AiCostRateCard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "ai-cost")
public record AiCostProperties(
        @NotNull @Valid Rate actual,
        @NotNull @Valid Rate shadow,
        @Min(1) int maxAttempts,
        @NotNull Duration leaseDuration
) {
    public AiCostProperties {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("AI job lease duration must be positive");
        }
    }
    public record Rate(
            @PositiveOrZero long inputMicrousdPerMillionTokens,
            @PositiveOrZero long outputMicrousdPerMillionTokens,
            @PositiveOrZero long thoughtMicrousdPerMillionTokens
    ) {
        public AiCostRateCard toRateCard() {
            return new AiCostRateCard(
                    inputMicrousdPerMillionTokens,
                    outputMicrousdPerMillionTokens,
                    thoughtMicrousdPerMillionTokens
            );
        }
    }
}
