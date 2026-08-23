package com.vid2knowledge.analysis.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record LearningPackage(
        @NotNull @Valid Video video,
        @NotNull @Valid Summary summary,
        @Size(min = 1) List<@NotBlank String> keyTakeaways,
        @Size(min = 10, max = 20) List<@NotNull @Valid Flashcard> flashcards,
        @Size(min = 5, max = 10) List<@NotNull @Valid QuizQuestion> quiz
) {

    public record Video(
            @NotBlank String youtubeUrl,
            @NotBlank String title,
            @NotBlank String language
    ) {
    }

    public record Summary(
            @NotBlank String overview,
            @Size(min = 1) List<@NotNull @Valid Section> sections
    ) {
    }

    public record Section(
            @NotBlank String title,
            String timestamp,
            @Size(min = 1) List<@NotBlank String> content
    ) {
    }

    public record Flashcard(
            @NotBlank String question,
            @NotBlank String answer,
            String timestamp
    ) {
    }

    public record QuizQuestion(
            @NotBlank String question,
            @Size(min = 4, max = 4) List<@NotBlank String> options,
            @NotNull @Min(0) @Max(3) Integer correctAnswerIndex,
            @NotBlank String explanation,
            String timestamp
    ) {
    }

}
