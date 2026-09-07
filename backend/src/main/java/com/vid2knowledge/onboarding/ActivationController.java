package com.vid2knowledge.onboarding;

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
@RequestMapping("/api/v1/organizations/{organizationId}/activation")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class ActivationController {
    private final TenantAccessService access;
    private final ActivationService activation;

    public ActivationController(TenantAccessService access, ActivationService activation) {
        this.access = access;
        this.activation = activation;
    }

    @GetMapping
    public ActivationService.ActivationStatus status(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR, CurrentActor.Role.REVIEWER
        );
        return activation.status(organizationId);
    }
}
