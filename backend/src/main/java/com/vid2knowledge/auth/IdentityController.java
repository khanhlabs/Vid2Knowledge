package com.vid2knowledge.auth;

import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class IdentityController {

    private final IdentityService identities;
    private final InvitationService invitations;
    private final TenantAccessService access;

    public IdentityController(
            IdentityService identities,
            InvitationService invitations,
            TenantAccessService access
    ) {
        this.identities = identities;
        this.invitations = invitations;
        this.access = access;
    }

    @GetMapping("/me")
    public IdentityService.Me me(@AuthenticationPrincipal Jwt jwt) {
        return identities.provision(jwt.getSubject(), email(jwt), displayName(jwt));
    }

    @PostMapping("/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.Membership createOrganization(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateOrganizationRequest request,
            HttpServletRequest servletRequest
    ) {
        return identities.createOrganization(
                jwt.getSubject(), email(jwt), displayName(jwt), request.name(), request.slug(),
                servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString()
        );
    }

    @PostMapping("/organizations/{organizationId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationService.InvitationCreated invite(
            @PathVariable java.util.UUID organizationId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody InviteRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = access.require(
                organizationId,
                authentication,
                CurrentActor.Role.OWNER,
                CurrentActor.Role.ADMIN
        );
        return invitations.invite(
                actor, request.email(), request.role(),
                servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString()
        );
    }

    @PostMapping("/invitations/accept")
    public IdentityService.Membership acceptInvitation(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AcceptInvitationRequest request,
            HttpServletRequest servletRequest
    ) {
        return invitations.accept(
                request.token(), jwt.getSubject(), email(jwt), displayName(jwt),
                servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString()
        );
    }

    private static String email(Jwt jwt) {
        Boolean verified = jwt.getClaim("email_verified");
        if (Boolean.FALSE.equals(verified)) {
            throw new IllegalArgumentException("A verified email is required");
        }
        return jwt.getClaimAsString("email");
    }

    private static String displayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        return name == null ? jwt.getClaimAsString("user_name") : name;
    }

    public record InviteRequest(
            @NotBlank @Email String email,
            @NotNull CurrentActor.Role role
    ) {
    }

    public record AcceptInvitationRequest(@NotBlank String token) {
    }
}
