package com.vid2knowledge.privacy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "supabase-admin", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledAuthIdentityAdmin implements AuthIdentityAdmin {
    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void delete(UUID providerUserId) {
        throw new IllegalStateException("Supabase admin identity deletion is disabled");
    }
}
