package com.vid2knowledge.onboarding;

import com.vid2knowledge.common.id.UuidV7Generator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ActivationServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    private JdbcTemplate jdbc;
    private ActivationService activation;
    private UUID organizationId;
    private UUID ownerId;

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
        ownerId = UuidV7Generator.generate();
        organizationId = UuidV7Generator.generate();
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, 'Owner')",
                ownerId, "activation-owner-" + ownerId, ownerId + "@example.com", ownerId + "@example.com"
        );
        jdbc.update("INSERT INTO organizations(id, name, slug) VALUES (?, 'Activation Org', ?)",
                organizationId, "activation-" + organizationId.toString().substring(0, 8));
        jdbc.update("INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
                organizationId, ownerId);
        jdbc.update(
                """
                INSERT INTO entitlements(id, organization_id, metric, allowance, period_start, period_end)
                VALUES (?, ?, 'PROCESSED_VIDEO_SECOND', 3600, ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, Timestamp.from(NOW),
                Timestamp.from(NOW.plusSeconds(14 * 86_400L))
        );
        activation = new ActivationService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void exposesEvidenceBasedNextActionAndTrialDeadline() {
        var empty = activation.status(organizationId);
        assertThat(empty.completedSteps()).isZero();
        assertThat(empty.nextAction()).isEqualTo("ADD_SOURCE");
        assertThat(empty.trialEndsAt()).isEqualTo(NOW.plusSeconds(14 * 86_400L));

        seedCompletedJourney();

        var complete = activation.status(organizationId);
        assertThat(complete.activated()).isTrue();
        assertThat(complete.completedSteps()).isEqualTo(complete.totalSteps()).isEqualTo(6);
        assertThat(complete.nextAction()).isEqualTo("REVIEW_OUTCOMES");
        assertThat(complete.steps()).allMatch(ActivationService.Step::complete);
    }

    private void seedCompletedJourney() {
        UUID learnerId = UuidV7Generator.generate();
        UUID sourceId = UuidV7Generator.generate();
        UUID packageId = UuidV7Generator.generate();
        UUID revisionId = UuidV7Generator.generate();
        UUID courseId = UuidV7Generator.generate();
        UUID moduleId = UuidV7Generator.generate();
        UUID lessonId = UuidV7Generator.generate();
        UUID cohortId = UuidV7Generator.generate();
        UUID assignmentId = UuidV7Generator.generate();
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, 'Learner')",
                learnerId, "activation-learner-" + learnerId, learnerId + "@example.com", learnerId + "@example.com"
        );
        jdbc.update("INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'LEARNER')",
                organizationId, learnerId);
        jdbc.update(
                "INSERT INTO sources(id, organization_id, type, canonical_uri, created_by) VALUES (?, ?, 'TEXT', ?, ?)",
                sourceId, organizationId, "internal://activation/" + sourceId, ownerId
        );
        jdbc.update("INSERT INTO learning_packages(id, organization_id, source_id) VALUES (?, ?, ?)",
                packageId, organizationId, sourceId);
        jdbc.update(
                """
                INSERT INTO package_revisions(
                    id, organization_id, package_id, revision_no, content_json, edited_by, verification_state
                ) VALUES (?, ?, ?, 1, '{}'::jsonb, ?, 'HUMAN_VERIFIED')
                """,
                revisionId, organizationId, packageId, ownerId
        );
        jdbc.update("UPDATE learning_packages SET current_revision_id = ? WHERE id = ?", revisionId, packageId);
        jdbc.update("INSERT INTO courses(id, organization_id, title, created_by) VALUES (?, ?, 'Course', ?)",
                courseId, organizationId, ownerId);
        jdbc.update(
                "INSERT INTO course_modules(id, organization_id, course_id, title, position) VALUES (?, ?, ?, 'Module', 1)",
                moduleId, organizationId, courseId
        );
        jdbc.update(
                "INSERT INTO lessons(id, organization_id, module_id, package_id, title, position) VALUES (?, ?, ?, ?, 'Lesson', 1)",
                lessonId, organizationId, moduleId, packageId
        );
        jdbc.update("INSERT INTO cohorts(id, organization_id, name, created_by) VALUES (?, ?, 'Cohort', ?)",
                cohortId, organizationId, ownerId);
        jdbc.update("INSERT INTO cohort_members(organization_id, cohort_id, user_id) VALUES (?, ?, ?)",
                organizationId, cohortId, learnerId);
        jdbc.update(
                """
                INSERT INTO assignments(
                    id, organization_id, cohort_id, lesson_id, package_revision_id, title,
                    available_at, state, created_by, published_at
                ) VALUES (?, ?, ?, ?, ?, 'Assignment', ?, 'PUBLISHED', ?, ?)
                """,
                assignmentId, organizationId, cohortId, lessonId, revisionId, Timestamp.from(NOW), ownerId,
                Timestamp.from(NOW)
        );
        jdbc.update(
                "INSERT INTO assignment_recipients(organization_id, assignment_id, user_id) VALUES (?, ?, ?)",
                organizationId, assignmentId, learnerId
        );
        jdbc.update(
                """
                INSERT INTO learner_progress(
                    organization_id, assignment_id, user_id, status, progress_percent, completed_at
                ) VALUES (?, ?, ?, 'COMPLETED', 100, ?)
                """,
                organizationId, assignmentId, learnerId, Timestamp.from(NOW)
        );
        jdbc.update(
                """
                INSERT INTO program_launches(
                    id, organization_id, idempotency_key, request_hash, course_id,
                    cohort_id, assignment_id, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, "activation-launch", "activation-hash",
                courseId, cohortId, assignmentId, ownerId
        );
    }
}
