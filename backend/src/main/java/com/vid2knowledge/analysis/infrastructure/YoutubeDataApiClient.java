package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.port.VideoMetadataProvider;
import com.vid2knowledge.config.YoutubeProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

@Component
public class YoutubeDataApiClient implements VideoMetadataProvider {

    private final YoutubeProperties properties;
    private final RestClient restClient;

    public YoutubeDataApiClient(YoutubeProperties properties) {
        this.properties = properties;
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public VideoMetadata fetch(String videoId) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("YouTube Data API key is not configured");
        }
        JsonNode response = restClient.get()
                .uri(uri -> uri.path("/videos")
                        .queryParam("part", "snippet,contentDetails,status")
                        .queryParam("id", videoId)
                        .queryParam("key", properties.apiKey())
                        .build())
                .retrieve()
                .body(JsonNode.class);
        JsonNode item = response == null ? null : response.path("items").path(0);
        if (item == null || item.isMissingNode()
                || !"public".equals(item.path("status").path("privacyStatus").asText())
                || !item.path("status").path("embeddable").asBoolean(false)) {
            throw new IllegalArgumentException("Video is unavailable, private, or not embeddable");
        }
        long duration;
        try {
            duration = Duration.parse(item.path("contentDetails").path("duration").asText()).toSeconds();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Video duration is unavailable", exception);
        }
        if (duration <= 0 || duration > properties.maxDurationSeconds()) {
            throw new IllegalArgumentException("Video duration is outside the supported range");
        }
        JsonNode snippet = item.path("snippet");
        String language = snippet.path("defaultAudioLanguage")
                .asText(snippet.path("defaultLanguage").asText("und"));
        return new VideoMetadata(snippet.path("title").asText(), duration, language);
    }
}
