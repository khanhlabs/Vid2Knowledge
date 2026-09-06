package com.vid2knowledge.analysis.dto;

import com.vid2knowledge.analysis.domain.AnalysisJob;

import java.time.Instant;
import java.util.UUID;

public record AnalysisJobResponse(
        UUID id,
        UUID sourceId,
        String state,
        int attempt,
        Instant queuedAt,
        UUID packageId
) {
    public static AnalysisJobResponse from(AnalysisJob job, UUID packageId) {
        return new AnalysisJobResponse(
                job.id(), job.sourceId(), job.state().name(), job.attempt(), job.queuedAt(), packageId
        );
    }
}
