package com.vid2knowledge.config;

import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
@Profile("prod")
@ConditionalOnProperty(prefix = "legal", name = "require-production-readiness", havingValue = "true")
public class LegalProductionReadiness {
    public LegalProductionReadiness(LegalProperties legal) {
        if (!legal.reviewed()) {
            throw new IllegalStateException("Production legal policies must be reviewed and explicitly enabled");
        }
        for (String version : List.of(
                legal.policySetVersion(), legal.termsVersion(), legal.privacyVersion(),
                legal.acceptableUseVersion(), legal.aiNoticeVersion()
        )) {
            if (version.toLowerCase(java.util.Locale.ROOT).contains("draft")) {
                throw new IllegalStateException("Production legal policy versions must not be drafts");
            }
        }
        for (URI uri : List.of(
                legal.termsUrl(), legal.privacyUrl(), legal.acceptableUseUrl(), legal.aiNoticeUrl()
        )) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalStateException("Production legal policy URLs must use HTTPS");
            }
        }
    }
}
