package com.vid2knowledge.analysis.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record LearningPackage(
        @NotBlank String schemaVersion,
        @NotNull @Valid Video video,
        @NotNull @Valid Summary summary,
        @Size(min = 1, max = 20) List<@NotNull @Valid EvidenceItem> keyTakeaways,
        @Size(min = 10, max = 20) List<@NotNull @Valid Flashcard> flashcards,
        @Size(min = 5, max = 10) List<@NotNull @Valid QuizQuestion> quiz
) {

    public record Video(
            String youtubeUrl,
            @Pattern(regexp = "^[A-Za-z0-9_-]{11}$") String videoId,
            @NotBlank @Pattern(regexp = "^(YOUTUBE|UPLOAD)$") String sourceType,
            @NotBlank @Size(max = 80) String sourceId,
            @NotBlank @Size(max = 300) String title,
            @NotBlank @Size(max = 16) String language
    ) {
        public Video(String youtubeUrl, String videoId, String title, String language) {
            this(youtubeUrl, videoId, "YOUTUBE", videoId, title, language);
        }
    }

    public record SourceReference(
            @Min(0) long timestampSeconds,
            @NotBlank @Size(max = 500) String evidence
    ) {
    }

    public record EvidenceItem(
            @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$") String id,
            @NotBlank @Size(max = 1000) String text,
            @NotNull @Valid SourceReference source
    ) {
    }

    public record Summary(
            @NotBlank @Size(max = 5000) String overview,
            @Size(min = 1, max = 30) List<@NotNull @Valid Section> sections
    ) {
    }

    public record Section(
            @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$") String id,
            @NotBlank @Size(max = 300) String title,
            @NotNull @Valid SourceReference source,
            @Size(min = 1, max = 20) List<@NotBlank @Size(max = 2000) String> content
    ) {
    }

    public record Flashcard(
            @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$") String id,
            @NotBlank @Size(max = 1000) String question,
            @NotBlank @Size(max = 2000) String answer,
            @NotNull @Valid SourceReference source
    ) {
    }

    public record QuizQuestion(
            @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$") String id,
            @NotBlank @Size(max = 1000) String question,
            @Size(min = 4, max = 4) List<@NotBlank @Size(max = 1000) String> options,
            @NotNull @Min(0) @Max(3) Integer correctAnswerIndex,
            @NotBlank @Size(max = 2000) String explanation,
            @NotNull @Valid SourceReference source
    ) {
    }

}
