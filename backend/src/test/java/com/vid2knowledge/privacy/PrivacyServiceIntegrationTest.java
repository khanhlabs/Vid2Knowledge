package com.vid2knowledge.privacy;

import com.vid2knowledge.auth.IdentityService;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.CommercialProperties;
import com.vid2knowledge.config.RetentionProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PrivacyServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");

    private JdbcTemplate jdbc;
    private PrivacyService privacy;
    private UUID userId;
    private UUID organizationId;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        privacy = new PrivacyService(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper()
        );
        userId = UuidV7Generator.generate();
        organizationId = UuidV7Generator.generate();
        String unique = userId.toString();
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, ?)",
                userId, "privacy-subject-" + unique, unique + "@example.com", unique + "@example.com", "Privacy User"
        );
        jdbc.update("INSERT INTO organizations(id, name, slug) VALUES (?, 'Privacy Org', ?)",
                organizationId, "privacy-" + organizationId);
        jdbc.update("INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
                organizationId, userId);
    }

    @Test
    void exportContainsOnlyTheAuthenticatedUsersPortableData() {
        var exported = privacy.export(userId);

        assertThat(exported.path("profile").path("email").asText()).isEqualTo(userId + "@example.com");
        assertThat(exported.path("memberships").size()).isEqualTo(1);
        assertThat(exported.path("memberships").get(0).path("organizationId").asText())
                .isEqualTo(organizationId.toString());
    }

    @Test
    void retentionRemovesExpiredOperationalDataButKeepsWebhookDedupeEvidence() {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        Instant old = now.minus(Duration.ofDays(120));
        UUID invoice = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO idempotency_records(
                    id, organization_id, operation, idempotency_key, request_hash, expires_at
                ) VALUES (?, ?, 'test', 'expired-key', 'hash', ?)
                """,
                UUID.randomUUID(), organizationId, Timestamp.from(now.minusSeconds(1))
        );
        jdbc.update(
                """
                INSERT INTO payment_webhook_inbox(
                    id, provider, event_key, signature, payload_json, received_at, processed_at
                ) VALUES (?, 'PAYOS', 'retention-event', 'secret-signature', '{"data":"sensitive"}'::jsonb, ?, ?)
                """,
                UUID.randomUUID(), Timestamp.from(old), Timestamp.from(old)
        );
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type, aggregate_id,
                    correlation_id, payload_json, occurred_at, published_at
                ) VALUES (?, ?, 'OldEvent', 1, 'Test', ?, 'retention', '{}'::jsonb, ?, ?)
                """,
                UUID.randomUUID(), organizationId, invoice, Timestamp.from(old), Timestamp.from(old)
        );
        jdbc.update(
                """
                INSERT INTO invitations(
                    id, organization_id, email, normalized_email, role, token_hash,
                    invited_by, expires_at, revoked_at, created_at
                ) VALUES (?, ?, ?, ?, 'LEARNER', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organizationId, "old@example.com", "old@example.com", UUID.randomUUID().toString(),
                userId, Timestamp.from(old.plus(Duration.ofDays(1))), Timestamp.from(old.plusSeconds(1)),
                Timestamp.from(old)
        );
        var service = new RetentionService(
                jdbc,
                new RetentionProperties(
                        Duration.ofDays(30), Duration.ofDays(90), Duration.ofDays(30),
                        Duration.ofDays(30), Duration.ofDays(90), Duration.ofDays(30),
                        Duration.ofDays(90)
                ),
                Clock.fixed(now, java.time.ZoneOffset.UTC)
        );

        var result = service.cleanup();

        assertThat(result.expiredIdempotencyRecords()).isEqualTo(1);
        assertThat(result.redactedPaymentWebhookPayloads()).isEqualTo(1);
        assertThat(result.deletedTerminalOutboxEvents()).isEqualTo(1);
        assertThat(result.deletedTerminalInvitations()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT signature FROM payment_webhook_inbox WHERE event_key = 'retention-event'", String.class
        )).isEqualTo("REDACTED");
        assertThat(jdbc.queryForObject(
                "SELECT payload_json = '{}'::jsonb FROM payment_webhook_inbox WHERE event_key = 'retention-event'",
                Boolean.class
        )).isTrue();
    }

    @Test
    void deletionRequiresOwnershipTransferThenPseudonymizesAndBlocksReprovisioning() {
        assertThatThrownBy(() -> privacy.requestDeletion(userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Transfer organization ownership");

        UUID replacement = UuidV7Generator.generate();
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, 'Owner')",
                replacement, "replacement-" + replacement, replacement + "@example.com", replacement + "@example.com"
        );
        jdbc.update("UPDATE memberships SET role = 'ADMIN' WHERE organization_id = ? AND user_id = ?",
                organizationId, userId);
        jdbc.update("INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
                organizationId, replacement);

        var request = privacy.requestDeletion(userId);
        assertThat(privacy.requestDeletion(userId).id()).isEqualTo(request.id());
        jdbc.update("UPDATE privacy_deletion_requests SET requested_at = ?, scheduled_for = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(8))),
                Timestamp.from(Instant.now().minusSeconds(1)), request.id());

        var result = privacy.processDueDeletions();

        assertThat(result.completed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("DELETED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM deleted_identity_blocks WHERE deletion_request_id = ?",
                Long.class, request.id()
        )).isEqualTo(1L);

        var identities = new IdentityService(jdbc, new CommercialProperties(
                0, 0, Duration.ofDays(14), Duration.ofDays(7)
        ));
        assertThatThrownBy(() -> identities.provision(
                "privacy-subject-" + userId, userId + "@example.com", "Returned"
        )).isInstanceOf(ResponseStatusException.class).hasMessageContaining("cannot be reprovisioned");
    }
}
