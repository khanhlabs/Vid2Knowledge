package com.vid2knowledge.billing;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/billing/profile")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class BillingProfileController {
    private final TenantAccessService access;
    private final BillingProfileService profiles;

    public BillingProfileController(TenantAccessService access, BillingProfileService profiles) {
        this.access = access;
        this.profiles = profiles;
    }

    @GetMapping
    public BillingProfileService.Profile profile(@PathVariable UUID organizationId, Authentication authentication) {
        access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN);
        return profiles.profile(organizationId);
    }

    @PutMapping
    public BillingProfileService.Profile update(
            @PathVariable UUID organizationId,
            @Valid @RequestBody UpdateRequest request,
            Authentication authentication
    ) {
        CurrentActor actor = access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN
        );
        return profiles.update(actor, new BillingProfileService.UpdateProfile(
                request.buyerType(), request.legalName(), request.taxIdentifier(), request.billingAddress(),
                request.billingEmail(), request.countryCode(), request.invoiceRequested(), request.expectedVersion()
        ));
    }

    public record UpdateRequest(
            @NotNull BillingProfileService.BuyerType buyerType,
            @NotBlank @Size(max = 240) String legalName,
            @Size(max = 32) String taxIdentifier,
            @NotBlank @Size(max = 500) String billingAddress,
            @NotBlank @Email @Size(max = 320) String billingEmail,
            @NotBlank @Size(min = 2, max = 2) String countryCode,
            boolean invoiceRequested,
            @Min(0) @Max(9_007_199_254_740_991L) long expectedVersion
    ) { }
}
