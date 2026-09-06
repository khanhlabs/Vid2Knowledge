package com.vid2knowledge.legal;

import com.vid2knowledge.config.LegalProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LegalServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");

    private JdbcTemplate jdbc;
    private UUID userId;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        ));
        userId = UUID.randomUUID();
        String identity = userId.toString();
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, 'Legal User')",
                userId, "legal-" + identity, identity + "@example.com", identity + "@example.com"
        );
    }

    @Test
    void recordsAnImmutableVersionSetAndRequiresReacceptanceWhenAnyPolicyChanges() {
        var service = new LegalService(jdbc, properties("v1"));
        assertThat(service.status(userId).accepted()).isFalse();
        assertThatThrownBy(() -> service.accept(
                userId, request("stale", true), "evidence"
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("versions changed");

        assertThat(service.accept(userId, request("v1", true), "request-evidence").accepted()).isTrue();
        assertThat(service.accept(userId, request("v1", true), "duplicate-evidence").accepted()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM legal_acceptances WHERE user_id = ?", Long.class, userId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT request_evidence_hash <> ? FROM legal_acceptances WHERE user_id = ?",
                Boolean.class, "request-evidence", userId
        )).isTrue();
        assertThat(new LegalService(jdbc, properties("v2")).status(userId).accepted()).isFalse();
    }

    @Test
    void interceptorBlocksBusinessApisUntilTheCurrentSetIsAccepted() {
        var service = new LegalService(jdbc, properties("v1"));
        var interceptor = new LegalAcceptanceInterceptor(jdbc, service);
        var request = new MockHttpServletRequest("POST", "/api/v1/organizations");
        request.setUserPrincipal(new JwtAuthenticationToken(
                Jwt.withTokenValue("test-token").header("alg", "none")
                        .subject("legal-" + userId).build()
        ));
        var response = new MockHttpServletResponse();

        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("policies must be accepted");
        service.accept(userId, request("v1", true), "interceptor-evidence");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();

        var privacyRequest = new MockHttpServletRequest("POST", "/api/v1/privacy/deletion-request");
        assertThat(interceptor.preHandle(privacyRequest, response, new Object())).isTrue();
    }

    private static LegalProperties properties(String version) {
        return new LegalProperties(
                version, version, URI.create("/legal/terms"), version, URI.create("/legal/privacy"),
                version, URI.create("/legal/acceptable-use"), version, URI.create("/legal/ai-notice"), false, false
        );
    }

    private static LegalService.AcceptanceRequest request(String version, boolean confirmed) {
        return new LegalService.AcceptanceRequest(version, version, version, version, version, confirmed);
    }
}
