package com.vid2knowledge.analysis.application.port;

public interface VideoMetadataProvider {
    VideoMetadata fetch(String videoId);

    record VideoMetadata(String title, long durationSeconds, String language) {
        public VideoMetadata {
            if (title == null || title.isBlank() || durationSeconds <= 0) {
                throw new IllegalArgumentException("Valid video metadata is required");
            }
        }
    }
}
