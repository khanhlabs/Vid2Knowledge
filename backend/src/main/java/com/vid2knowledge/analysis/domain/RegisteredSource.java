package com.vid2knowledge.analysis.domain;

import java.time.Instant;
import java.util.UUID;

public record RegisteredSource(
        UUID id,
        UUID organizationId,
        String canonicalUri,
        String externalId,
        String title,
        String language,
        long durationSeconds,
        Instant metadataVerifiedAt
) {
}
