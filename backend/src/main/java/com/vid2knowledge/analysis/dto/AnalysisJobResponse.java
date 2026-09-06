package com.vid2knowledge.analysis.dto;

import com.vid2knowledge.analysis.domain.AnalysisJob;

import java.time.Instant;
import java.util.UUID;

public record AnalysisJobResponse(
        UUID id,
        UUID sourceId,
        String state,
        int attempt,
        Instant queuedAt
) {
    public static AnalysisJobResponse from(AnalysisJob job) {
        return new AnalysisJobResponse(job.id(), job.sourceId(), job.state().name(), job.attempt(), job.queuedAt());
    }
}
