package com.vid2knowledge.analysis.domain;

import com.vid2knowledge.analysis.application.port.AiGenerationResult;

public record GenerationAccounting(
        AiGenerationResult generation,
        long actualCostMicrousd,
        long shadowCostMicrousd
) {
    public GenerationAccounting {
        if (generation == null || actualCostMicrousd < 0 || shadowCostMicrousd < 0) {
            throw new IllegalArgumentException("Generation and non-negative costs are required");
        }
    }
}
