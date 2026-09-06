package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/analytics")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class AnalyticsController {

    private final TenantAccessService access;
    private final OutcomeAnalyticsService analytics;

    public AnalyticsController(TenantAccessService access, OutcomeAnalyticsService analytics) {
        this.access = access;
        this.analytics = analytics;
    }

    @GetMapping("/cohorts/{cohortId}")
    public OutcomeAnalyticsService.OutcomeSummary cohort(
            @PathVariable UUID organizationId,
            @PathVariable UUID cohortId,
            Authentication authentication
    ) {
        access.require(
                organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR, CurrentActor.Role.REVIEWER
        );
        return analytics.summary(organizationId, cohortId);
    }
}
