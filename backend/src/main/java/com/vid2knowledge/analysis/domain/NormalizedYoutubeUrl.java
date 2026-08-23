package com.vid2knowledge.analysis.domain;

public record NormalizedYoutubeUrl(
        String videoId,
        String canonicalUrl
) {
}
