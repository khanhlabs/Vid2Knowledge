package com.vid2knowledge.benchmark;

import com.vid2knowledge.analysis.application.AiProviderException;
import com.vid2knowledge.analysis.application.InvalidLearningPackageException;
import com.vid2knowledge.analysis.application.LearningPackageCodec;
import com.vid2knowledge.analysis.application.LearningPackagePromptFactory;
import com.vid2knowledge.analysis.application.YoutubeUrlParser;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.analysis.domain.AiCostRateCard;
import com.vid2knowledge.analysis.infrastructure.AiProviderCircuitBreaker;
import com.vid2knowledge.analysis.infrastructure.GeminiFileClient;
import com.vid2knowledge.analysis.infrastructure.GeminiInteractionClient;
import com.vid2knowledge.config.GeminiProperties;
import jakarta.validation.Validation;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Explicit, offline benchmark command. It never starts the application or writes production data.
 */
public final class BenchmarkRunner {
    static final String PROMPT_VERSION = "learning-package-v3";
    private static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");
    private static final Set<String> LANGUAGES = Set.of("vi", "en", "mixed");
    private static final Set<String> FORMATS = Set.of("lecture", "podcast", "screen-demo", "slides", "other");
    private static final String DEFAULT_PROFILE = """
            {"language":"auto","audience":"professional","difficulty":"intermediate",
             "flashcards":12,"quizQuestions":6,"tone":"concise"}
            """;

