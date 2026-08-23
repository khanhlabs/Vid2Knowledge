package com.vid2knowledge.analysis.dto;

import jakarta.validation.constraints.NotBlank;

public record AnalysisPreviewRequest(
        @NotBlank(message = "YouTube URL is required")
        String youtubeUrl
) {
}
