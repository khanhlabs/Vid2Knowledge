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
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");

    @Test
    void migratesAnEmptyDatabaseAndCreatesTheTenantFoundation() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(31);
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
                    "cost_ledger",
                    "sources",
                    "rights_attestations",
                    "analysis_jobs",
                    "generation_runs",
                    "learning_packages",
                    "package_revisions",
                    "courses",
                    "course_modules",
                    "lessons",
                    "cohorts",
                    "assignments",
                    "assignment_recipients",
                    "learner_progress",
                    "quiz_attempts",
                    "quality_feedback",
                    "pricing_plans",
                    "billing_orders",
                    "payments",
                    "subscriptions",
                    "payment_webhook_inbox",
                    "program_launches",
                    "subscription_billing_periods",
                    "invoices",
                    "notification_jobs",
                    "flashcard_memory_states",
                    "flashcard_review_log",
                    "assessment_snapshots",
                    "assessment_attempts",
                    "assessment_attempt_answers",
                    "lesson_prerequisites",
                    "course_completion_rules",
                    "completion_certificates",
                    "embedding_chunks",
                    "qa_threads",
                    "qa_messages",
                    "qa_citations",
                    "qa_query_runs",
                    "content_templates",
                    "review_decisions",
                    "question_bank_items",
                    "credit_grants",
                    "refund_requests",
                    "billing_adjustments",
                    "renewal_attempts",
                    "privacy_deletion_requests",
                    "deleted_identity_blocks",
                    "organization_economic_profiles",
                    "account_cost_entries",
                    "legal_acceptances",
                    "integration_api_keys",
                    "webhook_endpoints",
                    "webhook_endpoint_secrets",
                    "webhook_deliveries",
                    "source_uploads",
                    "promotion_campaigns",
                    "promotion_campaign_events",
                    "promotion_redemptions",
                    "organization_billing_profiles",
                    "support_access_grants",
                    "support_access_events",
                    "organization_acquisition_attributions"
            ));
        }
    }
}
