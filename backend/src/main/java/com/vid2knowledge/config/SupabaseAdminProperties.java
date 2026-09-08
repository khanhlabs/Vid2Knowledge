package com.vid2knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "supabase-admin")
public record SupabaseAdminProperties(boolean enabled, URI url, String secretKey, Duration timeout) {
    public SupabaseAdminProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Supabase admin timeout must be positive and at most 10 seconds");
        }
        if (enabled && (url == null || !"https".equalsIgnoreCase(url.getScheme())
                || url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null
                || url.getFragment() != null || !(url.getPath().isEmpty() || "/".equals(url.getPath()))
                || secretKey == null || secretKey.isBlank())) {
            throw new IllegalArgumentException("Enabled Supabase admin deletion requires an HTTPS URL and secret key");
        }
    }
}
