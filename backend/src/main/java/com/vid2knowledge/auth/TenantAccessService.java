package com.vid2knowledge.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class TenantAccessService {

    private final JdbcTemplate jdbc;

    public TenantAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CurrentActor require(UUID organizationId, Authentication authentication, CurrentActor.Role... allowedRoles) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }
        List<CurrentActor> matches = jdbc.query(
                """
                SELECT u.id, m.organization_id, m.role
                FROM users u
                JOIN memberships m ON m.user_id = u.id
                JOIN organizations o ON o.id = m.organization_id
                WHERE u.auth_subject = ? AND u.status = 'ACTIVE'
                  AND m.organization_id = ? AND m.status = 'ACTIVE'
                  AND o.status = 'ACTIVE'
                """,
                (result, row) -> new CurrentActor(
                        result.getObject("id", UUID.class),
                        result.getObject("organization_id", UUID.class),
                        CurrentActor.Role.valueOf(result.getString("role"))
                ),
                authentication.getName(),
                organizationId
        );
        CurrentActor actor = matches.stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("Organization access denied"));
        if (allowedRoles.length > 0 && Arrays.stream(allowedRoles).noneMatch(role -> role == actor.role())) {
            throw new AccessDeniedException("Organization access denied");
        }
        return actor;
    }
}
