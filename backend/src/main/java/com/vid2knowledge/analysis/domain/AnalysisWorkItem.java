package com.vid2knowledge.analysis.domain;

public record AnalysisWorkItem(
        AnalysisJob job,
        String sourceUri,
        String outputProfileJson,
        String correlationId,
        long billedUnits
) {
}
