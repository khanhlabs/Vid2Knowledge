package com.vid2knowledge.integration;

import com.vid2knowledge.delivery.CatalogService;
import com.vid2knowledge.delivery.OutcomeAnalyticsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/integrations/v1/organizations/{organizationId}")
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class IntegrationApiController {
    private final CatalogService catalog;
    private final OutcomeAnalyticsService analytics;

    public IntegrationApiController(CatalogService catalog, OutcomeAnalyticsService analytics) {
        this.catalog = catalog;
        this.analytics = analytics;
    }

    @GetMapping("/courses")
    public List<CatalogService.Course> courses(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        require(authentication, organizationId, "catalog:read");
        return catalog.courses(organizationId);
    }

    @GetMapping("/analytics/overview")
    public OutcomeAnalyticsService.OrganizationOutcome overview(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        require(authentication, organizationId, "analytics:read");
        return analytics.overview(organizationId);
    }

    @GetMapping("/analytics/cohorts")
    public List<OutcomeAnalyticsService.CohortComparison> cohorts(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        require(authentication, organizationId, "analytics:read");
        return analytics.cohorts(organizationId);
    }

    private static IntegrationPrincipal require(
            Authentication authentication, UUID organizationId, String scope
    ) {
        if (authentication == null || !(authentication.getPrincipal() instanceof IntegrationPrincipal principal)
                || !principal.organizationId().equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API key cannot access this organization");
        }
        if (!principal.hasScope(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API key scope is insufficient");
        }
        return principal;
    }
}
