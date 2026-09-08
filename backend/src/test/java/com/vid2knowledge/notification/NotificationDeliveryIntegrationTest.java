package com.vid2knowledge.notification;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.IdentityService;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.CommercialProperties;
import com.vid2knowledge.config.NotificationProperties;
import com.vid2knowledge.sales.PilotLeadService;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
                URI.create("https://app.example.com"), "founder@example.com", key, 25, 3, Duration.ofMinutes(2)
        );
        cipher = new NotificationCipher(properties);
        queue = new JdbcNotificationQueue(jdbc, cipher, new ObjectMapper(), properties);
        jdbc.update("UPDATE learner_progress SET status = 'COMPLETED' WHERE status <> 'COMPLETED'");
        jdbc.update("DELETE FROM notification_jobs");
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
    void newPilotLeadAlertsFounderOnceWithoutPuttingProspectPiiInEmail() {
        var leads = new PilotLeadService(jdbc, transactions, queue);
        var request = new PilotLeadService.LeadRequest(
                "Nguyen Van Buyer", "buyer@private.example", "Private Academy",
                PilotLeadService.BuyerRole.OWNER, PilotLeadService.Minutes.BETWEEN_600_1499,
                PilotLeadService.Learners.BETWEEN_200_499, PilotLeadService.Goal.PROVE_LEARNING,
                "Sensitive sales context", PilotLeadService.Source.FOUNDER_OUTREACH, "founder-sep", true
        );

        var submitted = leads.submit(request, "pilot-alert-test-0001", "203.0.113.10|test-agent");
        leads.submit(request, "pilot-alert-test-0001", "203.0.113.10|test-agent");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_jobs WHERE pilot_lead_id = ?", Long.class, submitted.id()
        )).isEqualTo(1L);
        AtomicReference<OutboundEmail> delivered = new AtomicReference<>();
        assertThat(dispatcher(email -> {
            delivered.set(email);
            return "pilot-alert-1";
        }).dispatch("worker-sales-alert").sent()).isEqualTo(1);
        assertThat(delivered.get().recipient()).isEqualTo("founder@example.com");
        assertThat(delivered.get().subject()).contains("HOT");
        assertThat(delivered.get().text())
                .contains(submitted.id().toString(), "FOUNDER_OUTREACH")
                .doesNotContain("Nguyen Van Buyer", "buyer@private.example", "Private Academy", "Sensitive");
        assertThat(jdbc.queryForObject(
                "SELECT encrypted_payload FROM notification_jobs WHERE pilot_lead_id = ?",
                String.class, submitted.id()
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

    @Test
    void lifecycleMessagesAreScheduledAndCancelledAfterPreferenceOptOut() {
        UUID userId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        queue.onboarding(
                organizationId, userId, "owner@example.com", Instant.now().plus(Duration.ofDays(14))
        );
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_jobs WHERE organization_id = ?",
                Long.class, organizationId
        )).isEqualTo(3L);
        jdbc.update(
                """
                INSERT INTO notification_preferences(user_id, product_guidance_enabled)
                VALUES (?, FALSE)
                """,
                userId
        );
        jdbc.update(
                "UPDATE notification_jobs SET available_at = ? WHERE organization_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), organizationId
        );
        AtomicInteger sends = new AtomicInteger();
        var result = dispatcher(email -> {
            sends.incrementAndGet();
            return "must-not-send";
        }).dispatch("worker-opt-out");

        assertThat(result.cancelled()).isEqualTo(3);
        assertThat(sends).hasValue(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_jobs WHERE organization_id = ? AND state = 'CANCELLED'",
                Long.class, organizationId
        )).isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_jobs WHERE organization_id = ? AND encrypted_payload <> 'REDACTED'",
                Long.class, organizationId
        )).isZero();
    }

    @Test
    void notificationPreferencesDefaultSafelyAndKeepAnImmutableChangeRecord() {
        String subject = jdbc.queryForObject(
                """
                SELECT u.auth_subject FROM users u JOIN memberships m ON m.user_id = u.id
                WHERE m.organization_id = ? AND m.role = 'OWNER'
                """,
                String.class, organizationId
        );
        var preferences = new NotificationPreferenceService(jdbc);
        assertThat(preferences.get(subject)).isEqualTo(
                new NotificationPreferenceService.Preferences(true, true, false)
        );
        assertThat(preferences.update(
                subject, new NotificationPreferenceService.Preferences(false, true, false)
        )).isEqualTo(new NotificationPreferenceService.Preferences(false, true, false));
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM notification_preference_changes c
                JOIN users u ON u.id = c.user_id WHERE u.auth_subject = ?
                """,
                Long.class, subject
        )).isEqualTo(1L);
    }

    @Test
    void creatingATrialOrganizationSchedulesItsLifecycleMessages() {
        String suffix = UUID.randomUUID().toString();
        var commercial = new CommercialProperties(
                3_600, 25, Duration.ofDays(14), Duration.ofDays(7)
        );
        var identities = new IdentityService(jdbc, commercial, queue);

        var membership = transactions.execute(status -> identities.createOrganization(
                "new-owner-" + suffix,
                "new-owner-" + suffix + "@example.com",
                "New owner",
                "Khoa Cong nghe " + suffix,
                "khoa-cong-nghe-" + suffix,
                "test-correlation-" + suffix
        ));

        assertThat(membership).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_jobs WHERE organization_id = ?",
                Long.class, membership.id()
        )).isEqualTo(3L);
        assertThat(jdbc.queryForList(
                "SELECT notification_type FROM notification_jobs WHERE organization_id = ? ORDER BY notification_type",
                String.class, membership.id()
        )).containsExactly("ACTIVATION_NUDGE", "ONBOARDING_WELCOME", "TRIAL_EXPIRING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_jobs WHERE organization_id = ? AND encrypted_payload LIKE 'v1:%'",
                Long.class, membership.id()
        )).isEqualTo(3L);
    }

    @Test
    void dueLearningWorkIsScheduledOnceAndDeliveredThroughTheExistingQueue() {
        Instant now = Instant.now();
        ReminderFixture fixture = seedReminderFixture(now);
        var scheduler = new ReminderSchedulingService(
                jdbc, queue, java.time.Clock.fixed(now, ZoneOffset.UTC)
        );

        assertThat(scheduler.schedule()).isEqualTo(new ReminderSchedulingService.ScheduleResult(1, 1, 1));
        assertThat(scheduler.schedule()).isEqualTo(new ReminderSchedulingService.ScheduleResult(0, 0, 0));
        Instant reviewNotifyAt = jdbc.queryForObject(
                "SELECT available_at FROM notification_jobs WHERE organization_id = ? AND notification_type = 'REVIEW_DUE'",
                Timestamp.class, organizationId
        ).toInstant();
        assertThat(reviewNotifyAt.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).getHour()).isEqualTo(8);

        jdbc.update(
                "UPDATE notification_jobs SET available_at = ? WHERE organization_id = ?",
                Timestamp.from(now.minusSeconds(1)), organizationId
        );
        List<OutboundEmail> sent = new ArrayList<>();
        var result = dispatcher(email -> {
            sent.add(email);
            return "reminder-" + sent.size();
        }).dispatch("worker-reminders");

        assertThat(result.sent()).isEqualTo(3);
        assertThat(sent).extracting(OutboundEmail::recipient).containsOnly(fixture.email());
        assertThat(sent).extracting(OutboundEmail::subject)
                .anyMatch(subject -> subject.startsWith("Bài học mới:"))
                .anyMatch(subject -> subject.startsWith("Sắp đến hạn:"))
                .anyMatch(subject -> subject.contains("thẻ cần ôn"));
        assertThat(sent).extracting(OutboundEmail::text)
                .allMatch(text -> text.contains("/learn/" + organizationId));
    }

    @Test
    void reminderPreferenceAndCurrentProgressPreventStaleEmail() {
        Instant now = Instant.now();
        ReminderFixture optedOut = seedReminderFixture(now);
        jdbc.update(
                "INSERT INTO notification_preferences(user_id, assignment_reminders_enabled) VALUES (?, FALSE)",
                optedOut.userId()
        );
        var scheduler = new ReminderSchedulingService(
                jdbc, queue, java.time.Clock.fixed(now, ZoneOffset.UTC)
        );
        assertThat(scheduler.schedule()).isEqualTo(new ReminderSchedulingService.ScheduleResult(0, 0, 0));

        jdbc.update("UPDATE notification_preferences SET assignment_reminders_enabled = TRUE WHERE user_id = ?",
                optedOut.userId());
        assertThat(scheduler.schedule()).isEqualTo(new ReminderSchedulingService.ScheduleResult(1, 1, 1));
        jdbc.update(
                "UPDATE learner_progress SET status = 'COMPLETED', progress_percent = 100, completed_at = ? "
                        + "WHERE assignment_id = ? AND user_id = ?",
                Timestamp.from(now), optedOut.assignmentId(), optedOut.userId()
        );
        jdbc.update(
                "UPDATE notification_jobs SET available_at = ? WHERE organization_id = ?",
                Timestamp.from(now.minusSeconds(1)), organizationId
        );
        AtomicInteger sends = new AtomicInteger();
        var result = dispatcher(email -> {
            sends.incrementAndGet();
            return "must-not-send";
        }).dispatch("worker-stale-reminders");

        assertThat(result.cancelled()).isEqualTo(3);
        assertThat(sends).hasValue(0);
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

    private ReminderFixture seedReminderFixture(Instant now) {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        UUID learnerId = UuidV7Generator.generate();
        String email = "learner-" + learnerId + "@example.com";
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, ?)",
                learnerId, "learner-" + learnerId, email, email, "Learner"
        );
        jdbc.update(
                "INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'LEARNER')",
                organizationId, learnerId
        );
        UUID sourceId = UuidV7Generator.generate();
        jdbc.update(
                "INSERT INTO sources(id, organization_id, type, canonical_uri, created_by) VALUES (?, ?, 'TEXT', ?, ?)",
                sourceId, organizationId, "text://reminder/" + sourceId, ownerId
        );
        UUID packageId = UuidV7Generator.generate();
        UUID revisionId = UuidV7Generator.generate();
        jdbc.update(
                "INSERT INTO learning_packages(id, organization_id, source_id, publication_state) "
                        + "VALUES (?, ?, ?, 'PUBLISHED')",
                packageId, organizationId, sourceId
        );
        jdbc.update(
                "INSERT INTO package_revisions(id, organization_id, package_id, revision_no, content_json, "
                        + "edited_by, verification_state) VALUES (?, ?, ?, 1, '{}'::jsonb, ?, 'HUMAN_VERIFIED')",
                revisionId, organizationId, packageId, ownerId
        );
        jdbc.update("UPDATE learning_packages SET current_revision_id = ? WHERE id = ?", revisionId, packageId);
        UUID courseId = UuidV7Generator.generate();
        UUID moduleId = UuidV7Generator.generate();
        UUID lessonId = UuidV7Generator.generate();
        UUID cohortId = UuidV7Generator.generate();
        UUID assignmentId = UuidV7Generator.generate();
        jdbc.update(
                "INSERT INTO courses(id, organization_id, title, state, created_by) "
                        + "VALUES (?, ?, 'Retention Course', 'PUBLISHED', ?)",
                courseId, organizationId, ownerId
        );
        jdbc.update(
                "INSERT INTO course_modules(id, organization_id, course_id, title, position) "
                        + "VALUES (?, ?, ?, 'Module', 1)",
                moduleId, organizationId, courseId
        );
        jdbc.update(
                "INSERT INTO lessons(id, organization_id, module_id, package_id, title, position) "
                        + "VALUES (?, ?, ?, ?, 'Lesson', 1)",
                lessonId, organizationId, moduleId, packageId
        );
        jdbc.update(
                "INSERT INTO cohorts(id, organization_id, name, created_by) VALUES (?, ?, 'Cohort', ?)",
                cohortId, organizationId, ownerId
        );
        jdbc.update(
                """
                INSERT INTO assignments(
                    id, organization_id, cohort_id, lesson_id, package_revision_id, title,
                    available_at, due_at, state, created_by, published_at
                ) VALUES (?, ?, ?, ?, ?, 'Kỹ năng bán hàng', ?, ?, 'PUBLISHED', ?, ?)
                """,
                assignmentId, organizationId, cohortId, lessonId, revisionId,
                Timestamp.from(now.minus(Duration.ofDays(1))), Timestamp.from(now.plus(Duration.ofHours(12))),
                ownerId, Timestamp.from(now.minus(Duration.ofDays(1)))
        );
        jdbc.update(
                "INSERT INTO assignment_recipients(organization_id, assignment_id, user_id, assigned_at) "
                        + "VALUES (?, ?, ?, ?)",
                organizationId, assignmentId, learnerId, Timestamp.from(now.minus(Duration.ofDays(1)))
        );
        jdbc.update(
                "INSERT INTO learner_progress(organization_id, assignment_id, user_id, updated_at) VALUES (?, ?, ?, ?)",
                organizationId, assignmentId, learnerId, Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO flashcard_memory_states(
                    organization_id, assignment_id, user_id, package_revision_id, card_id, state,
                    stability_days, difficulty, due_at, last_reviewed_at, review_count, lapse_count,
                    algorithm_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'card-1', 'REVIEW', 2, 5, ?, ?, 1, 0, 'fsrs-6-v1', ?, ?)
                """,
                organizationId, assignmentId, learnerId, revisionId, Timestamp.from(now.minusSeconds(1)),
                Timestamp.from(now.minus(Duration.ofDays(2))), Timestamp.from(now), Timestamp.from(now)
        );
        return new ReminderFixture(learnerId, assignmentId, email);
    }

    private record ReminderFixture(UUID userId, UUID assignmentId, String email) { }
}
