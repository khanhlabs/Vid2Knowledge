package com.vid2knowledge.notification;

import com.vid2knowledge.config.NotificationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "true")
public class ResendNotificationSender implements NotificationSender {
    private final NotificationProperties properties;
    private final RestClient client;

    public ResendNotificationSender(NotificationProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder()
                .baseUrl(properties.resendBaseUrl().toString())
                .defaultHeader("Authorization", "Bearer " + properties.apiKey())
                .build();
    }

    @Override
    public String send(OutboundEmail email) {
        try {
            JsonNode response = client.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", email.idempotencyKey())
                    .body(Map.of(
                            "from", properties.from(),
                            "to", List.of(email.recipient()),
                            "subject", email.subject(),
                            "html", email.html(),
                            "text", email.text()
                    ))
                    .retrieve()
                    .body(JsonNode.class);
            String id = response == null ? "" : response.path("id").asText();
            if (id.isBlank()) {
                throw new NotificationDeliveryException("Resend response did not contain a message id", true, null);
            }
            return id;
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            boolean invalidIdempotentRequest = status == 409
                    && failure.getResponseBodyAsString().contains("invalid_idempotent_request");
            boolean retryable = !invalidIdempotentRequest
                    && (status == 408 || status == 409 || status == 425 || status == 429 || status >= 500);
            throw new NotificationDeliveryException("Resend rejected the email with HTTP " + status, retryable, failure);
        } catch (NotificationDeliveryException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new NotificationDeliveryException("Resend request failed", true, failure);
        }
    }
}
