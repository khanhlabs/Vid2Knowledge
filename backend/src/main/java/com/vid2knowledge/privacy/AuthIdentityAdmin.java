package com.vid2knowledge.privacy;

import java.util.UUID;

public interface AuthIdentityAdmin {
    boolean enabled();

    void delete(UUID providerUserId);
}
