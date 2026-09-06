package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.domain.LearningPackage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

@Component
public class LearningPackageCodec {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public LearningPackageCodec(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public LearningPackage parseAndValidate(String rawOutput, String canonicalSourceUri) {
        LearningPackage parsed;
        try {
            parsed = objectMapper.readValue(removeCodeFenceIfPresent(rawOutput), LearningPackage.class);
        } catch (Exception exception) {
            throw new InvalidLearningPackageException("AI returned malformed LearningPackage JSON", exception);
        }

        Set<ConstraintViolation<LearningPackage>> violations = validator.validate(parsed);
        if (!violations.isEmpty()) {
            throw new InvalidLearningPackageException("AI returned a LearningPackage that violates the schema");
        }

        return new LearningPackage(
                new LearningPackage.Video(
                        canonicalSourceUri,
                        parsed.video().title(),
                        parsed.video().language()
                ),
                parsed.summary(),
                parsed.keyTakeaways(),
                parsed.flashcards(),
                parsed.quiz()
        );
    }

    public String write(LearningPackage learningPackage) {
        try {
            return objectMapper.writeValueAsString(learningPackage);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize validated LearningPackage", exception);
        }
    }

    private static String removeCodeFenceIfPresent(String rawOutput) {
        String text = rawOutput.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineBreak = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstLineBreak < 0 || lastFence <= firstLineBreak) {
            return text;
        }
        return text.substring(firstLineBreak + 1, lastFence).trim();
    }
}
