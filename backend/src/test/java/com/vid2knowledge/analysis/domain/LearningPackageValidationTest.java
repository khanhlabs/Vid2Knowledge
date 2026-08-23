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
                        "Question" + index,
                        "Answer" + index,
                        "00:00"
                ))
                .toList();

        List<LearningPackage.QuizQuestion> quiz = IntStream.range(0, quizCount)
                .mapToObj(index -> new LearningPackage.QuizQuestion(
                        "Quiz questions" + index,
                        List.of("Option A", "Option B", "Option C", "Option D"),
                        0,
                        "Explanation" + index,
                        "00:00"
                ))
                .toList();

        return new LearningPackage(
                new LearningPackage.Video(
                        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                        "Test video",
                        "en"
                ),

                new LearningPackage.Summary(
                        "Test overview",
                        List.of(new LearningPackage.Section(
                                "Introduction",
                                "00:00",
                                List.of("Test content")
                        ))
                ),

                List.of("Test takeaway"),
                flashcards,
                quiz
        );
    }


}
