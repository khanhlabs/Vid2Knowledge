package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
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

    @GetMapping("/overview")
    public OutcomeAnalyticsService.OrganizationOutcome overview(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireBuyer(organizationId, authentication);
        return analytics.overview(organizationId);
    }

    @GetMapping("/cohorts")
    public java.util.List<OutcomeAnalyticsService.CohortComparison> cohorts(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireBuyer(organizationId, authentication);
        return analytics.cohorts(organizationId);
    }

    @GetMapping("/cohorts/{cohortId}")
    public OutcomeAnalyticsService.OutcomeSummary cohort(
            @PathVariable UUID organizationId,
            @PathVariable UUID cohortId,
            Authentication authentication
    ) {
        requireBuyer(organizationId, authentication);
        return analytics.summary(organizationId, cohortId);
    }

    @GetMapping("/cohorts/{cohortId}/insights")
    public OutcomeAnalyticsService.CohortOutcome insights(
            @PathVariable UUID organizationId,
            @PathVariable UUID cohortId,
            Authentication authentication
    ) {
        requireBuyer(organizationId, authentication);
        return analytics.cohort(organizationId, cohortId);
    }

    @GetMapping(value = "/cohorts.csv", produces = "text/csv")
    public org.springframework.http.ResponseEntity<byte[]> exportCohorts(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireBuyer(organizationId, authentication);
        StringBuilder csv = new StringBuilder(
                "cohort_id,name,status,learners,assigned,started,completed,activation_rate,completion_rate,average_score_percent\r\n"
        );
        for (var cohort : analytics.cohorts(organizationId)) {
            csv.append(cohort.id()).append(',').append(csv(cohort.name())).append(',')
                    .append(csv(cohort.status())).append(',').append(cohort.learners()).append(',')
                    .append(cohort.assigned()).append(',').append(cohort.started()).append(',')
                    .append(cohort.completed()).append(',').append(cohort.activationRate()).append(',')
                    .append(cohort.completionRate()).append(',')
                    .append(cohort.averageScorePercent() == null ? "" : cohort.averageScorePercent()).append("\r\n");
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=cohort-outcomes.csv")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void requireBuyer(UUID organizationId, Authentication authentication) {
        access.require(
                organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR, CurrentActor.Role.REVIEWER
        );
    }

    private static String csv(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
