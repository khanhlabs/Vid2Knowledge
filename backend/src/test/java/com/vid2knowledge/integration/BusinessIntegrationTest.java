package com.vid2knowledge.integration;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.common.outbox.OutboxEvent;
import com.vid2knowledge.config.IntegrationProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class BusinessIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ApiKeyService apiKeys;
    private IntegrationSecretCipher cipher;
    private IntegrationProperties properties;
    private UUID organizationId;
    private UUID userId;
    private CurrentActor owner;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load().migrate();
    }

    @BeforeEach
    void setUp() {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("DELETE FROM webhook_deliveries");
        jdbc.update("DELETE FROM webhook_endpoint_secrets");
        jdbc.update("DELETE FROM webhook_endpoints");
        jdbc.update("DELETE FROM integration_api_keys");
        jdbc.update("DELETE FROM subscriptions");
        jdbc.update("DELETE FROM billing_orders");
        jdbc.update("DELETE FROM memberships");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM organizations");
        jdbc.update("DELETE FROM users");
        seedBusinessOrganization();
        apiKeys = new ApiKeyService(jdbc);
        String encryptionKey = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
        );
        properties = new IntegrationProperties(
                true, encryptionKey, 25, 3, Duration.ofMinutes(2), Duration.ofSeconds(10)
        );
        cipher = new IntegrationSecretCipher(properties);
    }

    @Test
    void apiKeyIsShownOnceHashedScopedTenantBoundAndImmediatelyRevocable() {
        var created = apiKeys.create(
                owner, "BI export", Set.of("analytics:read"), Instant.now().plus(Duration.ofDays(30)), "corr-key"
        );

        assertThat(created.token()).startsWith("v2k_live_");
        assertThat(jdbc.queryForObject(
                "SELECT token_hash FROM integration_api_keys WHERE id = ?", String.class, created.id()
        )).isEqualTo(RequestFingerprint.sha256(created.token())).doesNotContain(created.token());
        IntegrationPrincipal principal = apiKeys.authenticate(created.token());
        assertThat(principal.organizationId()).isEqualTo(organizationId);
        assertThat(principal.scopes()).containsExactly("analytics:read");
        assertThat(apiKeys.list(organizationId).getFirst().lastUsedAt()).isNotNull();

        apiKeys.revoke(owner, created.id(), "corr-revoke");
        assertThat(apiKeys.authenticate(created.token())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_id = ?", Long.class, created.id()
        )).isEqualTo(2L);
    }

    @Test
    void webhookSecretIsEncryptedVersionedAndDeliveryIsSignedAndDeduplicated() {
        var endpoints = new WebhookEndpointService(jdbc, apiKeys, cipher, new WebhookUrlPolicy());
        var endpoint = endpoints.create(
                owner, "Data warehouse", "https://8.8.8.8/webhook",
                Set.of("PackagePublished"), "corr-webhook"
        );
        assertThat(endpoint.signingSecret()).startsWith("whsec_");
        assertThat(jdbc.queryForObject(
                "SELECT encrypted_secret FROM webhook_endpoint_secrets WHERE endpoint_id = ? AND version = 1",
                String.class, endpoint.id()
        )).startsWith("v1:").doesNotContain(endpoint.signingSecret());

        UUID eventId = UuidV7Generator.generate();
        Instant occurredAt = Instant.now().minusSeconds(1);
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type, aggregate_id,
                    correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'PackagePublished', 1, 'LearningPackage', ?, 'corr-event',
                          CAST(? AS jsonb), ?, ?)
                """,
                eventId, organizationId, UuidV7Generator.generate(), "{\"packageId\":\"package-1\"}",
                Timestamp.from(occurredAt), Timestamp.from(occurredAt)
        );
        var outbox = new OutboxEvent(
                eventId, organizationId, "PackagePublished", 1, "LearningPackage",
                UuidV7Generator.generate(), "corr-event", null,
                "{\"packageId\":\"package-1\"}", occurredAt, 0
        );
        var fanout = new WebhookFanout(jdbc, new ObjectMapper());
        assertThat(fanout.fanout(outbox)).isEqualTo(1);
        assertThat(fanout.fanout(outbox)).isZero();

        AtomicReference<Sent> sent = new AtomicReference<>();
        WebhookHttpSender sender = (target, deliveryId, eventType, timestamp, signature, payload) -> {
            sent.set(new Sent(timestamp, signature, payload));
            return 204;
        };
        var dispatcher = new WebhookDispatcher(
                jdbc, transactions, cipher, new WebhookUrlPolicy(), sender, properties
        );
        var result = dispatcher.dispatch("integration-test");

        assertThat(result.delivered()).isEqualTo(1);
        assertThat(sent.get().payload()).contains("PackagePublished", "package-1", organizationId.toString());
        assertThat(sent.get().signature()).isEqualTo(WebhookDispatcher.signature(
                endpoint.signingSecret(), sent.get().timestamp(), sent.get().payload()
        ));
        assertThat(jdbc.queryForObject(
                "SELECT state FROM webhook_deliveries WHERE endpoint_id = ?", String.class, endpoint.id()
        )).isEqualTo("DELIVERED");

        var rotated = endpoints.rotate(owner, endpoint.id(), "corr-rotate");
        assertThat(rotated.secretVersion()).isEqualTo(2);
        assertThat(rotated.signingSecret()).isNotEqualTo(endpoint.signingSecret());
    }

    @Test
    void transientWebhookResponseRetriesButPermanentResponseDeadLetters() {
        var endpoints = new WebhookEndpointService(jdbc, apiKeys, cipher, new WebhookUrlPolicy());
        var endpoint = endpoints.create(
                owner, "Retry receiver", "https://8.8.4.4/hooks",
                Set.of("PaymentReceived"), "corr-retry"
        );
        var fanout = new WebhookFanout(jdbc, new ObjectMapper());
        OutboxEvent event = outboxEvent("PaymentReceived");
        assertThat(fanout.fanout(event)).isEqualTo(1);

        AtomicInteger calls = new AtomicInteger();
        WebhookHttpSender sender = (target, deliveryId, eventType, timestamp, signature, payload) ->
                calls.incrementAndGet() == 1 ? 429 : 400;
        var dispatcher = new WebhookDispatcher(
                jdbc, transactions, cipher, new WebhookUrlPolicy(), sender, properties
        );

        assertThat(dispatcher.dispatch("retry-one").retried()).isEqualTo(1);
        jdbc.update("UPDATE webhook_deliveries SET available_at = ? WHERE endpoint_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), endpoint.id());
        assertThat(dispatcher.dispatch("retry-two").deadLettered()).isEqualTo(1);
        assertThat(calls).hasValue(2);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM webhook_deliveries WHERE endpoint_id = ?", String.class, endpoint.id()
        )).isEqualTo("DEAD_LETTER");
    }

    @Test
    void webhookUrlPolicyRejectsLocalAndNonHttpsTargets() {
        var policy = new WebhookUrlPolicy();
        assertThatThrownBy(() -> policy.requirePublicHttps("http://example.com/hook"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> policy.requirePublicHttps("https://127.0.0.1/hook"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> policy.requirePublicHttps("https://[::1]/hook"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private OutboxEvent outboxEvent(String eventType) {
        UUID eventId = UuidV7Generator.generate();
        UUID aggregateId = UuidV7Generator.generate();
        Instant occurredAt = Instant.now().minusSeconds(1);
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type, aggregate_id,
                    correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, ?, 1, 'IntegrationTest', ?, 'corr-event', CAST(? AS jsonb), ?, ?)
                """,
                eventId, organizationId, eventType, aggregateId, "{\"id\":\"aggregate-1\"}",
                Timestamp.from(occurredAt), Timestamp.from(occurredAt)
        );
        return new OutboxEvent(
                eventId, organizationId, eventType, 1, "IntegrationTest", aggregateId,
                "corr-event", null, "{\"id\":\"aggregate-1\"}", occurredAt, 0
        );
    }

    private void seedBusinessOrganization() {
        userId = UuidV7Generator.generate();
        organizationId = UuidV7Generator.generate();
        UUID orderId = UuidV7Generator.generate();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO users(id, auth_subject, email, normalized_email, display_name)
                VALUES (?, ?, 'owner@example.com', 'owner@example.com', 'Owner')
                """,
                userId, "auth-" + userId
        );
        jdbc.update("INSERT INTO organizations(id, name, slug) VALUES (?, 'Business Org', ?)",
                organizationId, "business-" + organizationId.toString().substring(0, 8));
        jdbc.update("INSERT INTO memberships(organization_id, user_id, role, status) VALUES (?, ?, 'OWNER', 'ACTIVE')",
                organizationId, userId);
        jdbc.update(
                """
                INSERT INTO billing_orders(
                    id, organization_id, plan_id, order_code, amount_vnd, state, idempotency_key,
                    request_fingerprint, expires_at, paid_at, created_by
                ) VALUES (?, ?, '00000000-0000-7000-8000-000000000401', ?, 7990000, 'PAID', ?, ?, ?, ?, ?)
                """,
                orderId, organizationId, Math.abs(organizationId.getMostSignificantBits() % 800000000L) + 100000000L,
                "key-" + orderId, "fingerprint-" + orderId, Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now), userId
        );
        jdbc.update(
                """
                INSERT INTO subscriptions(
                    id, organization_id, plan_id, billing_order_id, status,
                    current_period_start, current_period_end
                ) VALUES (?, ?, '00000000-0000-7000-8000-000000000401', ?, 'ACTIVE', ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, orderId, Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plus(Duration.ofDays(30)))
        );
        owner = new CurrentActor(userId, organizationId, CurrentActor.Role.OWNER);
    }

    private record Sent(Instant timestamp, String signature, String payload) {}
}
