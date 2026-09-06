package com.vid2knowledge.auth;

import java.util.UUID;

public record CurrentActor(UUID userId, UUID organizationId, Role role) {
    public enum Role {
        OWNER,
        ADMIN,
        INSTRUCTOR,
        REVIEWER,
        LEARNER,
        SUPPORT_READONLY
    }
}
