package com.vid2knowledge.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 80)
        @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
        @Pattern(regexp = "^(DIRECT|SAMPLE_COURSE)$") String acquisitionSource
) {
}
