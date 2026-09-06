package com.vid2knowledge.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishesAReadOnlyVersionedTenantContractWithoutEventDrift() throws Exception {
        JsonNode contract;
        try (var input = getClass().getResourceAsStream("/integration-openapi.json")) {
            assertThat(input).as("integration-openapi.json").isNotNull();
            contract = mapper.readTree(input);
        }

        assertThat(contract.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(contract.path("info").path("version").asText()).isEqualTo("1.0.0");
        assertThat(contract.path("components").path("securitySchemes").path("businessApiKey")
                .path("scheme").asText()).isEqualTo("bearer");

        JsonNode paths = contract.path("paths");
        assertThat(paths.size()).isEqualTo(3);
        paths.properties().forEach(path -> {
            assertThat(path.getKey()).contains("/{organizationId}/");
            assertThat(path.getValue().has("get")).isTrue();
            assertThat(path.getValue().has("post")).isFalse();
            assertThat(ApiKeyService.ALLOWED_SCOPES)
                    .contains(path.getValue().path("get").path("x-required-scope").asText());
        });

        Set<String> documentedEvents = new HashSet<>();
        contract.path("components").path("schemas").path("WebhookEnvelope")
                .path("properties").path("type").path("enum")
                .forEach(node -> documentedEvents.add(node.asText()));
        assertThat(documentedEvents).isEqualTo(WebhookEndpointService.ALLOWED_EVENTS);

        Set<String> headers = new HashSet<>();
        contract.path("webhooks").path("businessEvent").path("post").path("parameters")
                .forEach(node -> headers.add(node.path("name").asText()));
        assertThat(headers).containsExactlyInAnyOrder(
                "X-V2K-Delivery", "X-V2K-Event", "X-V2K-Timestamp", "X-V2K-Signature"
        );
    }
}
