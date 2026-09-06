package com.vid2knowledge.integration;

import com.vid2knowledge.config.IntegrationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class DefaultWebhookHttpSender implements WebhookHttpSender {
    private final IntegrationProperties properties;
    private final HttpClient client;

    public DefaultWebhookHttpSender(IntegrationProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public int send(
            URI target, UUID deliveryId, String eventType, Instant timestamp, String signature, String payload
    ) {
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "Vid2Knowledge-Webhooks/1.0")
                .header("X-V2K-Delivery", deliveryId.toString())
                .header("X-V2K-Event", eventType)
                .header("X-V2K-Timestamp", Long.toString(timestamp.getEpochSecond()))
                .header("X-V2K-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook delivery was interrupted", interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Webhook endpoint could not be reached", failure);
        }
    }
}
