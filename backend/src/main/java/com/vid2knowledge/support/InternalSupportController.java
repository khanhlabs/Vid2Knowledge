package com.vid2knowledge.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/support/organizations/{organizationId}")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalSupportController {
    private final SupportAccessService support;

    public InternalSupportController(SupportAccessService support) {
        this.support = support;
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<SupportAccessService.Diagnostics> diagnostics(
            @PathVariable UUID organizationId,
            @RequestParam UUID grantId,
            Authentication authentication
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(support.diagnostics(
                        organizationId, grantId, authentication == null ? "internal" : authentication.getName()
                ));
    }
}
