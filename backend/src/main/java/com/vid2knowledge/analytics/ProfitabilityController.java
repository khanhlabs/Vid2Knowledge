package com.vid2knowledge.analytics;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/analytics/profitability")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class ProfitabilityController {
    private final TenantAccessService access;
    private final ProfitabilityService profitability;

    public ProfitabilityController(TenantAccessService access, ProfitabilityService profitability) {
        this.access = access;
        this.profitability = profitability;
    }

    @GetMapping
    public ProfitabilityService.ProfitabilityReport report(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            Authentication authentication
    ) {
        requireOwner(organizationId, authentication);
        Instant end = to == null ? Instant.now() : to;
        return profitability.report(organizationId, from == null ? end.minus(30, ChronoUnit.DAYS) : from, end);
    }

    @GetMapping("/assumptions")
    public ProfitabilityService.EconomicProfile assumptions(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireOwner(organizationId, authentication);
        return profitability.profile(organizationId);
    }

    @PutMapping("/assumptions")
    public ProfitabilityService.EconomicProfile updateAssumptions(
            @PathVariable UUID organizationId,
            @RequestBody ProfitabilityService.EconomicProfile request,
            Authentication authentication
    ) {
        return profitability.updateProfile(requireOwner(organizationId, authentication), request);
    }

    @PostMapping("/costs")
    public ProfitabilityService.CostEntry addCost(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CostRequest request,
            Authentication authentication
    ) {
        return profitability.addCost(
                requireOwner(organizationId, authentication), request.category(), request.amountVnd(),
                request.incurredAt(), request.note()
        );
    }

    private CurrentActor requireOwner(UUID organizationId, Authentication authentication) {
        return access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN);
    }

    public record CostRequest(
            @NotBlank String category,
            @Positive long amountVnd,
            @NotNull Instant incurredAt,
            @NotBlank @Size(min = 3, max = 500) String note
    ) { }
}
