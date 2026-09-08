package com.vid2knowledge.privacy;

import com.vid2knowledge.config.SupabaseAdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SupabaseAuthIdentityAdminTest {
    @Test
    void deletesWithServerCredentialsAndTreatsMissingIdentityAsIdempotentSuccess() {
        var builder = RestClient.builder().baseUrl("https://project.supabase.co/auth/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var admin = new SupabaseAuthIdentityAdmin(builder.build(), "server-secret");
        UUID id = UUID.randomUUID();
        server.expect(requestTo("https://project.supabase.co/auth/v1/admin/users/" + id))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("apikey", "server-secret"))
                .andExpect(header("Authorization", "Bearer server-secret"))
                .andRespond(withSuccess());
        server.expect(requestTo("https://project.supabase.co/auth/v1/admin/users/" + id))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        admin.delete(id);
        admin.delete(id);
        server.verify();
    }

    @Test
    void sendsOpaqueSecretOnlyInApiKeyHeader() {
        var builder = RestClient.builder().baseUrl("https://project.supabase.co/auth/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var admin = new SupabaseAuthIdentityAdmin(builder.build(), "sb_secret_test-only");
        server.expect(anything()).andExpect(header("apikey", "sb_secret_test-only"))
                .andExpect(headerDoesNotExist("Authorization")).andRespond(withSuccess());
        admin.delete(UUID.randomUUID());
        server.verify();
    }

    @Test
    void doesNotTreatRedirectAsDeletionSuccess() {
        var builder = RestClient.builder().baseUrl("https://project.supabase.co/auth/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var admin = new SupabaseAuthIdentityAdmin(builder.build(), "sb_secret_test-only");
        server.expect(anything()).andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://other.invalid"));
        assertThatThrownBy(() -> admin.delete(UUID.randomUUID()))
                .isInstanceOf(SupabaseAuthIdentityAdmin.IdentityDeletionException.class);
        server.verify();
    }

    @Test
    void rejectsProviderFailureInsteadOfClaimingDeletion() {
        var builder = RestClient.builder().baseUrl("https://project.supabase.co/auth/v1");
        var server = MockRestServiceServer.bindTo(builder).build();
        var admin = new SupabaseAuthIdentityAdmin(builder.build(), "server-secret");
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> admin.delete(UUID.randomUUID()))
                .isInstanceOf(SupabaseAuthIdentityAdmin.IdentityDeletionException.class);
        server.verify();
    }

    @Test
    void rejectsInsecureOrAmbiguousAdminOriginsAndUnboundedTimeout() {
        for (String url : new String[]{"http://project.supabase.co", "https://user:password@project.supabase.co",
                "https://project.supabase.co/auth/v1", "https://project.supabase.co?token=value"}) {
            assertThatThrownBy(() -> new SupabaseAdminProperties(true, URI.create(url), "secret", Duration.ofSeconds(10)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new SupabaseAdminProperties(true, URI.create("https://project.supabase.co"),
                "secret", Duration.ofSeconds(60))).isInstanceOf(IllegalArgumentException.class);
    }
}
