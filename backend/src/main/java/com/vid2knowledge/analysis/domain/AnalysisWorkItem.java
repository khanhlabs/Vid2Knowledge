package com.vid2knowledge.analysis.domain;

public record AnalysisWorkItem(
        AnalysisJob job,
        AnalysisSource source,
        String outputProfileJson,
        String correlationId,
        long billedUnits
) {
}
