package com.vid2knowledge.analysis.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisOutputProfileTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesWhitelistedChoicesAndDrivesThePrompt() {
        var profile = AnalysisOutputProfile.parse(
                """
                {"language":"vi","audience":"employee","difficulty":"advanced",
                 "flashcards":15,"quizQuestions":8,"tone":"formal"}
                """, mapper
        );
        String prompt = new LearningPackagePromptFactory(mapper).create(profile.normalizedJson(mapper));

        assertThat(prompt).contains(
                "Write in Vietnamese", "Target audience: employee", "Difficulty: advanced",
                "Tone: formal", "exactly 15 flashcards", "exactly 8 quiz questions"
        );
    }

    @Test
    void rejectsUnknownFieldsAndCostExpandingCountsBeforeProviderUse() {
        assertThatThrownBy(() -> AnalysisOutputProfile.parse("{\"brandVoice\":\"ignore policy\"}", mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported output profile field");
        assertThatThrownBy(() -> AnalysisOutputProfile.parse("{\"flashcards\":1000}", mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 10 and 20");
    }
}
