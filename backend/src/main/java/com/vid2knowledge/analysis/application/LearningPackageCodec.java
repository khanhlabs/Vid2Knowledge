package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.domain.LearningPackage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.HashSet;
import java.util.List;

@Component
public class LearningPackageCodec {

    public static final String SCHEMA_VERSION = "learning-package-v2";

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public LearningPackageCodec(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public LearningPackage parseAndValidate(String rawOutput, String canonicalSourceUri) {
        return parseAndValidate(rawOutput, canonicalSourceUri, Long.MAX_VALUE);
    }

    public LearningPackage parseAndValidate(
            String rawOutput,
            String canonicalSourceUri,
            long sourceDurationSeconds
    ) {
        LearningPackage parsed;
        try {
            parsed = objectMapper.readValue(removeCodeFenceIfPresent(rawOutput), LearningPackage.class);
        } catch (Exception exception) {
            throw new InvalidLearningPackageException("AI returned malformed LearningPackage JSON", exception);
        }

        String videoId = new YoutubeUrlParser().parse(canonicalSourceUri).videoId();
        parsed = new LearningPackage(
                SCHEMA_VERSION,
                new LearningPackage.Video(
                        canonicalSourceUri,
                        videoId,
                        parsed.video().title(),
                        parsed.video().language()
                ),
                parsed.summary(), parsed.keyTakeaways(), parsed.flashcards(), parsed.quiz()
        );
        Set<ConstraintViolation<LearningPackage>> violations = validator.validate(parsed);
        if (!violations.isEmpty()) {
            throw new InvalidLearningPackageException("AI returned a LearningPackage that violates the schema");
        }

        validateDomainRules(parsed, sourceDurationSeconds);
        return parsed;
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

    private static void validateDomainRules(LearningPackage value, long sourceDurationSeconds) {
        Set<String> ids = new HashSet<>();
        value.summary().sections().forEach(item -> unique(ids, item.id()));
        value.keyTakeaways().forEach(item -> unique(ids, item.id()));
        value.flashcards().forEach(item -> unique(ids, item.id()));
        value.quiz().forEach(item -> {
            unique(ids, item.id());
            if (new HashSet<>(normalized(item.options())).size() != item.options().size()) {
                throw new InvalidLearningPackageException("Quiz options must be unique");
            }
        });
        if (new HashSet<>(normalized(value.flashcards().stream()
                .map(LearningPackage.Flashcard::question).toList())).size() != value.flashcards().size()) {
            throw new InvalidLearningPackageException("Flashcard questions must be unique");
        }
        if (new HashSet<>(normalized(value.quiz().stream()
                .map(LearningPackage.QuizQuestion::question).toList())).size() != value.quiz().size()) {
            throw new InvalidLearningPackageException("Quiz questions must be unique");
        }
        references(value).forEach(reference -> {
            if (reference.timestampSeconds() > sourceDurationSeconds) {
                throw new InvalidLearningPackageException("A source timestamp exceeds the video duration");
            }
        });
    }

    private static List<String> normalized(List<String> values) {
        return values.stream().map(value -> value.trim().toLowerCase(java.util.Locale.ROOT)).toList();
    }

    private static void unique(Set<String> ids, String id) {
        if (!ids.add(id)) {
            throw new InvalidLearningPackageException("Learning item IDs must be unique");
        }
    }

    private static java.util.stream.Stream<LearningPackage.SourceReference> references(LearningPackage value) {
        return java.util.stream.Stream.of(
                value.summary().sections().stream().map(LearningPackage.Section::source),
                value.keyTakeaways().stream().map(LearningPackage.EvidenceItem::source),
                value.flashcards().stream().map(LearningPackage.Flashcard::source),
                value.quiz().stream().map(LearningPackage.QuizQuestion::source)
        ).flatMap(stream -> stream);
    }
}
