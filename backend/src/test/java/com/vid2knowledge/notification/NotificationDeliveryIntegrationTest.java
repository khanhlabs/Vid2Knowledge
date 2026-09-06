package com.vid2knowledge.notification;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.NotificationProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class NotificationDeliveryIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private NotificationProperties properties;
    private NotificationCipher cipher;
    private JdbcNotificationQueue queue;
    private UUID organizationId;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.UTF_8));
        properties = new NotificationProperties(
                true, "re_test", "Vid2Knowledge <hello@example.com>", URI.create("https://api.resend.com"),
                URI.create("https://app.example.com"), key, 25, 3, Duration.ofMinutes(2)
        );
        cipher = new NotificationCipher(properties);
        queue = new JdbcNotificationQueue(jdbc, cipher, new ObjectMapper());
        organizationId = seedOrganization();
    }

    @Test
    void invitationPayloadIsEncryptedDeduplicatedDeliveredAndRedacted() {
        UUID invitationId = UuidV7Generator.generate();
        String token = "a-sensitive-invitation-token";
        queue.invitation(
                organizationId, invitationId, "student@example.com", CurrentActor.Role.LEARNER,
                token, Instant.now().plus(Duration.ofDays(7))
        );
        queue.invitation(
                organizationId, invitationId, "student@example.com", CurrentActor.Role.LEARNER,
                token, Instant.now().plus(Duration.ofDays(7))
        );
        String encrypted = jdbc.queryForObject(
                "SELECT encrypted_payload FROM notification_jobs WHERE organization_id = ?",
                String.class, organizationId
        );
        assertThat(encrypted).startsWith("v1:").doesNotContain(token);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_jobs WHERE organization_id = ?", Long.class, organizationId
        )).isEqualTo(1L);

        AtomicReference<OutboundEmail> delivered = new AtomicReference<>();
        NotificationSender sender = email -> {
            delivered.set(email);
            return "resend-message-1";
        };
        var result = dispatcher(sender).dispatch("test-worker");

        assertThat(result.sent()).isEqualTo(1);
        assertThat(delivered.get().html()).contains("accept-invitation?token=" + token);
        assertThat(delivered.get().idempotencyKey()).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM notification_jobs WHERE organization_id = ?", String.class, organizationId
        )).isEqualTo("SENT");
        assertThat(jdbc.queryForObject(
                "SELECT encrypted_payload FROM notification_jobs WHERE organization_id = ?",
                String.class, organizationId
        )).isEqualTo("REDACTED");
    }

    @Test
    void transientFailureRetriesAndEventuallySends() {
        queue.paymentReceipt(organizationId, UuidV7Generator.generate(), "V2K-100001", 790_000, Instant.now());
        AtomicInteger calls = new AtomicInteger();
        NotificationSender sender = email -> {
            if (calls.incrementAndGet() == 1) {
                throw new NotificationDeliveryException("temporary outage", true, null);
            }
            return "resend-message-2";
        };
        NotificationDispatcher dispatcher = dispatcher(sender);

        assertThat(dispatcher.dispatch("worker-1").retried()).isEqualTo(1);
        jdbc.update(
                "UPDATE notification_jobs SET available_at = ? WHERE organization_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), organizationId
        );
        assertThat(dispatcher.dispatch("worker-2").sent()).isEqualTo(1);
        assertThat(calls).hasValue(2);
        assertThat(jdbc.queryForObject(
                "SELECT attempt_count FROM notification_jobs WHERE organization_id = ?", Integer.class, organizationId
        )).isEqualTo(2);
    }

    @Test
    void permanentProviderFailureIsDeadLetteredImmediately() {
        queue.cancellationScheduled(organizationId, UuidV7Generator.generate(), Instant.now().plusSeconds(3600));
        NotificationSender sender = email -> {
            throw new NotificationDeliveryException("invalid recipient", false, null);
        };

        assertThat(dispatcher(sender).dispatch("worker-dead").deadLettered()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM notification_jobs WHERE organization_id = ?", String.class, organizationId
        )).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at IS NOT NULL FROM notification_jobs WHERE organization_id = ?",
                Boolean.class, organizationId
        )).isTrue();
    }

    private NotificationDispatcher dispatcher(NotificationSender sender) {
        return new NotificationDispatcher(
                jdbc, transactions, cipher, sender, properties, new ObjectMapper()
        );
    }

    private UUID seedOrganization() {
        UUID userId = UuidV7Generator.generate();
        UUID orgId = UuidV7Generator.generate();
        String suffix = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, ?)",
                userId, "notification-subject-" + suffix, "owner-" + suffix + "@example.com",
                "owner-" + suffix + "@example.com", "Owner"
        );
        jdbc.update(
                "INSERT INTO organizations(id, name, slug) VALUES (?, 'Trường Đại học Ví dụ', ?)",
                orgId, "notification-" + suffix
        );
        jdbc.update(
                "INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
                orgId, userId
        );
        return orgId;
    }
}
