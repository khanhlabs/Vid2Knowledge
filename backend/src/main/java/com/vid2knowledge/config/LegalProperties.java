package com.vid2knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

@Validated
@ConfigurationProperties(prefix = "legal")
public record LegalProperties(
        String policySetVersion,
        String termsVersion,
        URI termsUrl,
        String privacyVersion,
        URI privacyUrl,
        String acceptableUseVersion,
        URI acceptableUseUrl,
        String aiNoticeVersion,
        URI aiNoticeUrl,
        boolean reviewed,
        boolean requireProductionReadiness
) {
    public LegalProperties {
        requireVersion(policySetVersion, "policy set");
        requireVersion(termsVersion, "terms");
        requireVersion(privacyVersion, "privacy");
        requireVersion(acceptableUseVersion, "acceptable use");
        requireVersion(aiNoticeVersion, "AI notice");
        requireUrl(termsUrl, "terms");
        requireUrl(privacyUrl, "privacy");
        requireUrl(acceptableUseUrl, "acceptable use");
        requireUrl(aiNoticeUrl, "AI notice");
    }

    private static void requireVersion(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,40}")) {
            throw new IllegalArgumentException(label + " version is invalid");
        }
    }

    private static void requireUrl(URI value, String label) {
        if (value == null || (!value.isAbsolute() && !value.toString().startsWith("/"))) {
            throw new IllegalArgumentException(label + " URL must be absolute or root-relative");
        }
    }
}
