package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.port.VideoAnalysisProvider;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.analysis.application.port.KnowledgeAiProvider;
import com.vid2knowledge.config.GeminiProperties;
import com.vid2knowledge.analysis.application.AiProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

@Component
public class GeminiInteractionClient implements VideoAnalysisProvider, KnowledgeAiProvider {

    private final RestClient restClient;
    private final GeminiProperties properties;
    private final AiProviderCircuitBreaker circuitBreaker;
    private static final Logger log = LoggerFactory.getLogger(GeminiInteractionClient.class);

    public GeminiInteractionClient(GeminiProperties properties, AiProviderCircuitBreaker circuitBreaker){
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("x-goog-api-key", properties.apiKey())
                .build();
    }

    @Override
    public List<List<Double>> embed(List<String> texts, EmbeddingPurpose purpose) {
        if (texts.isEmpty() || texts.size() > 100) {
            throw new IllegalArgumentException("Embedding batch must contain 1 to 100 texts");
        }
        String taskType = purpose == EmbeddingPurpose.DOCUMENT
                ? "RETRIEVAL_DOCUMENT" : "RETRIEVAL_QUERY";
        String model = "models/" + properties.embeddingModel();
        List<Map<String, Object>> requests = texts.stream().map(text -> Map.<String, Object>of(
                "model", model,
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "taskType", taskType,
                "outputDimensionality", 768
        )).toList();
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/models/{model}:batchEmbedContents", properties.embeddingModel())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("requests", requests))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new AiProviderException(
                    "Gemini embedding request failed with HTTP " + status,
                    status == 408 || status == 429 || status >= 500, exception
            );
        } catch (ResourceAccessException exception) {
            throw new AiProviderException("Gemini embedding request timed out or could not connect", true, exception);
        }
        if (response == null || !response.path("embeddings").isArray()
                || response.path("embeddings").size() != texts.size()) {
            throw new AiProviderException("Gemini returned an invalid embedding batch", false);
        }
        return java.util.stream.StreamSupport.stream(response.path("embeddings").spliterator(), false)
                .map(item -> java.util.stream.StreamSupport.stream(item.path("values").spliterator(), false)
                        .map(JsonNode::asDouble).toList())
                .peek(vector -> {
                    if (vector.size() != 768) {
                        throw new AiProviderException("Gemini embedding dimension mismatch", false);
                    }
                })
                .toList();
    }

    @Override
    public AiGenerationResult generateGroundedAnswer(String prompt) {
        return generateText(List.of(Map.of("type", "text", "text", prompt)));
    }

    @Override
    public String embeddingModel() {
        return properties.embeddingModel();
    }

    @Override
    public AiGenerationResult generateLearningPackage(
            String prompt,
            String canonicalYoutubeUrl
    ) {
        return generateText(List.of(
                Map.of("type", "video", "uri", canonicalYoutubeUrl),
                Map.of("type", "text", "text", prompt)
        ));
    }

    private AiGenerationResult generateText(List<Map<String, String>> input) {
        circuitBreaker.beforeCall();
        long startedAt = System.nanoTime();

        Map<String, Object> requestBody = Map.of(
                "model", properties.model(),
                "store", false,
                "input", input
        );

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/interactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 408 || status == 429 || status >= 500;
            if (retryable) circuitBreaker.transientFailure();
            throw new AiProviderException("Gemini request failed with HTTP " + status, retryable, exception);
        } catch (ResourceAccessException exception) {
            circuitBreaker.transientFailure();
            throw new AiProviderException("Gemini request timed out or could not connect", true, exception);
        }

        if (response == null) {
            throw new AiProviderException("Gemini returned an empty response", false);
        }

        if (!"completed".equals(response.path("status").asText())) {
            throw new AiProviderException(
                    "Gemini interaction did not complete. status="
                            + response.path("status").asText(),
                    false
            );
        }

        JsonNode usage = response.path("usage");

        log.info(
                "Gemini interaction completed. model={}, status={}, durationMs={}, "
                        + "inputTokens={}, outputTokens={}, thoughtTokens={}, totalTokens={}",
                properties.model(),
                response.path("status").asText(),
                (System.nanoTime() - startedAt) / 1_000_000,
                usage.path("total_input_tokens").asLong(),
                usage.path("total_output_tokens").asLong(),
                usage.path("total_thought_tokens").asLong(),
                usage.path("total_tokens").asLong()
        );

        StringBuilder output = new StringBuilder();

        for (JsonNode step : response.path("steps")) {
            if (!"model_output".equals(step.path("type").asText())) {
                continue;
            }

            for (JsonNode content : step.path("content")) {
                if ("text".equals(content.path("type").asText())) {
                    output.append(content.path("text").asText());
                }
            }
        }

        if (output.isEmpty()) {
            throw new AiProviderException("Gemini returned no text output", false);
        }

        circuitBreaker.success();

        return new AiGenerationResult(
                "GOOGLE_GEMINI",
                properties.model(),
                response.path("modelVersion").asText("unknown"),
                usage.path("total_input_tokens").asLong(),
                usage.path("total_output_tokens").asLong(),
                usage.path("total_thought_tokens").asLong(),
                (System.nanoTime() - startedAt) / 1_000_000,
                0,
                output.toString()
        );
    }

}
