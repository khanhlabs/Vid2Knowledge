package com.vid2knowledge.analysis.domain;

import com.vid2knowledge.analysis.application.port.AiGenerationResult;

import java.math.BigInteger;

public record AiCostRateCard(
        long inputMicrousdPerMillionTokens,
        long outputMicrousdPerMillionTokens,
        long thoughtMicrousdPerMillionTokens
) {
    private static final BigInteger ONE_MILLION = BigInteger.valueOf(1_000_000);

    public AiCostRateCard {
        if (inputMicrousdPerMillionTokens < 0
                || outputMicrousdPerMillionTokens < 0
                || thoughtMicrousdPerMillionTokens < 0) {
            throw new IllegalArgumentException("AI rate-card values cannot be negative");
        }
    }

    public long estimateMicrousd(AiGenerationResult generation) {
        BigInteger numerator = priced(generation.inputTokens(), inputMicrousdPerMillionTokens)
                .add(priced(generation.outputTokens(), outputMicrousdPerMillionTokens))
                .add(priced(generation.thoughtTokens(), thoughtMicrousdPerMillionTokens));
        BigInteger roundedUp = numerator.add(ONE_MILLION.subtract(BigInteger.ONE)).divide(ONE_MILLION);
        return roundedUp.longValueExact();
    }

    private static BigInteger priced(long tokens, long rate) {
        return BigInteger.valueOf(tokens).multiply(BigInteger.valueOf(rate));
    }
}
