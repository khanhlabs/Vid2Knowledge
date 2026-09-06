package com.vid2knowledge.analysis.domain;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LearningPackageValidationTest {

    private final Validator validator;

    LearningPackageValidationTest(){
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = (Validator) factory.getValidator();
    }

    @Test
    void acceptsPackageWithAtLeastTenFlashcardsAndFiveQuizQuestions() {
        var violations = validator.validate(validPackage(10, 5));

        assertTrue(violations.isEmpty(), violations::toString);
    }

    @Test
    void rejectsPackageWithFewerThanTenFlashcards() {
        var violations = validator.validate(validPackage(9, 5));

        assertTrue(
                violations.stream()
                        .anyMatch(violation ->
                                violation.getPropertyPath().toString()
                                        .startsWith("flashcards"))
        );
    }

    @Test
    void rejectsPackageWithFewerThanFiveQuizQuestions() {
        var violations = validator.validate(validPackage(10, 4));

        assertTrue(
                violations.stream()
                        .anyMatch(violation ->
                                violation.getPropertyPath().toString()
                                        .startsWith("quiz"))
        );
    }

    private LearningPackage validPackage(int flashCardCount, int quizCount){
        List<LearningPackage.Flashcard> flashcards = IntStream.range(0, flashCardCount)
                .mapToObj(index -> new LearningPackage.Flashcard(
                        "card-" + index,
                        "Question" + index,
                        "Answer" + index,
                        new LearningPackage.SourceReference(index, "Evidence " + index)
                ))
                .toList();

        List<LearningPackage.QuizQuestion> quiz = IntStream.range(0, quizCount)
                .mapToObj(index -> new LearningPackage.QuizQuestion(
                        "quiz-" + index,
                        "Quiz questions" + index,
                        List.of("Option A", "Option B", "Option C", "Option D"),
                        0,
                        "Explanation" + index,
                        new LearningPackage.SourceReference(index, "Evidence " + index)
                ))
                .toList();

        return new LearningPackage(
                "learning-package-v2",
                new LearningPackage.Video(
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                        "dQw4w9WgXcQ",
                        "Test video",
                        "en"
                ),

                new LearningPackage.Summary(
                        "Test overview",
                        List.of(new LearningPackage.Section(
                                "section-01",
                                "Introduction",
                                new LearningPackage.SourceReference(0, "Opening statement"),
                                List.of("Test content")
                        ))
                ),

                List.of(new LearningPackage.EvidenceItem(
                        "takeaway-01", "Test takeaway",
                        new LearningPackage.SourceReference(0, "Opening statement")
                )),
                flashcards,
                quiz
        );
    }


}
