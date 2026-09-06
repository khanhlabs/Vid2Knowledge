package com.vid2knowledge.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void migratesAnEmptyDatabaseAndCreatesTheTenantFoundation() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        flyway.validate();

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); var result = connection.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"})) {
            var tables = new java.util.HashSet<String>();
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME"));
            }

            assertThat(tables).containsAll(Set.of(
                    "users",
                    "organizations",
                    "memberships",
                    "invitations",
                    "audit_logs",
                    "idempotency_records",
                    "outbox_events",
                    "entitlements",
                    "usage_reservations",
                    "usage_ledger",
                    "cost_ledger"
            ));
        }
    }
}