    private BenchmarkRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: BenchmarkRunner <controlled-dataset.json> <new-output-directory>");
        }
        String apiKey = requiredEnvironment("GEMINI_API_KEY");
        Path datasetPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(datasetPath)) throw new IllegalArgumentException("Dataset file does not exist");
        if (Files.exists(outputDirectory)) throw new IllegalArgumentException("Output directory must not already exist");

        ObjectMapper mapper = new ObjectMapper();
        byte[] datasetBytes = Files.readAllBytes(datasetPath);
        Dataset dataset = mapper.readValue(datasetBytes, Dataset.class);
        boolean partial = Boolean.parseBoolean(environment("BENCHMARK_ALLOW_PARTIAL", "false"));
        validateDataset(dataset, partial);
        Files.createDirectory(outputDirectory);

        String model = environment("GEMINI_MODEL", "gemini-3.7-flash");
        GeminiProperties properties = new GeminiProperties(
                apiKey, model, environment("GEMINI_EMBEDDING_MODEL", "gemini-embedding-001"),
                URI.create(environment("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta")),
                Duration.parse(environment("GEMINI_TIMEOUT", "PT3M")), Duration.ofMinutes(10)
        );
        var beanFactory = new DefaultListableBeanFactory();
        var provider = new GeminiInteractionClient(
                properties, new AiProviderCircuitBreaker(), beanFactory.getBeanProvider(GeminiFileClient.class)
        );
        var promptFactory = new LearningPackagePromptFactory(mapper);
        var validatorFactory = Validation.buildDefaultValidatorFactory();
        var codec = new LearningPackageCodec(mapper, validatorFactory.getValidator());
        AiCostRateCard actualRates = rates("AI_ACTUAL", 750_000, 3_750_000, 3_750_000);
        AiCostRateCard shadowRates = rates("AI_SHADOW", 1_500_000, 7_500_000, 7_500_000);
        int maximumAttempts = integerEnvironment("BENCHMARK_MAX_ATTEMPTS", 3, 1, 5);
        String profile = environment("BENCHMARK_OUTPUT_PROFILE", DEFAULT_PROFILE).trim();
        promptFactory.create(profile); // validate before the first paid request

        String startedAt = Instant.now().toString();
        List<RunResult> results = new ArrayList<>();
        Path journal = outputDirectory.resolve("run-results.jsonl");
        try {
            for (DatasetRun run : dataset.runs()) {
                RunResult result = execute(
                        run, maximumAttempts, provider, promptFactory, codec, actualRates, shadowRates,
                        profile, outputDirectory, mapper
                );
                results.add(result);
                Files.writeString(
                        journal, mapper.writeValueAsString(result) + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND
                );
            }
        } finally {
            validatorFactory.close();
        }

        Summary summary = summarize(results);
        Map<String, Summary> strata = summarizeStrata(dataset.runs(), results);
        Manifest manifest = new Manifest(
                dataset.datasetVersion(), sha256(datasetBytes), startedAt, Instant.now().toString(),
                "GOOGLE_GEMINI", model, PROMPT_VERSION, LearningPackageCodec.SCHEMA_VERSION,
                profile, maximumAttempts, partial, rateMap(actualRates), rateMap(shadowRates), summary, strata
        );
        Files.writeString(
                outputDirectory.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW
        );
        writeReviewTemplate(outputDirectory.resolve("human-review.csv"), dataset.runs());
        System.out.printf(
                Locale.ROOT,
                "Benchmark complete: %d/%d valid (%.2f%%), p95 latency=%dms, shadow cost=%d microusd%n",
                summary.validRuns(), summary.totalRuns(), summary.validRate() * 100,
                summary.p95LatencyMs(), summary.totalShadowCostMicrousd()
        );
    }

    private static RunResult execute(
            DatasetRun run,
            int maximumAttempts,
            GeminiInteractionClient provider,
            LearningPackagePromptFactory promptFactory,
            LearningPackageCodec codec,
            AiCostRateCard actualRates,
            AiCostRateCard shadowRates,
            String profile,
            Path outputDirectory,
            ObjectMapper mapper
    ) {
        String canonicalUrl = new YoutubeUrlParser().parse(run.youtubeUrl()).canonicalUrl();
        List<Attempt> attempts = new ArrayList<>();
        for (int attemptNumber = 1; attemptNumber <= maximumAttempts; attemptNumber++) {
            try {
                AiGenerationResult generation = provider.generateLearningPackage(
                        promptFactory.create(profile), canonicalUrl
                );
                long actualCost = actualRates.estimateMicrousd(generation);
                long shadowCost = shadowRates.estimateMicrousd(generation);
                try {
                    var learningPackage = codec.parseAndValidate(
                            generation.output(), canonicalUrl, run.durationSeconds()
                    );
                    Path packagePath = outputDirectory.resolve(run.runId() + ".package.json");
                    Files.writeString(
                            packagePath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(learningPackage),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW
                    );
                    attempts.add(Attempt.from(attemptNumber, generation, "VALID", actualCost, shadowCost, null));
                    return new RunResult(run.runId(), "VALID", true, packagePath.getFileName().toString(), attempts);
                } catch (InvalidLearningPackageException invalid) {
                    attempts.add(Attempt.from(
                            attemptNumber, generation, "INVALID_SCHEMA", actualCost, shadowCost, safeError(invalid)
                    ));
                    return new RunResult(run.runId(), "INVALID_SCHEMA", false, null, attempts);
                }
            } catch (AiProviderException providerFailure) {
                attempts.add(Attempt.failure(attemptNumber, "PROVIDER_ERROR", safeError(providerFailure)));
                if (!providerFailure.retryable() || attemptNumber == maximumAttempts) {
                    return new RunResult(run.runId(), "PROVIDER_ERROR", false, null, attempts);
                }
            } catch (Exception unexpected) {
                attempts.add(Attempt.failure(attemptNumber, "RUNNER_ERROR", safeError(unexpected)));
                return new RunResult(run.runId(), "RUNNER_ERROR", false, null, attempts);
            }
        }
        throw new IllegalStateException("Benchmark attempt loop exited unexpectedly");
    }

    static void validateDataset(Dataset dataset, boolean allowPartial) {
        if (dataset == null || dataset.datasetVersion() == null || dataset.datasetVersion().isBlank()) {
            throw new IllegalArgumentException("datasetVersion is required");
        }
        if (dataset.runs() == null || dataset.runs().isEmpty()) {
            throw new IllegalArgumentException("Dataset must contain runs");
        }
        Set<String> ids = new HashSet<>();
        Set<String> caseIds = new HashSet<>();
        Set<String> canonicalUrls = new HashSet<>();
        Set<String> languages = new HashSet<>();
        Set<String> formats = new HashSet<>();
        Set<Boolean> transcriptStates = new HashSet<>();
        Set<String> durationBuckets = new HashSet<>();
        Map<String, String> caseUrls = new LinkedHashMap<>();
        Map<String, Integer> caseRunCounts = new LinkedHashMap<>();
        YoutubeUrlParser urls = new YoutubeUrlParser();
        for (DatasetRun run : dataset.runs()) {
            if (run == null || run.caseId() == null || !RUN_ID.matcher(run.caseId()).matches()) {
                throw new IllegalArgumentException("Every caseId must be a safe 1-64 character identifier");
            }
            if (run == null || run.runId() == null || !RUN_ID.matcher(run.runId()).matches()) {
                throw new IllegalArgumentException("Every runId must be a safe 1-64 character identifier");
            }
            if (!ids.add(run.runId())) throw new IllegalArgumentException("Duplicate runId: " + run.runId());
            String canonicalUrl = urls.parse(run.youtubeUrl()).canonicalUrl();
            String existingUrl = caseUrls.putIfAbsent(run.caseId(), canonicalUrl);
            if (existingUrl != null && !existingUrl.equals(canonicalUrl)) {
                throw new IllegalArgumentException("A caseId cannot refer to multiple videos: " + run.caseId());
            }
            caseIds.add(run.caseId());
            canonicalUrls.add(canonicalUrl);
            caseRunCounts.merge(run.caseId(), 1, Integer::sum);
            if (run.durationSeconds() < 1 || run.durationSeconds() > 14_400) {
                throw new IllegalArgumentException("durationSeconds must be 1-14400 for " + run.runId());
            }
            if (run.rightsBasis() == null || run.rightsBasis().isBlank()) {
                throw new IllegalArgumentException("rightsBasis is required for " + run.runId());
            }
            if (!LANGUAGES.contains(run.language())) {
                throw new IllegalArgumentException("Unsupported language stratum for " + run.runId());
            }
            if (!FORMATS.contains(run.format())) {
                throw new IllegalArgumentException("Unsupported format stratum for " + run.runId());
            }
            if (run.characteristics() == null || run.characteristics().isBlank()) {
                throw new IllegalArgumentException("characteristics is required for " + run.runId());
            }
            languages.add(run.language());
            formats.add(run.format());
            transcriptStates.add(run.transcriptAvailable());
            durationBuckets.add(durationBucket(run.durationSeconds()));
        }
        if (!allowPartial) {
            if (caseIds.size() < 50 || canonicalUrls.size() < 50) {
                throw new IllegalArgumentException("A decision benchmark requires at least 50 unique supported videos");
            }
            if (!languages.containsAll(Set.of("vi", "en"))) {
                throw new IllegalArgumentException("A decision benchmark must include Vietnamese and English videos");
            }
            if (!formats.containsAll(Set.of("lecture", "podcast", "screen-demo", "slides"))) {
                throw new IllegalArgumentException("A decision benchmark must include all four core formats");
            }
            if (!transcriptStates.containsAll(Set.of(true, false))) {
                throw new IllegalArgumentException("A decision benchmark must include videos with and without transcripts");
            }
            if (!durationBuckets.containsAll(Set.of("short", "medium", "long"))) {
                throw new IllegalArgumentException("A decision benchmark must include short, medium, and long videos");
            }
            long repeatedCases = caseRunCounts.values().stream().filter(count -> count >= 2).count();
            if (repeatedCases < 10) {
                throw new IllegalArgumentException("A decision benchmark requires repeat runs for at least 10 cases");
            }
        }
    }

    private static Map<String, Summary> summarizeStrata(List<DatasetRun> runs, List<RunResult> results) {
        Map<String, RunResult> byRunId = results.stream().collect(java.util.stream.Collectors.toMap(
                RunResult::runId, result -> result
        ));
        Map<String, List<RunResult>> groups = new LinkedHashMap<>();
        for (DatasetRun run : runs) {
            RunResult result = byRunId.get(run.runId());
            addGroup(groups, "language:" + run.language(), result);
            addGroup(groups, "format:" + run.format(), result);
            addGroup(groups, "transcript:" + run.transcriptAvailable(), result);
            addGroup(groups, "duration:" + durationBucket(run.durationSeconds()), result);
        }
        Map<String, Summary> summaries = new LinkedHashMap<>();
        groups.forEach((name, groupResults) -> summaries.put(name, summarize(groupResults)));
        return summaries;
    }

    private static void addGroup(Map<String, List<RunResult>> groups, String name, RunResult result) {
        groups.computeIfAbsent(name, ignored -> new ArrayList<>()).add(result);
    }

    private static String durationBucket(long durationSeconds) {
        if (durationSeconds <= 900) return "short";
        if (durationSeconds <= 3_600) return "medium";
        return "long";
    }

    private static Summary summarize(List<RunResult> results) {
        List<Attempt> generated = results.stream().flatMap(result -> result.attempts().stream())
                .filter(attempt -> attempt.latencyMs() != null).toList();
        List<Long> latencies = generated.stream().map(Attempt::latencyMs).sorted().toList();
        List<Long> validCosts = results.stream().filter(RunResult::validSchema)
                .map(result -> result.attempts().getLast().shadowCostMicrousd()).sorted().toList();
        int valid = (int) results.stream().filter(RunResult::validSchema).count();
        return new Summary(
                results.size(), valid, valid / (double) results.size(),
                generated.size(), percentile(latencies, 0.50), percentile(latencies, 0.95),
                percentile(validCosts, 0.50), percentile(validCosts, 0.95),
                generated.stream().mapToLong(Attempt::actualCostMicrousd).sum(),
                generated.stream().mapToLong(Attempt::shadowCostMicrousd).sum()
        );
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static void writeReviewTemplate(Path path, List<DatasetRun> runs) throws Exception {
        StringBuilder csv = new StringBuilder("\uFEFFcase_id,run_id,language,format,duration_seconds,"
                + "characteristics,transcript_available,"
                + "factual_accuracy,timestamp_accuracy,flashcards,quiz,learning_usefulness,serious_defect,reviewer_notes\r\n");
        for (DatasetRun run : runs) {
            csv.append(csv(run.caseId())).append(',').append(csv(run.runId())).append(',')
                    .append(csv(run.language())).append(',').append(csv(run.format())).append(',')
                    .append(run.durationSeconds()).append(',').append(csv(run.characteristics())).append(',')
                    .append(run.transcriptAvailable())
                    .append(",,,,,,,\r\n");
        }
        Files.writeString(path, csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static AiCostRateCard rates(String prefix, long input, long output, long thought) {
        return new AiCostRateCard(
                longEnvironment(prefix + "_INPUT_MICROUSD", input),
                longEnvironment(prefix + "_OUTPUT_MICROUSD", output),
                longEnvironment(prefix + "_THOUGHT_MICROUSD", thought)
        );
    }

    private static Map<String, Long> rateMap(AiCostRateCard rates) {
        return Map.of(
                "inputMicrousdPerMillionTokens", rates.inputMicrousdPerMillionTokens(),
                "outputMicrousdPerMillionTokens", rates.outputMicrousdPerMillionTokens(),
                "thoughtMicrousdPerMillionTokens", rates.thoughtMicrousdPerMillionTokens()
        );
    }

    private static int integerEnvironment(String name, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(environment(name, String.valueOf(fallback)));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " is out of range");
        return value;
    }

    private static long longEnvironment(String name, long fallback) {
        long value = Long.parseLong(environment(name, String.valueOf(fallback)));
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
        return value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String safeError(Exception error) {
        String value = error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "no detail" : error.getMessage());
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    record Dataset(String datasetVersion, List<DatasetRun> runs) { }

    record DatasetRun(
            String caseId, String runId, String youtubeUrl, long durationSeconds, String rightsBasis,
            String language, String format, String characteristics, boolean transcriptAvailable
    ) { }

    record RunResult(String runId, String status, boolean validSchema, String packageFile, List<Attempt> attempts) {
        RunResult {
            attempts = List.copyOf(attempts);
        }
    }

    record Attempt(
            int attempt, String status, String provider, String model, String modelVersion,
            Long inputTokens, Long outputTokens, Long thoughtTokens, Long latencyMs,
            long actualCostMicrousd, long shadowCostMicrousd, String error
    ) {
        static Attempt from(
                int attempt, AiGenerationResult value, String status, long actualCost, long shadowCost, String error
        ) {
            return new Attempt(
                    attempt, status, value.provider(), value.model(), value.modelVersion(), value.inputTokens(),
                    value.outputTokens(), value.thoughtTokens(), value.latencyMs(), actualCost, shadowCost, error
            );
        }

        static Attempt failure(int attempt, String status, String error) {
            return new Attempt(attempt, status, null, null, null, null, null, null, null, 0, 0, error);
        }
    }

    record Manifest(
            String datasetVersion, String datasetSha256, String startedAt, String completedAt,
            String provider, String model, String promptVersion, String schemaVersion,
            String outputProfileJson, int maximumAttempts, boolean partial,
            Map<String, Long> actualRates, Map<String, Long> shadowRates, Summary summary,
            Map<String, Summary> strata
    ) { }

    record Summary(
            int totalRuns, int validRuns, double validRate, int providerResponses,
            long medianLatencyMs, long p95LatencyMs, long medianValidShadowCostMicrousd,
            long p95ValidShadowCostMicrousd, long totalActualCostMicrousd, long totalShadowCostMicrousd
    ) { }
}
