package com.vid2knowledge.config;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalProductionReadinessTest {
    @Test
    void refusesUnreviewedOrNonHttpsPoliciesInProduction() {
        assertThatThrownBy(() -> new LegalProductionReadiness(properties(false, "https")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("reviewed");
        assertThatThrownBy(() -> new LegalProductionReadiness(properties(true, "http")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
        assertThatCode(() -> new LegalProductionReadiness(properties(true, "https"))).doesNotThrowAnyException();
    }

    private static LegalProperties properties(boolean reviewed, String scheme) {
        URI uri = URI.create(scheme + "://example.com/legal");
        return new LegalProperties("v1", "v1", uri, "v1", uri, "v1", uri, "v1", uri, reviewed, true);
    }
}
