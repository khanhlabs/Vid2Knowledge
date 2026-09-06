package com.vid2knowledge.integration;

import java.util.Set;
import java.util.UUID;

public record IntegrationPrincipal(UUID apiKeyId, UUID organizationId, Set<String> scopes) {
    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
