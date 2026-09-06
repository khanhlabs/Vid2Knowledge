package com.vid2knowledge.billing;

import com.vid2knowledge.config.PayOsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "payos", name = "enabled", havingValue = "true")
public class PayOsPaymentGateway implements PaymentGateway {

    private final PayOsProperties properties;
    private final RestClient client;

    public PayOsPaymentGateway(PayOsProperties properties) {
        this.properties = properties;
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .defaultHeader("x-client-id", properties.clientId())
                .defaultHeader("x-api-key", properties.apiKey())
                .build();
    }

    @Override
    public CheckoutLink createCheckout(long orderCode, long amountVnd, String description) {
        String signature = PayOsSignature.signCheckout(
                amountVnd,
                properties.cancelUrl().toString(),
                description,
                orderCode,
                properties.returnUrl().toString(),
                properties.checksumKey()
        );
        JsonNode response = client.post()
                .uri("/v2/payment-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "orderCode", orderCode,
                        "amount", amountVnd,
                        "description", description,
                        "cancelUrl", properties.cancelUrl().toString(),
                        "returnUrl", properties.returnUrl().toString(),
                        "expiredAt", Instant.now().plus(properties.checkoutTtl()).getEpochSecond(),
                        "signature", signature
                ))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !"00".equals(response.path("code").asText())) {
            throw new IllegalStateException("payOS did not create a checkout link");
        }
        JsonNode data = response.path("data");
        String id = data.path("paymentLinkId").asText();
        String checkoutUrl = data.path("checkoutUrl").asText();
        if (id.isBlank() || checkoutUrl.isBlank()) {
            throw new IllegalStateException("payOS returned an incomplete checkout link");
        }
        return new CheckoutLink(id, URI.create(checkoutUrl));
    }
}
