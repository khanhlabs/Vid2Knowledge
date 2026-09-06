package com.vid2knowledge.analysis.domain;

import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiCostRateCardTest {

    @Test
    void estimatesCostInIntegerMicroUsdAndRoundsUp() {
        var rateCard = new AiCostRateCard(100_000, 400_000, 200_000);
        var generation = new AiGenerationResult(
                "provider", "model", "version", 1_000, 500, 250, 900, 0, "{}"
        );

        assertThat(rateCard.estimateMicrousd(generation)).isEqualTo(350);
    }

    @Test
    void usesBigIntegerToRejectOverflowInsteadOfSilentlyWrapping() {
        var rateCard = new AiCostRateCard(Long.MAX_VALUE, 0, 0);
        var generation = new AiGenerationResult(
                "provider", "model", "version", Long.MAX_VALUE, 0, 0, 0, 0, "{}"
        );

        assertThatThrownBy(() -> rateCard.estimateMicrousd(generation))
                .isInstanceOf(ArithmeticException.class);
    }
}
