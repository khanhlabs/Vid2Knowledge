package com.vid2knowledge.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/notification-preferences")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationPreferenceController {
    private final NotificationPreferenceService preferences;

    public NotificationPreferenceController(NotificationPreferenceService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public NotificationPreferenceService.Preferences get(@AuthenticationPrincipal Jwt jwt) {
        return preferences.get(jwt.getSubject());
    }

    @PatchMapping
    public NotificationPreferenceService.Preferences update(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateRequest requested
    ) {
        return preferences.update(jwt.getSubject(), new NotificationPreferenceService.Preferences(
                requested.productGuidanceEnabled(), requested.assignmentRemindersEnabled(), requested.marketingEnabled()
        ));
    }

    public record UpdateRequest(
            @NotNull Boolean productGuidanceEnabled,
            @NotNull Boolean assignmentRemindersEnabled,
            @NotNull Boolean marketingEnabled
    ) { }
}
