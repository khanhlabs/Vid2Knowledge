package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.port.VideoAnalysisProvider;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.config.GeminiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

@Component
public class GeminiInteractionClient implements VideoAnalysisProvider {

    private final RestClient restClient;
    private final GeminiProperties properties;
    private static final Logger log = LoggerFactory.getLogger(GeminiInteractionClient.class);

    public GeminiInteractionClient(GeminiProperties properties){
        this.properties = properties;

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
    public AiGenerationResult generateLearningPackage(
            String prompt,
            String canonicalYoutubeUrl
    ) {
        long startedAt = System.nanoTime();

        Map<String, Object> requestBody = Map.of(
                "model", properties.model(),
                "store", false,
                "input", List.of(
                        Map.of("type", "video", "uri", canonicalYoutubeUrl),
                        Map.of("type", "text", "text", prompt)
                )
        );

        JsonNode response = restClient.post()
                .uri("/interactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("Gemini returned an empty response");
        }

        if (!"completed".equals(response.path("status").asText())) {
            throw new IllegalStateException(
                    "Gemini interaction did not complete. status="
                            + response.path("status").asText()
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
            throw new IllegalStateException("Gemini returned no text output");
        }

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
