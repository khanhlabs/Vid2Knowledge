package com.vid2knowledge.storage;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.ObjectStorageProperties;
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

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SourceUploadIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    private JdbcTemplate jdbc;
    private FakeStorage storage;
    private SourceUploadService service;
    private CurrentActor owner;

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
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM source_uploads");
        jdbc.update("DELETE FROM subscriptions");
        jdbc.update("DELETE FROM billing_orders");
        jdbc.update("DELETE FROM memberships");
        jdbc.update("DELETE FROM organizations");
        jdbc.update("DELETE FROM users");
        owner = seedPaidTeam();
        storage = new FakeStorage();
        var properties = new ObjectStorageProperties(
                true, URI.create("https://account.r2.cloudflarestorage.com"), "private",
                "access", "secret", 500_000_000, Duration.ofMinutes(15)
        );
        service = new SourceUploadService(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), storage,
                properties, Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void reservesTenantRandomizedPutAndVerifiesExactObjectOnce() {
        var reservation = service.reserve(owner, "training.mp4", "video/mp4", 12_345, "corr-reserve");
        assertThat(reservation.uploadUrl().getHost()).isEqualTo("upload.example.com");
        assertThat(reservation.requiredHeaders()).containsEntry("Content-Type", "video/mp4");
        assertThat(storage.key).startsWith("pending-source-uploads/organizations/" + owner.organizationId() + "/")
                .endsWith("/original.mp4").doesNotContain("training.mp4");

        storage.object = new ObjectStorage.StoredObject(12_345, "video/mp4", "\"etag-1\"");
        storage.prefix = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        var completed = service.complete(owner, reservation.id(), "corr-complete");
        assertThat(completed.state()).isEqualTo("STORAGE_VERIFIED");
        assertThat(service.complete(owner, reservation.id(), "corr-replay")).isEqualTo(completed);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_id = ?", Long.class, reservation.id()
        )).isEqualTo(2L);

        CurrentActor wrongTenant = new CurrentActor(owner.userId(), UUID.randomUUID(), CurrentActor.Role.OWNER);
        assertThatThrownBy(() -> service.complete(wrongTenant, reservation.id(), "corr-cross"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    }

    @Test
    void deletesAndRejectsSpoofedMediaAndBlocksUnpaidTenant() {
        var reservation = service.reserve(owner, "training.webm", "video/webm", 2_048, "corr-reserve");
        storage.object = new ObjectStorage.StoredObject(2_048, "video/webm", "etag");
        storage.prefix = "not-a-webm".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.complete(owner, reservation.id(), "corr-invalid"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("422");
        assertThat(storage.deletedKey).isEqualTo(storage.key);
        assertThat(service.list(owner.organizationId())).singleElement()
                .extracting(SourceUploadService.UploadView::state).isEqualTo("REJECTED");

        CurrentActor unpaid = seedOrganization("unpaid");
        assertThatThrownBy(() -> service.reserve(unpaid, "file.mp4", "video/mp4", 2_048, "corr-unpaid"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("402");
        assertThatThrownBy(() -> service.reserve(owner, "file.exe", "application/octet-stream", 2_048, "corr-type"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CurrentActor seedPaidTeam() {
        CurrentActor actor = seedOrganization("paid");
        UUID orderId = UuidV7Generator.generate();
        jdbc.update(
                """
                INSERT INTO billing_orders(
                    id, organization_id, plan_id, order_code, amount_vnd, state, idempotency_key,
                    request_fingerprint, expires_at, paid_at, created_by
                ) VALUES (?, ?, '00000000-0000-7000-8000-000000000201', ?, 2490000, 'PAID', ?, ?, ?, ?, ?)
                """,
                orderId, actor.organizationId(), 700_000_001L, "upload-order-" + orderId,
                "upload-fingerprint-" + orderId, Timestamp.from(NOW.plusSeconds(3600)), Timestamp.from(NOW),
                actor.userId()
        );
        jdbc.update(
                """
                INSERT INTO subscriptions(
                    id, organization_id, plan_id, billing_order_id, status, current_period_start, current_period_end
                ) VALUES (?, ?, '00000000-0000-7000-8000-000000000201', ?, 'ACTIVE', ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), orderId,
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.plus(Duration.ofDays(30)))
        );
        return actor;
    }

    private CurrentActor seedOrganization(String prefix) {
        UUID userId = UuidV7Generator.generate();
        UUID organizationId = UuidV7Generator.generate();
        String email = prefix + "-" + userId + "@example.com";
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, 'Owner')",
                userId, "auth-" + userId, email, email
        );
        jdbc.update("INSERT INTO organizations(id, name, slug) VALUES (?, 'Upload Org', ?)",
                organizationId, prefix + "-" + organizationId.toString().substring(0, 8));
        jdbc.update("INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
                organizationId, userId);
        return new CurrentActor(userId, organizationId, CurrentActor.Role.OWNER);
    }

    private static final class FakeStorage implements ObjectStorage {
        private String key;
        private String deletedKey;
        private StoredObject object;
        private byte[] prefix;

        @Override
        public URI presignPut(String objectKey, String contentType, long contentLength, Duration duration) {
            this.key = objectKey;
            return URI.create("https://upload.example.com/presigned");
        }

        @Override
        public StoredObject head(String objectKey) {
            assertThat(objectKey).isEqualTo(key);
            return object;
        }

        @Override
        public byte[] readPrefix(String objectKey, int length) {
            assertThat(objectKey).isEqualTo(key);
            return prefix;
        }

        @Override
        public void delete(String objectKey) {
            deletedKey = objectKey;
        }
    }
}
