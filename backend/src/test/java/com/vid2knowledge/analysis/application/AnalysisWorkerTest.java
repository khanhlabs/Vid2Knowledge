package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.analysis.application.port.VideoAnalysisProvider;
import com.vid2knowledge.analysis.domain.AnalysisJob;
import com.vid2knowledge.analysis.domain.AnalysisWorkItem;
import com.vid2knowledge.analysis.domain.LearningPackage;
import com.vid2knowledge.config.AiCostProperties;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    @Test
    void completesOnlyAfterOutputValidationAndCalculatesBothCosts() {
        AnalysisJobStore jobs = mock(AnalysisJobStore.class);
        AnalysisCompletionService completion = mock(AnalysisCompletionService.class);
        var codec = codec();
        AnalysisWorkItem item = item(1);
        String validJson = codec.write(validPackage());
        VideoAnalysisProvider provider = (prompt, uri) -> generation(validJson);
        when(jobs.claim(item.job().id(), "worker-1", Duration.ofMinutes(5), NOW))
                .thenReturn(Optional.of(item));
        var worker = worker(jobs, provider, codec, completion);

        assertThat(worker.process(item.job().id(), "worker-1"))
                .isEqualTo(AnalysisWorker.WorkResult.COMPLETED);

        verify(completion).complete(
                eq(item), eq("worker-1"),
                org.mockito.ArgumentMatchers.argThat(cost ->
                        cost.actualCostMicrousd() == 188 && cost.shadowCostMicrousd() == 375),
                org.mockito.ArgumentMatchers.contains(item.source().canonicalUri()),
                eq("learning-package-v3"), eq("learning-package-v3"), eq(NOW)
        );
        verify(completion, never()).fail(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void malformedPaidOutputIsTerminalAndStillCarriesAccounting() {
        AnalysisJobStore jobs = mock(AnalysisJobStore.class);
        AnalysisCompletionService completion = mock(AnalysisCompletionService.class);
        AnalysisWorkItem item = item(1);
        when(jobs.claim(item.job().id(), "worker-1", Duration.ofMinutes(5), NOW))
                .thenReturn(Optional.of(item));
        var worker = worker(jobs, (prompt, uri) -> generation("not-json"), codec(), completion);

        assertThat(worker.process(item.job().id(), "worker-1"))
                .isEqualTo(AnalysisWorker.WorkResult.FAILED);

        verify(completion).fail(
                eq(item), eq("worker-1"), any(), eq("INVALID_AI_OUTPUT"), any(), eq(true),
                eq(NOW), eq("learning-package-v3"), eq("learning-package-v3"), eq(NOW)
        );
    }

    @Test
    void transientProviderFailureUsesExponentialRetryWithoutInventingCost() {
        AnalysisJobStore jobs = mock(AnalysisJobStore.class);
        AnalysisCompletionService completion = mock(AnalysisCompletionService.class);
        AnalysisWorkItem item = item(2);
        when(jobs.claim(item.job().id(), "worker-1", Duration.ofMinutes(5), NOW))
                .thenReturn(Optional.of(item));
        VideoAnalysisProvider provider = (prompt, uri) -> {
            throw new IllegalStateException("temporary outage");
        };

        assertThat(worker(jobs, provider, codec(), completion).process(item.job().id(), "worker-1"))
                .isEqualTo(AnalysisWorker.WorkResult.RETRY_SCHEDULED);

        verify(completion).fail(
                eq(item), eq("worker-1"), eq(null), eq("PROVIDER_ERROR"), eq("temporary outage"),
                eq(false), eq(NOW.plusSeconds(60)), eq("learning-package-v3"),
                eq("learning-package-v3"), eq(NOW)
        );
    }

    @Test
    void terminalProviderFailureIsNotRetried() {
        AnalysisJobStore jobs = mock(AnalysisJobStore.class);
        AnalysisCompletionService completion = mock(AnalysisCompletionService.class);
        AnalysisWorkItem item = item(1);
        when(jobs.claim(item.job().id(), "worker-1", Duration.ofMinutes(5), NOW))
                .thenReturn(Optional.of(item));
        VideoAnalysisProvider provider = (prompt, uri) -> {
            throw new AiProviderException("Gemini request failed with HTTP 400", false);
        };

        assertThat(worker(jobs, provider, codec(), completion).process(item.job().id(), "worker-1"))
                .isEqualTo(AnalysisWorker.WorkResult.FAILED);

        verify(completion).fail(
                eq(item), eq("worker-1"), eq(null), eq("PROVIDER_ERROR"),
                eq("Gemini request failed with HTTP 400"), eq(true), eq(NOW),
                eq("learning-package-v3"), eq("learning-package-v3"), eq(NOW)
        );
    }

    private static AnalysisWorker worker(
            AnalysisJobStore jobs,
            VideoAnalysisProvider provider,
            LearningPackageCodec codec,
            AnalysisCompletionService completion
    ) {
        var prices = new AiCostProperties(
                new AiCostProperties.Rate(750_000, 3_750_000, 3_750_000),
                new AiCostProperties.Rate(1_500_000, 7_500_000, 7_500_000),
                3,
                Duration.ofMinutes(5)
        );
        return new AnalysisWorker(
                jobs, provider, new LearningPackagePromptFactory(new ObjectMapper()), codec, completion,
                prices, Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static AnalysisWorkItem item(int attempt) {
        UUID jobId = UUID.randomUUID();
        var job = new AnalysisJob(
                jobId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AnalysisJob.State.PROCESSING, "fingerprint", "key", "GOOGLE_GEMINI",
                "gemini-3.7-flash", attempt, NOW
        );
        return new AnalysisWorkItem(job,
                com.vid2knowledge.analysis.domain.AnalysisSource.youtube(
                        "https://www.youtube.com/watch?v=abcdefghijk"), "{}", "corr", 300);
    }

    private static AiGenerationResult generation(String output) {
        return new AiGenerationResult(
                "GOOGLE_GEMINI", "gemini-3.7-flash", "001",
                100, 20, 10, 500, 0, output
        );
    }

    private static LearningPackageCodec codec() {
        return new LearningPackageCodec(
                new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator()
        );
    }

    private static LearningPackage validPackage() {
        var cards = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> new LearningPackage.Flashcard(
                        "card-" + index, "Question " + index, "Answer " + index,
                        new LearningPackage.SourceReference(index, "Evidence " + index)
                ))
                .toList();
        var quiz = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> new LearningPackage.QuizQuestion(
                        "quiz-" + index, "Quiz " + index,
                        List.of("A", "B", "C", "D"), 0, "Because",
                        new LearningPackage.SourceReference(index, "Evidence " + index)
                ))
                .toList();
        return new LearningPackage(
                "learning-package-v2",
                new LearningPackage.Video("ignored", "abcdefghijk", "Title", "vi"),
                new LearningPackage.Summary(
                        "Overview", List.of(new LearningPackage.Section(
                                "section-01", "Section",
                                new LearningPackage.SourceReference(0, "Evidence"), List.of("Content")
                        ))
                ),
                List.of(new LearningPackage.EvidenceItem(
                        "takeaway-01", "Takeaway", new LearningPackage.SourceReference(0, "Evidence")
                )), cards, quiz
        );
    }
}
