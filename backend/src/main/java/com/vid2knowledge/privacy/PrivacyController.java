package com.vid2knowledge.privacy;

import com.vid2knowledge.auth.IdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/privacy")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PrivacyController {
    private final PrivacyService privacy;
    private final IdentityService identities;

    public PrivacyController(PrivacyService privacy, IdentityService identities) {
        this.privacy = privacy;
        this.identities = identities;
    }

    @GetMapping("/export")
    public ResponseEntity<JsonNode> export(@AuthenticationPrincipal Jwt jwt) {
        var me = me(jwt);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=vid2knowledge-data.json")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(privacy.export(me.id()));
    }

    @PostMapping("/deletion-request")
    public PrivacyService.DeletionRequest requestDeletion(@AuthenticationPrincipal Jwt jwt) {
        return privacy.requestDeletion(me(jwt).id());
    }

    @GetMapping("/deletion-request")
    public PrivacyService.DeletionRequest activeDeletion(@AuthenticationPrincipal Jwt jwt) {
        return privacy.activeDeletion(me(jwt).id());
    }

    @DeleteMapping("/deletion-request")
    public ResponseEntity<Void> cancelDeletion(@AuthenticationPrincipal Jwt jwt) {
        privacy.cancelDeletion(me(jwt).id());
        return ResponseEntity.noContent().build();
    }

    private IdentityService.Me me(Jwt jwt) {
        Boolean verified = jwt.getClaim("email_verified");
        if (Boolean.FALSE.equals(verified)) {
            throw new IllegalArgumentException("A verified email is required");
        }
        String displayName = jwt.getClaimAsString("name");
        return identities.provision(jwt.getSubject(), jwt.getClaimAsString("email"), displayName);
    }
}
