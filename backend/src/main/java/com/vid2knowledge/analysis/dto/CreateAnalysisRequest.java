package com.vid2knowledge.analysis.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record CreateAnalysisRequest(
        @NotNull UUID sourceId,
        JsonNode outputProfile,
        UUID templateId
) {
}
