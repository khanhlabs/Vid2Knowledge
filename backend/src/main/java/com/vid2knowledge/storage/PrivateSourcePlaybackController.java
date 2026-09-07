package com.vid2knowledge.storage;

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
@RequestMapping("/api/v1/organizations/{organizationId}/sources/{sourceId}/playback")
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class PrivateSourcePlaybackController {
    private final TenantAccessService access;
    private final PrivateSourcePlaybackService playback;

    public PrivateSourcePlaybackController(TenantAccessService access, PrivateSourcePlaybackService playback) {
        this.access = access;
        this.playback = playback;
    }

    @GetMapping
    public PrivateSourcePlaybackService.PlaybackUrl get(
            @PathVariable UUID organizationId, @PathVariable UUID sourceId, Authentication authentication
    ) {
        CurrentActor actor = access.require(organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN, CurrentActor.Role.INSTRUCTOR,
                CurrentActor.Role.REVIEWER, CurrentActor.Role.LEARNER);
        return playback.create(actor, sourceId);
    }
}
