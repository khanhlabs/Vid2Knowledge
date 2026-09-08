package com.vid2knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sales-access")
public record SalesAccessProperties(
        String serviceAccountEmail,
        String oidcAudience
) { }
