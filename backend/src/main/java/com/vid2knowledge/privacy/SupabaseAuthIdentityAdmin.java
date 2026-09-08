package com.vid2knowledge.privacy;

import com.vid2knowledge.config.SupabaseAdminProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "supabase-admin", name = "enabled", havingValue = "true")
public class SupabaseAuthIdentityAdmin implements AuthIdentityAdmin {
    private final RestClient client;
    private final String secretKey;

    @Autowired
    public SupabaseAuthIdentityAdmin(SupabaseAdminProperties properties) {
        var requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.timeout()).followRedirects(HttpClient.Redirect.NEVER).build());
        requests.setReadTimeout(properties.timeout());
        this.client = RestClient.builder().baseUrl(properties.url().resolve("/auth/v1").toString())
                .requestFactory(requests).build();
        this.secretKey = properties.secretKey();
    }

    SupabaseAuthIdentityAdmin(RestClient client, String secretKey) {
        this.client = client;
        this.secretKey = secretKey;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void delete(UUID providerUserId) {
        try {
            var request = client.delete().uri("/admin/users/{id}", providerUserId)
                    .header("apikey", secretKey);
            // New opaque keys belong only in apikey; legacy service_role JWTs
            // also supply the Auth server's bearer authorization.
            if (!secretKey.startsWith("sb_secret_")) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey);
            }
            var response = request.retrieve().toBodilessEntity();
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IdentityDeletionException("Unexpected Supabase identity deletion response", false, null);
            }
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) return;
            throw new IdentityDeletionException(
                    "Supabase identity deletion failed with status " + failure.getStatusCode().value(),
                    failure.getStatusCode().is5xxServerError() || failure.getStatusCode().value() == 429,
                    failure
            );
        }
    }

    public static class IdentityDeletionException extends RuntimeException {
        private final boolean retryable;

        IdentityDeletionException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
