package com.vid2knowledge.integration;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/integrations")
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class IntegrationManagementController {
    private final TenantAccessService access;
    private final ApiKeyService apiKeys;
    private final WebhookEndpointService webhooks;

    public IntegrationManagementController(
            TenantAccessService access, ApiKeyService apiKeys, WebhookEndpointService webhooks
    ) {
        this.access = access;
        this.apiKeys = apiKeys;
        this.webhooks = webhooks;
    }

    @GetMapping("/api-keys")
    public List<ApiKeyService.ApiKeyView> apiKeys(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireManager(organizationId, authentication);
        return apiKeys.list(organizationId);
    }

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyService.ApiKeyCreated createApiKey(
            @PathVariable UUID organizationId,
            Authentication authentication,
            @Valid @RequestBody CreateApiKeyRequest request,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = requireManager(organizationId, authentication);
        return apiKeys.create(actor, request.name(), request.scopes(), request.expiresAt(), correlation(servletRequest));
    }

    @DeleteMapping("/api-keys/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeApiKey(
            @PathVariable UUID organizationId,
            @PathVariable UUID keyId,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = requireManager(organizationId, authentication);
        apiKeys.revoke(actor, keyId, correlation(servletRequest));
    }

    @GetMapping("/webhook-endpoints")
    public List<WebhookEndpointService.EndpointView> webhookEndpoints(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireManager(organizationId, authentication);
        return webhooks.list(organizationId);
    }

    @PostMapping("/webhook-endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookEndpointService.EndpointCreated createWebhookEndpoint(
            @PathVariable UUID organizationId,
            Authentication authentication,
            @Valid @RequestBody CreateWebhookRequest request,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = requireManager(organizationId, authentication);
        return webhooks.create(
                actor, request.name(), request.url(), request.eventTypes(), correlation(servletRequest)
        );
    }

    @PostMapping("/webhook-endpoints/{endpointId}/rotate-secret")
    public WebhookEndpointService.SecretRotated rotateWebhookSecret(
            @PathVariable UUID organizationId,
            @PathVariable UUID endpointId,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = requireManager(organizationId, authentication);
        return webhooks.rotate(actor, endpointId, correlation(servletRequest));
    }

    @DeleteMapping("/webhook-endpoints/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disableWebhookEndpoint(
            @PathVariable UUID organizationId,
            @PathVariable UUID endpointId,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = requireManager(organizationId, authentication);
        webhooks.disable(actor, endpointId, correlation(servletRequest));
    }

    @GetMapping("/webhook-endpoints/{endpointId}/deliveries")
    public List<WebhookEndpointService.DeliveryView> webhookDeliveries(
            @PathVariable UUID organizationId,
            @PathVariable UUID endpointId,
            Authentication authentication
    ) {
        requireManager(organizationId, authentication);
        return webhooks.deliveries(organizationId, endpointId);
    }

    private CurrentActor requireManager(UUID organizationId, Authentication authentication) {
        return access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN);
    }

    private static String correlation(HttpServletRequest request) {
        return request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString();
    }

    public record CreateApiKeyRequest(
            @NotBlank @Size(max = 120) String name,
            @NotEmpty Set<String> scopes,
            @Future Instant expiresAt
    ) {}

    public record CreateWebhookRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 2048) String url,
            @NotEmpty Set<String> eventTypes
    ) {}
}
