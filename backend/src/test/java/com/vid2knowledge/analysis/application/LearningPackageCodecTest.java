package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.domain.LearningPackage;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningPackageCodecTest {

    private final LearningPackageCodec codec = new LearningPackageCodec(
            new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator()
    );

    @Test
    void replacesUntrustedSourceIdentityAndSchemaVersion() {
        LearningPackage parsed = codec.parseAndValidate(
                codec.write(validPackage()),
                "https://www.youtube.com/watch?v=abcdefghijk",
                600
        );

        assertThat(parsed.schemaVersion()).isEqualTo("learning-package-v2");
        assertThat(parsed.video().videoId()).isEqualTo("abcdefghijk");
        assertThat(parsed.video().youtubeUrl())
                .isEqualTo("https://www.youtube.com/watch?v=abcdefghijk");
    }

    @Test
    void rejectsEvidenceOutsideVerifiedVideoDuration() {
        assertThatThrownBy(() -> codec.parseAndValidate(
                codec.write(validPackage()),
                "https://www.youtube.com/watch?v=abcdefghijk",
                5
        )).isInstanceOf(InvalidLearningPackageException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void rejectsDuplicateQuizOptions() {
        LearningPackage base = validPackage();
        LearningPackage.QuizQuestion first = base.quiz().getFirst();
        var duplicate = new LearningPackage.QuizQuestion(
                first.id(), first.question(), List.of("A", "A", "C", "D"),
                first.correctAnswerIndex(), first.explanation(), first.source()
        );
        LearningPackage changed = new LearningPackage(
                base.schemaVersion(), base.video(), base.summary(), base.keyTakeaways(),
                base.flashcards(), java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(duplicate), base.quiz().stream().skip(1)
                ).toList()
        );

        assertThatThrownBy(() -> codec.parseAndValidate(
                codec.write(changed), "https://www.youtube.com/watch?v=abcdefghijk", 600
        )).isInstanceOf(InvalidLearningPackageException.class)
                .hasMessageContaining("options");
    }

    private static LearningPackage validPackage() {
        var source = new LearningPackage.SourceReference(10, "Direct supporting evidence");
        var cards = IntStream.range(0, 10)
                .mapToObj(index -> new LearningPackage.Flashcard(
                        "card-" + index, "Question " + index, "Answer " + index, source
                )).toList();
        var quiz = IntStream.range(0, 5)
                .mapToObj(index -> new LearningPackage.QuizQuestion(
                        "quiz-" + index, "Quiz " + index,
                        List.of("A" + index, "B" + index, "C" + index, "D" + index),
                        0, "Explanation " + index, source
                )).toList();
        return new LearningPackage(
                "untrusted-version",
                new LearningPackage.Video("https://attacker.invalid", "zzzzzzzzzzz", "Title", "vi"),
                new LearningPackage.Summary(
                        "Overview",
                        List.of(new LearningPackage.Section(
                                "section-01", "Section", source, List.of("Content")
                        ))
                ),
                List.of(new LearningPackage.EvidenceItem("takeaway-01", "Takeaway", source)),
                cards,
                quiz
        );
    }
}
