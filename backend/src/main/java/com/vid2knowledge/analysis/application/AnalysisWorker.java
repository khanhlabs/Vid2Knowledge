package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.analysis.application.port.VideoAnalysisProvider;
import com.vid2knowledge.analysis.domain.AnalysisWorkItem;
import com.vid2knowledge.analysis.domain.GenerationAccounting;
import com.vid2knowledge.config.AiCostProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisWorker {

    static final String PROMPT_VERSION = "learning-package-v1";
    static final String SCHEMA_VERSION = "learning-package-v1";

    private final AnalysisJobStore jobs;
    private final VideoAnalysisProvider provider;
    private final LearningPackagePromptFactory promptFactory;
    private final LearningPackageCodec codec;
    private final AnalysisCompletionService completion;
    private final AiCostProperties costProperties;
    private final Clock clock;

    @Autowired
    public AnalysisWorker(
            AnalysisJobStore jobs,
            VideoAnalysisProvider provider,
            LearningPackagePromptFactory promptFactory,
            LearningPackageCodec codec,
            AnalysisCompletionService completion,
            AiCostProperties costProperties
    ) {
        this(jobs, provider, promptFactory, codec, completion, costProperties, Clock.systemUTC());
    }

    AnalysisWorker(
            AnalysisJobStore jobs,
            VideoAnalysisProvider provider,
            LearningPackagePromptFactory promptFactory,
            LearningPackageCodec codec,
            AnalysisCompletionService completion,
            AiCostProperties costProperties,
            Clock clock
    ) {
        this.jobs = jobs;
        this.provider = provider;
        this.promptFactory = promptFactory;
        this.codec = codec;
        this.completion = completion;
        this.costProperties = costProperties;
        this.clock = clock;
    }

    public WorkResult process(UUID jobId, String workerId) {
        Optional<AnalysisWorkItem> claimed = jobs.claim(
                jobId, workerId, costProperties.leaseDuration(), clock.instant()
        );
        if (claimed.isEmpty()) {
            return WorkResult.NOT_CLAIMED;
        }

        AnalysisWorkItem item = claimed.get();
        AiGenerationResult generation;
        try {
            generation = provider.generateLearningPackage(promptFactory.create(), item.sourceUri());
        } catch (RuntimeException exception) {
            boolean terminal = item.job().attempt() >= costProperties.maxAttempts();
            completion.fail(
                    item, workerId, null, "PROVIDER_ERROR", safeMessage(exception), terminal,
                    retryAt(item), PROMPT_VERSION, SCHEMA_VERSION, clock.instant()
            );
            return terminal ? WorkResult.FAILED : WorkResult.RETRY_SCHEDULED;
        }

        GenerationAccounting accounting = new GenerationAccounting(
                generation,
                costProperties.actual().toRateCard().estimateMicrousd(generation),
                costProperties.shadow().toRateCard().estimateMicrousd(generation)
        );
        try {
            var learningPackage = codec.parseAndValidate(generation.output(), item.sourceUri());
            completion.complete(
                    item, workerId, accounting, codec.write(learningPackage),
                    PROMPT_VERSION, SCHEMA_VERSION, clock.instant()
            );
            return WorkResult.COMPLETED;
        } catch (InvalidLearningPackageException exception) {
            completion.fail(
                    item, workerId, accounting, "INVALID_AI_OUTPUT", safeMessage(exception), true,
                    clock.instant(), PROMPT_VERSION, SCHEMA_VERSION, clock.instant()
            );
            return WorkResult.FAILED;
        }
    }

    private java.time.Instant retryAt(AnalysisWorkItem item) {
        long seconds = Math.min(900, 30L << Math.min(item.job().attempt() - 1, 5));
        return clock.instant().plus(Duration.ofSeconds(seconds));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    public enum WorkResult {
        NOT_CLAIMED,
        COMPLETED,
        RETRY_SCHEDULED,
        FAILED
    }
}
