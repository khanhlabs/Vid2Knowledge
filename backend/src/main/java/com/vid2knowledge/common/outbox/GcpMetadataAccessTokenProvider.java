package com.vid2knowledge.common.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;

@Component
@ConditionalOnProperty(prefix = "task-queue", name = "mode", havingValue = "CLOUD_TASKS")
public class GcpMetadataAccessTokenProvider {

    private final RestClient metadata = RestClient.builder()
            .baseUrl("http://metadata.google.internal/computeMetadata/v1")
            .defaultHeader("Metadata-Flavor", "Google")
            .build();
    private final Clock clock = Clock.systemUTC();
    private volatile CachedToken cached;

    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (cached != null && cached.expiresAt().isAfter(now.plusSeconds(60))) {
            return cached.value();
        }
        JsonNode response = metadata.get()
                .uri("/instance/service-accounts/default/token")
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("access_token").asText().isBlank()) {
            throw new IllegalStateException("GCP metadata server returned no access token");
        }
        long expiresIn = response.path("expires_in").asLong(300);
        cached = new CachedToken(response.path("access_token").asText(), now.plusSeconds(expiresIn));
        return cached.value();
    }

    private record CachedToken(String value, Instant expiresAt) {
    }
}
