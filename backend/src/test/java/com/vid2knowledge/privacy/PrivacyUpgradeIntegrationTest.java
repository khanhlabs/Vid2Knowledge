package com.vid2knowledge.privacy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PrivacyUpgradeIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");

    @Test
    void upgradesLegacyLocalCompletionWithoutInventingProviderEvidence() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).target("34").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, auth_subject, email, normalized_email, display_name, status) VALUES (?, ?, ?, ?, 'Deleted user', 'DELETED')",
                userId, "deleted:" + userId, userId + "@redacted.invalid", userId + "@redacted.invalid");
        jdbc.update("""
                INSERT INTO privacy_deletion_requests(id, user_id, state, requested_at, scheduled_for, completed_at)
                VALUES (?, ?, 'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '8 days',
                    CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP)
                """, requestId, userId);

        assertThat(Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM privacy_deletion_requests WHERE id = ?",
                String.class, requestId)).isEqualTo("IDENTITY_REVIEW");
        assertThat(jdbc.queryForObject("""
                SELECT completed_at IS NULL AND locally_erased_at IS NOT NULL
                    AND auth_provider_user_id IS NULL AND provider_deleted_at IS NULL
                FROM privacy_deletion_requests WHERE id = ?
                """, Boolean.class, requestId)).isTrue();
    }
}
