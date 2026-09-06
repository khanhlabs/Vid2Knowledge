package com.vid2knowledge.analysis.dto;

import com.vid2knowledge.analysis.application.RegisterYoutubeSourceService.RightsBasis;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterYoutubeSourceRequest(
        @NotBlank String youtubeUrl,
        @NotNull RightsBasis rightsBasis,
        @AssertTrue boolean termsAccepted
) {
}
