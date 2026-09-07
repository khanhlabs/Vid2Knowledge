package com.vid2knowledge.support;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/support-access-grants")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class SupportAccessController {
    private final TenantAccessService access;
    private final SupportAccessService support;

    public SupportAccessController(TenantAccessService access, SupportAccessService support) {
        this.access = access;
        this.support = support;
    }

    @GetMapping
    public List<SupportAccessService.Grant> grants(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        access.require(organizationId, authentication, CurrentActor.Role.OWNER);
        return support.grants(organizationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupportAccessService.Grant create(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CreateRequest request,
            Authentication authentication
    ) {
        CurrentActor actor = access.require(organizationId, authentication, CurrentActor.Role.OWNER);
        return support.create(actor, new SupportAccessService.CreateGrant(
                request.reason(), request.ticketReference(), request.durationMinutes()
        ));
    }

    @DeleteMapping("/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @PathVariable UUID organizationId,
            @PathVariable UUID grantId,
            Authentication authentication
    ) {
        CurrentActor actor = access.require(organizationId, authentication, CurrentActor.Role.OWNER);
        support.revoke(actor, grantId);
    }

    public record CreateRequest(
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 120) String ticketReference,
            @Min(15) @Max(1_440) int durationMinutes
    ) { }
}
