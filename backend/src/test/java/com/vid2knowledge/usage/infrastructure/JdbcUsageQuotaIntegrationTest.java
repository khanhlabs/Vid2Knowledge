package com.vid2knowledge.usage.infrastructure;

import com.vid2knowledge.analysis.application.RequestAnalysisCommand;
import com.vid2knowledge.analysis.application.RequestAnalysisService;
import com.vid2knowledge.analysis.application.AnalysisCompletionService;
import com.vid2knowledge.analysis.application.AnalysisLeaseLostException;
import com.vid2knowledge.analysis.application.RegisterYoutubeSourceService;
import com.vid2knowledge.analysis.application.YoutubeUrlParser;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.analysis.application.port.KnowledgeAiProvider;
import com.vid2knowledge.analysis.application.port.VideoMetadataProvider;
import com.vid2knowledge.analysis.domain.GenerationAccounting;
import com.vid2knowledge.analysis.infrastructure.JdbcAnalysisJobStore;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.common.outbox.JdbcOutboxStore;
import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.auth.IdentityService;
import com.vid2knowledge.auth.InvitationService;
import com.vid2knowledge.auth.OrganizationAdminService;
import com.vid2knowledge.config.CommercialProperties;
import com.vid2knowledge.config.AiCostProperties;
import com.vid2knowledge.delivery.CatalogService;
import com.vid2knowledge.delivery.AssessmentService;
import com.vid2knowledge.delivery.AuthoringService;
import com.vid2knowledge.delivery.LearnerService;
import com.vid2knowledge.delivery.LearningPathService;
import com.vid2knowledge.delivery.FlashcardReviewService;
import com.vid2knowledge.delivery.FsrsScheduler;
import com.vid2knowledge.delivery.GroundedQaService;
import com.vid2knowledge.delivery.OutcomeAnalyticsService;
import com.vid2knowledge.delivery.PackageWorkflowService;
import com.vid2knowledge.analysis.application.LearningPackageCodec;
import jakarta.validation.Validation;
import com.vid2knowledge.billing.BillingService;
import com.vid2knowledge.billing.BillingProfileService;
import com.vid2knowledge.billing.PaymentGateway;
import com.vid2knowledge.billing.PayOsSignature;
import com.vid2knowledge.billing.PromotionService;
import com.vid2knowledge.analytics.ProfitabilityService;
import com.vid2knowledge.analytics.AcquisitionAnalyticsService;
import com.vid2knowledge.config.PayOsProperties;
import com.vid2knowledge.notification.DisabledNotificationQueue;
import com.vid2knowledge.support.SupportAccessService;
import com.vid2knowledge.sales.PilotLeadService;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import com.vid2knowledge.usage.domain.QuotaExceededException;
import com.vid2knowledge.usage.domain.UsageMetric;
import com.vid2knowledge.usage.domain.UsageReservation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcUsageQuotaIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:0.8.6-pg18-bookworm");

    private JdbcTemplate jdbc;
    private JdbcUsageQuota quota;
    private TransactionTemplate transactions;
    private UUID organizationId;

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        quota = new JdbcUsageQuota(jdbc, transactions);
        organizationId = seedEntitlement(1_000);
    }

    @Test
    void reserveIsIdempotentAndRejectsParameterDrift() {
        UsageReservation first = quota.reserve(
                organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND,
                400,
                "analysis-1",
                Duration.ofMinutes(15),
                "correlation-1"
        );
        UsageReservation replay = quota.reserve(
                organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND,
                400,
                "analysis-1",
                Duration.ofMinutes(15),
                "correlation-2"
        );

        assertThat(replay.id()).isEqualTo(first.id());
        assertThatThrownBy(() -> quota.reserve(
                organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND,
                401,
                "analysis-1",
                Duration.ofMinutes(15),
                "correlation-3"
        )).isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations WHERE organization_id = ?", Long.class, organizationId))
                .isEqualTo(1L);
    }

    @Test
    void commitReleasesUnusedUnitsAndPreservesTheAllowance() {
        UsageReservation reservation = quota.reserve(
                organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND,
                800,
                "analysis-2",
                Duration.ofMinutes(15),
                "correlation-1"
        );

        UsageReservation committed = quota.commit(reservation.id(), 700, "correlation-2");
        UsageReservation next = quota.reserve(
                organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND,
                300,
                "analysis-3",
                Duration.ofMinutes(15),
                "correlation-3"
        );

        assertThat(committed.status()).isEqualTo(UsageReservation.Status.COMMITTED);
        assertThat(committed.committedUnits()).isEqualTo(700);
        assertThat(next.reservedUnits()).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_ledger WHERE reservation_id = ?", Long.class, reservation.id()))
                .isEqualTo(3L);
    }

    @Test
    void rejectsReservationBeyondTheRemainingAllowance() {
        quota.reserve(
                organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND,
                950,
                "analysis-4",
                Duration.ofMinutes(15),
                "correlation-1"
        );

        assertThatThrownBy(() -> quota.reserve(
                organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND,
                51,
                "analysis-5",
                Duration.ofMinutes(15),
                "correlation-2"
        )).isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void tenantAccessRequiresActiveMembershipAndAllowedRole() {
        String subject = jdbc.queryForObject(
                """
                SELECT u.auth_subject FROM users u
                JOIN memberships m ON m.user_id = u.id
                WHERE m.organization_id = ?
                """,
                String.class,
                organizationId
        );
        var authentication = UsernamePasswordAuthenticationToken.authenticated(subject, "n/a", java.util.List.of());
        var access = new TenantAccessService(jdbc);

        assertThat(access.require(organizationId, authentication, CurrentActor.Role.OWNER).role())
                .isEqualTo(CurrentActor.Role.OWNER);
        assertThatThrownBy(() -> access.require(organizationId, authentication, CurrentActor.Role.LEARNER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.require(UUID.randomUUID(), authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sourceRegistrationUsesServerVerifiedDurationAndRecordsRightsAudit() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class,
                organizationId
        );
        var actor = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        VideoMetadataProvider metadata = videoId -> new VideoMetadataProvider.VideoMetadata(
                "Verified title", 725, "vi"
        );
        var registration = new RegisterYoutubeSourceService(jdbc, new YoutubeUrlParser(), metadata);

        var first = registration.register(
                actor, "https://youtu.be/dQw4w9WgXcQ?feature=share",
                RegisterYoutubeSourceService.RightsBasis.PERMISSION, true, "correlation-source-1"
        );
        var replay = registration.register(
                actor, "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                RegisterYoutubeSourceService.RightsBasis.PERMISSION, true, "correlation-source-2"
        );

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(first.durationSeconds()).isEqualTo(725);
        assertThat(first.title()).isEqualTo("Verified title");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sources WHERE organization_id = ?", Long.class, organizationId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rights_attestations WHERE source_id = ?", Long.class, first.id()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE resource_id = ?", Long.class, first.id()))
                .isEqualTo(2L);
    }

    @Test
    void jwtIdentityProvisioningIsIdempotentAndOrganizationTrialIsCapped() {
        Instant now = Instant.parse("2026-09-06T00:00:00Z");
        var identities = new IdentityService(
                jdbc,
                new CommercialProperties(3_600, 20, Duration.ofDays(14), Duration.ofDays(7)),
                Clock.fixed(now, ZoneOffset.UTC)
        );

        var first = transactions.execute(status -> identities.provision(
                "jwt-subject-new", "Founder@Example.com", "Founder"
        ));
        var replay = transactions.execute(status -> identities.provision(
                "jwt-subject-new", "founder@example.com", "Founder Updated"
        ));
        var organization = transactions.execute(status -> identities.createOrganization(
                "jwt-subject-new", "founder@example.com", "Founder Updated",
                "Acme Academy", "acme-academy", "SAMPLE_COURSE", "correlation-org-1"
        ));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(organization.role()).isEqualTo(CurrentActor.Role.OWNER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE auth_subject = 'jwt-subject-new'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT allowance FROM entitlements WHERE organization_id = ? AND metric = 'PROCESSED_VIDEO_SECOND'",
                Long.class, organization.id()
        )).isEqualTo(3_600L);
        assertThat(jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (period_end - period_start))::bigint FROM entitlements " +
                        "WHERE organization_id = ? AND metric = 'PROCESSED_VIDEO_SECOND'",
                Long.class, organization.id()
        )).isEqualTo(Duration.ofDays(14).toSeconds());
        assertThat(jdbc.queryForObject(
                "SELECT allowance FROM entitlements WHERE organization_id = ? AND metric = 'QA_QUERY'",
                Long.class, organization.id()
        )).isEqualTo(20L);
        assertThat(jdbc.queryForObject(
                "SELECT source FROM organization_acquisition_attributions WHERE organization_id = ?",
                String.class, organization.id()
        )).isEqualTo("SAMPLE_COURSE");
        assertThat(new AcquisitionAnalyticsService(jdbc).report())
                .filteredOn(row -> row.source().equals("SAMPLE_COURSE"))
                .singleElement().satisfies(row -> {
                    assertThat(row.organizations()).isEqualTo(1);
                    assertThat(row.sourceStarted()).isZero();
                    assertThat(row.paidOrganizations()).isZero();
                    assertThat(row.netRevenueVnd()).isZero();
                });
    }

    @Test
    void buyerCanPublishAssignmentAndObserveServerGradedLearnerOutcome() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        UUID learnerId = seedLearner(organizationId);
        CurrentActor learner = new CurrentActor(learnerId, organizationId, CurrentActor.Role.LEARNER);
        UUID packageId = seedPublishedPackage(organizationId, ownerId);
        var catalog = new CatalogService(jdbc);
        var course = catalog.createCourse(owner, "Sales onboarding", "Core course", "catalog-1");
        var module = catalog.addModule(owner, course.id(), "Module one", 1, "catalog-2");
        var lesson = catalog.addLesson(owner, module.id(), packageId, "Lesson one", 1, "catalog-3");
        var cohort = catalog.createCohort(owner, "September", Instant.now(), Instant.now().plus(Duration.ofDays(30)), "catalog-4");
        catalog.addCohortMember(owner, cohort.id(), learnerId, "catalog-5");
        var assignment = catalog.createAssignment(
                owner, cohort.id(), lesson.id(), "Watch and practise",
                Instant.now().minusSeconds(1), Instant.now().plus(Duration.ofDays(7)), "catalog-6"
        );
        catalog.publishAssignment(owner, assignment.id(), "catalog-7");

        var learnerService = new LearnerService(jdbc, new ObjectMapper());
        var view = learnerService.start(learner, assignment.id());
        var result = learnerService.submit(
                learner, assignment.id(), java.util.List.of(1, 2), "attempt-key-123", "learner-1"
        );
        var replay = learnerService.submit(
                learner, assignment.id(), java.util.List.of(1, 2), "attempt-key-123", "learner-2"
        );
        var outcome = new OutcomeAnalyticsService(jdbc).summary(organizationId, cohort.id());

        assertThat(catalog.courses(organizationId)).extracting(CatalogService.Course::id).contains(course.id());
        assertThat(catalog.course(organizationId, course.id()).modules()).singleElement()
                .satisfies(item -> assertThat(item.lessons()).singleElement()
                        .satisfies(savedLesson -> assertThat(savedLesson.id()).isEqualTo(lesson.id())));
        assertThat(catalog.cohorts(organizationId)).singleElement()
                .satisfies(item -> assertThat(item.memberCount()).isEqualTo(1));
        assertThat(catalog.assignments(organizationId)).extracting(CatalogService.Assignment::id)
                .contains(assignment.id());
        assertThat(view.content().path("quiz").get(0).has("correctAnswerIndex")).isFalse();
        assertThat(view.content().path("quiz").get(0).has("explanation")).isFalse();
        assertThat(result.scorePercent()).isEqualTo(100);
        assertThat(replay.attemptId()).isEqualTo(result.attemptId());
        assertThatThrownBy(() -> learnerService.submit(
                learner, assignment.id(), java.util.List.of(0, 0), "attempt-key-123", "learner-3"
        )).isInstanceOf(IdempotencyConflictException.class);
        assertThat(outcome.assigned()).isEqualTo(1);
        assertThat(outcome.completed()).isEqualTo(1);
        assertThat(outcome.averageScorePercent()).isEqualTo(100);
        var analytics = new OutcomeAnalyticsService(jdbc);
        assertThat(analytics.overview(organizationId).completionRate()).isEqualTo(1.0);
        assertThat(analytics.cohorts(organizationId)).extracting(OutcomeAnalyticsService.CohortComparison::id)
                .contains(cohort.id());
        assertThatThrownBy(() -> analytics.summary(UUID.randomUUID(), cohort.id()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void learnerGetsAdaptiveFlashcardQueueWithIdempotentReviewHistory() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        UUID learnerId = seedLearner(organizationId);
        CurrentActor learner = new CurrentActor(learnerId, organizationId, CurrentActor.Role.LEARNER);
        UUID packageId = seedPublishedPackage(organizationId, ownerId);
        var launch = new CatalogService(jdbc).launchProgram(
                owner, "Ôn tập thích ứng", packageId, java.util.List.of(learnerId),
                Instant.now().minusSeconds(1), Instant.now().plus(Duration.ofDays(7)),
                "flashcard-launch", "flashcard-launch-correlation"
        );
        var reviews = new FlashcardReviewService(jdbc, transactions, new ObjectMapper());

        assertThat(reviews.due(learner)).extracting(FlashcardReviewService.DueCard::cardId)
                .containsExactly("flash-1", "flash-2");
        var first = reviews.review(
                learner, launch.assignmentId(), "flash-1", FsrsScheduler.Rating.GOOD, "flash-review-1"
        );
        var replay = reviews.review(
                learner, launch.assignmentId(), "flash-1", FsrsScheduler.Rating.GOOD, "flash-review-1"
        );
        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> reviews.review(
                learner, launch.assignmentId(), "flash-1", FsrsScheduler.Rating.EASY, "flash-review-1"
        )).isInstanceOf(IdempotencyConflictException.class);
        jdbc.update(
                """
                UPDATE flashcard_memory_states SET last_reviewed_at = ?, due_at = ?
                WHERE assignment_id = ? AND user_id = ? AND card_id = 'flash-1'
                """,
                Timestamp.from(Instant.now().minus(Duration.ofDays(3))),
                Timestamp.from(Instant.now().minusSeconds(1)), launch.assignmentId(), learnerId
        );
        var forgotten = reviews.review(
                learner, launch.assignmentId(), "flash-1", FsrsScheduler.Rating.AGAIN, "flash-review-2"
        );
        var summary = reviews.summary(learner);

        assertThat(forgotten.state()).isEqualTo("RELEARNING");
        assertThat(forgotten.lapseCount()).isEqualTo(1);
        assertThat(summary.totalCards()).isEqualTo(2);
        assertThat(summary.dueCards()).isEqualTo(1);
        assertThat(summary.reviewsToday()).isEqualTo(2);
        assertThat(summary.currentStreakDays()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flashcard_review_log WHERE assignment_id = ?", Long.class,
                launch.assignmentId()
        )).isEqualTo(2L);
        CurrentActor otherTenant = new CurrentActor(learnerId, UUID.randomUUID(), CurrentActor.Role.LEARNER);
        assertThat(reviews.due(otherTenant)).isEmpty();
        assertThatThrownBy(() -> reviews.review(
                otherTenant, launch.assignmentId(), "flash-1", FsrsScheduler.Rating.GOOD, "cross-tenant-review"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void examUsesImmutableRandomizedSnapshotAndMeasuresDelayedRecall() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        UUID learnerId = seedLearner(organizationId);
        CurrentActor learner = new CurrentActor(learnerId, organizationId, CurrentActor.Role.LEARNER);
        UUID packageId = seedPublishedPackage(organizationId, ownerId);
        var launch = new CatalogService(jdbc).launchProgram(
                owner, "Kiểm tra có bằng chứng", packageId, java.util.List.of(learnerId),
                Instant.now().minusSeconds(1), Instant.now().plus(Duration.ofDays(7)),
                "assessment-launch", "assessment-launch-correlation"
        );
        var mapper = new ObjectMapper();
        var assessments = new AssessmentService(jdbc, mapper);

        var snapshot = assessments.start(
                learner, launch.assignmentId(), AssessmentService.Mode.PRACTICE,
                "assessment-start-1", "assessment-correlation-1"
        );
        var replay = assessments.start(
                learner, launch.assignmentId(), AssessmentService.Mode.PRACTICE,
                "assessment-start-1", "assessment-correlation-2"
        );
        assertThat(replay.snapshotId()).isEqualTo(snapshot.snapshotId());
        assertThat(snapshot.questions()).allSatisfy(question -> {
            assertThat(question.has("correctAnswerIndex")).isFalse();
            assertThat(question.has("explanation")).isFalse();
            assertThat(question.path("options")).hasSize(4);
        });
        assertThatThrownBy(() -> assessments.start(
                learner, launch.assignmentId(), AssessmentService.Mode.DELAYED_RECALL,
                "assessment-start-1", "assessment-correlation-3"
        )).isInstanceOf(IdempotencyConflictException.class);

        String keyJson = jdbc.queryForObject(
                "SELECT answer_key_json::text FROM assessment_snapshots WHERE id = ?",
                String.class, snapshot.snapshotId()
        );
        var key = mapper.readTree(keyJson);
        var answers = new java.util.ArrayList<Integer>();
        key.forEach(question -> answers.add(question.path("correctAnswerIndex").asInt()));
        answers.set(0, (answers.getFirst() + 1) % 4);
        var result = assessments.submit(
                learner, snapshot.snapshotId(), answers, "assessment-submit-1", "assessment-correlation-4"
        );
        var resultReplay = assessments.submit(
                learner, snapshot.snapshotId(), answers, "assessment-submit-1", "assessment-correlation-5"
        );
        assertThat(result.scorePercent()).isEqualTo(50);
        assertThat(result.questions()).filteredOn(question -> !question.correct()).singleElement()
                .satisfies(question -> assertThat(question.explanation()).isNotBlank());
        assertThat(resultReplay).isEqualTo(result);
        assertThatThrownBy(() -> assessments.submit(
                learner, snapshot.snapshotId(), java.util.List.of(0, 0),
                "assessment-submit-2", "assessment-correlation-6"
        )).isInstanceOf(IdempotencyConflictException.class);

        var overview = assessments.overview(learner, launch.assignmentId());
        assertThat(overview.practiceAttempts()).isEqualTo(1);
        assertThat(overview.delayedRecallAvailable()).isFalse();
        assertThat(overview.weakAreas()).singleElement();
        new LearnerService(jdbc, mapper).feedback(
                learner, launch.assignmentId(), LearnerService.FeedbackKind.REPORT_ERROR,
                "QUIZ", "quiz-1", "Đáp án cần được kiểm tra", "analytics-feedback"
        );
        var buyerOutcome = new OutcomeAnalyticsService(jdbc).cohort(organizationId, launch.cohortId());
        assertThat(buyerOutcome.practiceAverageScorePercent()).isEqualTo(50);
        assertThat(buyerOutcome.reportedErrors()).isEqualTo(1);
        assertThat(buyerOutcome.openErrors()).isEqualTo(1);
        assertThat(buyerOutcome.weakTopics()).singleElement()
                .satisfies(topic -> {
                    assertThat(topic.wrongCount()).isEqualTo(1);
                    assertThat(topic.errorRate()).isEqualTo(1.0);
                });
        jdbc.update(
                "UPDATE assessment_attempts SET submitted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(4))), result.attemptId()
        );
        var delayed = assessments.start(
                learner, launch.assignmentId(), AssessmentService.Mode.DELAYED_RECALL,
                "assessment-delayed-1", "assessment-correlation-7"
        );
        assertThat(delayed.mode()).isEqualTo(AssessmentService.Mode.DELAYED_RECALL);
        assertThat(assessments.overview(learner, launch.assignmentId()).delayedRecallAvailable()).isTrue();

        CurrentActor otherTenant = new CurrentActor(learnerId, UUID.randomUUID(), CurrentActor.Role.LEARNER);
        assertThatThrownBy(() -> assessments.overview(otherTenant, launch.assignmentId()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> assessments.submit(
                otherTenant, snapshot.snapshotId(), answers,
                "cross-tenant-assessment", "assessment-correlation-8"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void publishedLearningPathEnforcesPrerequisitesAndIssuesVerifiableCertificate() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        UUID learnerId = seedLearner(organizationId);
        CurrentActor learner = new CurrentActor(learnerId, organizationId, CurrentActor.Role.LEARNER);
        UUID packageId = seedPublishedPackage(organizationId, ownerId);
        var catalog = new CatalogService(jdbc);
        var paths = new LearningPathService(jdbc, new ObjectMapper());
        var launch = catalog.launchProgram(
                owner, "Lộ trình bán hàng", packageId, java.util.List.of(learnerId),
                Instant.now().minusSeconds(1), Instant.now().plus(Duration.ofDays(7)),
                "path-launch", "path-correlation-1"
        );
        paths.configure(owner, launch.courseId(), 70, false, "path-correlation-2");

        var before = paths.paths(learner).getFirst();
        assertThat(before.totalLessons()).isEqualTo(1);
        assertThat(before.certificateEligible()).isFalse();
        assertThatThrownBy(() -> paths.issue(
                learner, launch.courseId(), launch.cohortId(), "path-correlation-3"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        new LearnerService(jdbc, new ObjectMapper()).submit(
                learner, launch.assignmentId(), java.util.List.of(1, 2),
                "path-assessment-submit", "path-correlation-4"
        );
        assertThat(paths.paths(learner).getFirst().certificateEligible()).isTrue();
        var certificate = paths.issue(
                learner, launch.courseId(), launch.cohortId(), "path-correlation-5"
        );
        var replay = paths.issue(
                learner, launch.courseId(), launch.cohortId(), "path-correlation-6"
        );
        var verified = paths.verify(certificate.verificationCode().toLowerCase());
        assertThat(replay.id()).isEqualTo(certificate.id());
        assertThat(verified.learnerName()).isNotBlank();
        assertThat(verified.courseTitle()).isEqualTo("Lộ trình bán hàng");
        assertThat(verified.revoked()).isFalse();
        paths.revoke(owner, certificate.id(), "Issued in error", "path-correlation-7");
        assertThat(paths.verify(certificate.verificationCode()).revoked()).isTrue();

        var secondCourse = catalog.createCourse(owner, "Prerequisite graph", "", "path-correlation-8");
        var module = catalog.addModule(owner, secondCourse.id(), "Module", 1, "path-correlation-9");
        var firstLesson = catalog.addLesson(
                owner, module.id(), packageId, "Lesson A", 1, "path-correlation-10"
        );
        var secondLesson = catalog.addLesson(
                owner, module.id(), packageId, "Lesson B", 2, "path-correlation-11"
        );
        paths.addPrerequisite(owner, secondLesson.id(), firstLesson.id(), "path-correlation-12");
        assertThatThrownBy(() -> paths.addPrerequisite(
                owner, firstLesson.id(), secondLesson.id(), "path-correlation-13"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        paths.publish(owner, secondCourse.id(), "path-correlation-14");
        var prerequisiteCohort = catalog.createCohort(
                owner, "Prerequisite cohort", Instant.now().minusSeconds(1),
                Instant.now().plus(Duration.ofDays(7)), "path-correlation-15"
        );
        catalog.addCohortMember(owner, prerequisiteCohort.id(), learnerId, "path-correlation-16");
        var firstAssignment = catalog.createAssignment(
                owner, prerequisiteCohort.id(), firstLesson.id(), "Lesson A",
                Instant.now().minusSeconds(1), Instant.now().plus(Duration.ofDays(7)), "path-correlation-17"
        );
        var secondAssignment = catalog.createAssignment(
                owner, prerequisiteCohort.id(), secondLesson.id(), "Lesson B",
                Instant.now().minusSeconds(1), Instant.now().plus(Duration.ofDays(7)), "path-correlation-18"
        );
        catalog.publishAssignment(owner, firstAssignment.id(), "path-correlation-19");
        catalog.publishAssignment(owner, secondAssignment.id(), "path-correlation-20");
        var learning = new LearnerService(jdbc, new ObjectMapper());
        assertThat(learning.assignments(learner)).filteredOn(item -> item.id().equals(secondAssignment.id()))
                .singleElement().satisfies(item -> assertThat(item.unlocked()).isFalse());
        assertThatThrownBy(() -> learning.start(learner, secondAssignment.id()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        learning.submit(
                learner, firstAssignment.id(), java.util.List.of(1, 2),
                "prerequisite-first-submit", "path-correlation-21"
        );
        assertThat(learning.start(learner, secondAssignment.id()).id()).isEqualTo(secondAssignment.id());
    }

    @Test
    void groundedQaUsesTenantScopedVectorsCitationsIdempotencyAndQuota() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        UUID learnerId = seedLearner(organizationId);
        CurrentActor learner = new CurrentActor(learnerId, organizationId, CurrentActor.Role.LEARNER);
        UUID packageId = seedPublishedPackage(organizationId, ownerId);
        var launch = new CatalogService(jdbc).launchProgram(
                owner, "Hỏi đáp có nguồn", packageId, java.util.List.of(learnerId),
                Instant.now().minusSeconds(1), Instant.now().plus(Duration.ofDays(7)),
                "qa-launch", "qa-correlation-1"
        );
        jdbc.update(
                """
                INSERT INTO entitlements(id, organization_id, metric, allowance, period_start, period_end)
                VALUES (?, ?, 'QA_QUERY', 5, ?, ?)
                """,
                UuidV7Generator.generate(), organizationId,
                Timestamp.from(Instant.now().minusSeconds(1)), Timestamp.from(Instant.now().plus(Duration.ofDays(7)))
        );
        AtomicInteger answersGenerated = new AtomicInteger();
        KnowledgeAiProvider provider = new KnowledgeAiProvider() {
            @Override
            public java.util.List<java.util.List<Double>> embed(
                    java.util.List<String> texts, EmbeddingPurpose purpose
            ) {
                return texts.stream().map(text -> {
                    var vector = new java.util.ArrayList<Double>(java.util.Collections.nCopies(768, 0.0));
                    vector.set(0, 1.0);
                    return java.util.List.copyOf(vector);
                }).toList();
            }

            @Override
            public AiGenerationResult generateGroundedAnswer(String prompt) {
                answersGenerated.incrementAndGet();
                assertThat(prompt).contains("CONTEXT:", "Không dùng kiến thức bên ngoài");
                return new AiGenerationResult(
                        "TEST", "grounded-test", "v1", 100, 20, 0, 50, 0,
                        "{\"answer\":\"Câu trả lời từ bài học.\",\"citations\":[1],\"insufficientEvidence\":false}"
                );
            }

            @Override
            public String embeddingModel() {
                return "embedding-test-768";
            }
        };
        var rate = new AiCostProperties.Rate(1_000_000, 2_000_000, 2_000_000);
        var qa = new GroundedQaService(
                jdbc, new ObjectMapper(), provider, quota,
                new AiCostProperties(rate, rate, 3, Duration.ofMinutes(5)),
                transactions, Clock.systemUTC()
        );

        var indexed = qa.index(owner, packageId, "qa-correlation-2");
        var answer = qa.ask(
                learner, launch.assignmentId(), "Khái niệm chính là gì?",
                "qa-request-123", "qa-correlation-3"
        );
        var replay = qa.ask(
                learner, launch.assignmentId(), "Khái niệm chính là gì?",
                "qa-request-123", "qa-correlation-4"
        );
        assertThat(indexed.chunks()).isGreaterThanOrEqualTo(5);
        assertThat(answer.insufficientEvidence()).isFalse();
        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.timestampSeconds()).isGreaterThanOrEqualTo(0);
            assertThat(citation.content()).isNotBlank();
        });
        assertThat(replay).isEqualTo(answer);
        assertThat(answersGenerated).hasValue(1);
        assertThatThrownBy(() -> qa.ask(
                learner, launch.assignmentId(), "Câu hỏi bị đổi",
                "qa-request-123", "qa-correlation-5"
        )).isInstanceOf(IdempotencyConflictException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM usage_reservations WHERE organization_id = ? AND metric = 'QA_QUERY'",
                Long.class, organizationId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM cost_ledger WHERE organization_id = ? AND operation LIKE 'QA_%'",
                Long.class, organizationId
        )).isEqualTo(3L);
        CurrentActor otherTenant = new CurrentActor(learnerId, UUID.randomUUID(), CurrentActor.Role.LEARNER);
        assertThatThrownBy(() -> qa.ask(
                otherTenant, launch.assignmentId(), "Cross tenant", "qa-cross-tenant", "qa-correlation-6"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void programLaunchIsAtomicIdempotentAndImmediatelyAssignable() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        UUID learnerId = seedLearner(organizationId);
        UUID packageId = seedPublishedPackage(organizationId, ownerId);
        var catalog = new CatalogService(jdbc);
        Instant availableAt = Instant.now().minusSeconds(1);
        Instant dueAt = Instant.now().plus(Duration.ofDays(7));

        var first = catalog.launchProgram(
                owner, "Pilot bán hàng", packageId, java.util.List.of(learnerId),
                availableAt, dueAt, "program-launch-1", "launch-correlation-1"
        );
        var replay = catalog.launchProgram(
                owner, "Pilot bán hàng", packageId, java.util.List.of(learnerId),
                availableAt, dueAt, "program-launch-1", "launch-correlation-2"
        );

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM courses WHERE organization_id = ? AND title = 'Pilot bán hàng'",
                Long.class, organizationId
        )).isEqualTo(1L);
        assertThat(new LearnerService(jdbc, new ObjectMapper()).assignments(
                new CurrentActor(learnerId, organizationId, CurrentActor.Role.LEARNER)
        )).extracting(LearnerService.AssignmentSummary::id).containsExactly(first.assignmentId());
        assertThatThrownBy(() -> catalog.launchProgram(
                owner, "Nội dung khác", packageId, java.util.List.of(learnerId),
                availableAt, dueAt, "program-launch-1", "launch-correlation-3"
        )).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void packageReviewWorkflowIsTenantScopedAndAudited() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        UUID packageId = seedPackage(organizationId, ownerId, "GENERATED");
        var packages = new PackageWorkflowService(
                jdbc,
                new LearningPackageCodec(new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator()),
                new ObjectMapper()
        );

        var original = packages.get(organizationId, packageId);
        String sourceUri = jdbc.queryForObject(
                "SELECT canonical_uri FROM sources WHERE id = ?", String.class, original.sourceId()
        );
        ObjectNode edited = validPackageContent(new ObjectMapper(), sourceUri);
        ((ObjectNode) edited.path("summary")).put("overview", "Bản chỉnh sửa đã được đối chiếu nguồn.");
        var saved = transactions.execute(status ->
                packages.saveDraft(owner, packageId, original.version(), edited, "package-draft-1")
        );
        assertThat(saved).isNotNull();
        assertThat(saved.revisionNo()).isEqualTo(original.revisionNo() + 1);
        assertThat(saved.state()).isEqualTo("DRAFT");
        assertThat(saved.content().path("summary").path("overview").asText()).contains("đối chiếu nguồn");
        assertThatThrownBy(() -> transactions.execute(status -> packages.saveDraft(
                owner, packageId, original.version(), edited, "package-draft-stale"
        ))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("412");

        packages.transition(owner, packageId, PackageWorkflowService.Transition.SUBMIT_REVIEW, "package-1");
        packages.transition(owner, packageId, PackageWorkflowService.Transition.APPROVE, "package-2");
        var published = packages.transition(
                owner, packageId, PackageWorkflowService.Transition.PUBLISH, "package-3"
        );

        assertThat(packages.list(organizationId)).extracting(PackageWorkflowService.PackageSummary::id)
                .contains(packageId);
        assertThat(published.state()).isEqualTo("PUBLISHED");
        assertThat(published.verificationState()).isEqualTo("HUMAN_VERIFIED");
        assertThatThrownBy(() -> packages.get(UUID.randomUUID(), packageId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_id = ?", Long.class, packageId
        )).isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM review_decisions WHERE package_id = ?", Long.class, packageId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM question_bank_items WHERE organization_id = ?", Long.class, organizationId
        )).isEqualTo(5L);
    }

    @Test
    void authoringTemplatesAreTenantScopedVersionedAndRejectionsRequireReasons() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        var mapper = new ObjectMapper();
        var authoring = new AuthoringService(jdbc, mapper);
        var profile = mapper.createObjectNode()
                .put("language", "vi").put("audience", "employee")
                .put("difficulty", "advanced").put("flashcards", 15)
                .put("quizQuestions", 8).put("tone", "formal");

        var template = authoring.createTemplate(owner, "Đào tạo bán hàng", profile, "template-1");
        assertThat(authoring.resolveProfile(organizationId, template.id(), null))
                .contains("\"flashcards\":15", "\"quizQuestions\":8");
        assertThatThrownBy(() -> authoring.resolveProfile(UUID.randomUUID(), template.id(), null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> authoring.updateTemplate(
                owner, template.id(), 99, "Tên mới", profile, "template-2"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        UUID packageId = seedPackage(organizationId, ownerId, "GENERATED");
        var packages = new PackageWorkflowService(
                jdbc, new LearningPackageCodec(mapper, Validation.buildDefaultValidatorFactory().getValidator()), mapper
        );
        packages.transition(owner, packageId, PackageWorkflowService.Transition.SUBMIT_REVIEW, "review-1");
        assertThat(authoring.reviewQueue(organizationId)).extracting(AuthoringService.ReviewQueueItem::packageId)
                .contains(packageId);
        assertThatThrownBy(() -> packages.transition(
                owner, packageId, PackageWorkflowService.Transition.REJECT, null, "review-2"
        )).isInstanceOf(IllegalArgumentException.class);
        packages.transition(
                owner, packageId, PackageWorkflowService.Transition.REJECT,
                "Cần đối chiếu lại timestamp", "review-3"
        );
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM review_decisions WHERE package_id = ?", String.class, packageId
        )).contains("timestamp");

        authoring.updateSettings(owner, false, "settings-1");
        assertThat(authoring.settings(organizationId).approvalRequired()).isFalse();
        UUID directPublish = seedPackage(organizationId, ownerId, "DRAFT");
        assertThat(packages.transition(
                owner, directPublish, PackageWorkflowService.Transition.PUBLISH, "publish-direct"
        ).verificationState()).isEqualTo("HUMAN_VERIFIED");
    }

    @Test
    void invitationIsEmailBoundSingleUseAndCreatesLearnerMembership() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        var commercial = new CommercialProperties(3_600, 20, Duration.ofDays(14), Duration.ofDays(7));
        var identities = new IdentityService(jdbc, commercial);
        var invitations = new InvitationService(jdbc, identities, commercial, new DisabledNotificationQueue());
        var invitation = transactions.execute(status -> invitations.invite(
                owner, "new-learner@example.com", CurrentActor.Role.LEARNER, "invite-1"
        ));

        assertThatThrownBy(() -> transactions.execute(status -> invitations.accept(
                invitation.token(), "wrong-subject", "wrong@example.com", "Wrong", "invite-2"
        ))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        var membership = transactions.execute(status -> invitations.accept(
                invitation.token(), "new-learner-subject", "new-learner@example.com", "New Learner", "invite-3"
        ));

        assertThat(membership.role()).isEqualTo(CurrentActor.Role.LEARNER);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.organization_id = ? AND u.auth_subject = ? AND m.role = 'LEARNER' AND m.status = 'ACTIVE'
                """,
                Long.class, organizationId, "new-learner-subject"
        )).isEqualTo(1L);
        assertThatThrownBy(() -> transactions.execute(status -> invitations.accept(
                invitation.token(), "new-learner-subject", "new-learner@example.com", "New Learner", "invite-4"
        ))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void ownerCanManageMembersWithoutRemovingTheLastOwner() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        UUID learnerId = seedLearner(organizationId);
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        var admin = new OrganizationAdminService(jdbc);

        var changed = admin.changeRole(owner, learnerId, CurrentActor.Role.INSTRUCTOR, "member-1");

        assertThat(changed.role()).isEqualTo(CurrentActor.Role.INSTRUCTOR);
        assertThatThrownBy(() -> admin.changeRole(
                owner, ownerId, CurrentActor.Role.ADMIN, "member-2"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> admin.deactivate(owner, ownerId, "member-3"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        admin.deactivate(owner, learnerId, "member-4");
        assertThat(admin.members(organizationId)).filteredOn(member -> member.id().equals(learnerId))
                .extracting(OrganizationAdminService.Member::status)
                .containsExactly("SUSPENDED");
    }

    @Test
    void ownershipTransferIsAtomicAndLeavesExactlyOneOwner() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        UUID nextOwnerId = seedLearner(organizationId);
        var owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);

        new OrganizationAdminService(jdbc).transferOwnership(owner, nextOwnerId, "owner-transfer-1");
        UUID thirdMemberId = seedLearner(organizationId);
        assertThatThrownBy(() -> new OrganizationAdminService(jdbc).transferOwnership(
                owner, thirdMemberId, "stale-owner-transfer"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM memberships WHERE organization_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
                Long.class, organizationId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT role FROM memberships WHERE organization_id = ? AND user_id = ?",
                String.class, organizationId, nextOwnerId
        )).isEqualTo("OWNER");
    }

    @Test
    void checkoutUsesImmutableServerPriceAndVerifiedWebhookGrantsEntitlementOnce() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        AtomicInteger gatewayCalls = new AtomicInteger();
        PaymentGateway gateway = (orderCode, amount, description) -> {
            int call = gatewayCalls.incrementAndGet();
            assertThat(amount).isEqualTo(790_000);
            return new PaymentGateway.CheckoutLink(
                    "pay-link-" + call, URI.create("https://pay.payos.vn/web/pay-link-" + call)
            );
        };
        var properties = new PayOsProperties(
                true, "client", "api", "secret", URI.create("https://api-merchant.payos.vn"),
                URI.create("https://app.example/success"), URI.create("https://app.example/cancel"),
                Duration.ofMinutes(30)
        );
        var billing = new BillingService(jdbc, transactions, gateway, properties, new DisabledNotificationQueue());
        UUID planId = UUID.fromString("00000000-0000-7000-8000-000000000101");

        var checkout = billing.checkout(owner, planId, "checkout-key-1");
        var replay = billing.checkout(owner, planId, "checkout-key-1");

        assertThat(replay.orderId()).isEqualTo(checkout.orderId());
        assertThat(gatewayCalls).hasValue(1);
        var mapper = new ObjectMapper();
        var data = mapper.createObjectNode()
                .put("orderCode", checkout.orderCode())
                .put("amount", checkout.amountVnd())
                .put("description", "V2K " + checkout.orderCode())
                .put("accountNumber", "123")
                .put("reference", "bank-reference-1")
                .put("transactionDateTime", "2026-09-06 10:00:00")
                .put("currency", "VND")
                .put("paymentLinkId", "pay-link-1")
                .put("code", "00")
                .put("desc", "success");
        var envelope = mapper.createObjectNode()
                .put("code", "00")
                .put("desc", "success")
                .put("success", true)
                .set("data", data);
        envelope.put("signature", PayOsSignature.signWebhook(data, "secret"));

        billing.processWebhook(envelope);
        billing.processWebhook(envelope);

        assertThat(jdbc.queryForObject("SELECT state FROM billing_orders WHERE id = ?", String.class, checkout.orderId()))
                .isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE billing_order_id = ?", Long.class, checkout.orderId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE billing_order_id = ?", Long.class, checkout.orderId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices WHERE billing_order_id = ?", Long.class, checkout.orderId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT allowance FROM entitlements WHERE organization_id = ? ORDER BY period_start DESC LIMIT 1",
                Long.class, organizationId
        )).isEqualTo(18_000L);
    }

    @Test
    void promotionIsMarginGuardedAttributedAndRedeemableOnlyOnce() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        Instant now = Instant.now();
        var promotionService = new PromotionService(jdbc);
        var campaign = promotionService.create(new PromotionService.CreateCampaign(
                "student_ref_10", "Student referral acquisition", 1_000, "CREATOR_MONTHLY",
                PromotionService.Channel.REFERRAL, "campus-ambassador-opaque-01",
                now.minus(Duration.ofMinutes(1)), now.plus(Duration.ofDays(30)), 1
        ), "operator-subject");
        UUID planId = UUID.fromString("00000000-0000-7000-8000-000000000101");
        AtomicInteger gatewayCalls = new AtomicInteger();
        BillingService billing = billing((orderCode, amount, description) -> {
            gatewayCalls.incrementAndGet();
            assertThat(amount).isEqualTo(711_000);
            return new PaymentGateway.CheckoutLink(
                    "promotion-link", URI.create("https://pay.payos.vn/web/promotion-link")
            );
        });

        var quote = billing.quote(organizationId, planId, " student_ref_10 ");
        assertThat(quote.listPriceVnd()).isEqualTo(790_000);
        assertThat(quote.discountVnd()).isEqualTo(79_000);
        assertThat(quote.amountVnd()).isEqualTo(711_000);
        assertThat(quote.promotionCode()).isEqualTo("STUDENT_REF_10");
        assertThat(quote.attributionChannel()).isEqualTo("REFERRAL");

        var checkout = billing.checkout(owner, planId, "student_ref_10", "promotion-checkout");
        var replay = billing.checkout(owner, planId, "student_ref_10", "promotion-checkout");
        assertThat(replay.orderId()).isEqualTo(checkout.orderId());
        assertThat(gatewayCalls).hasValue(1);
        pay(billing, checkout, "promotion-link", "promotion-payment-reference");

        assertThat(jdbc.queryForMap(
                "SELECT list_price_vnd, discount_vnd, amount_due_vnd FROM invoices WHERE billing_order_id = ?",
                checkout.orderId()
        )).containsEntry("list_price_vnd", 790_000L)
                .containsEntry("discount_vnd", 79_000L)
                .containsEntry("amount_due_vnd", 711_000L);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM promotion_redemptions WHERE billing_order_id = ?",
                String.class, checkout.orderId()
        )).isEqualTo("REDEEMED");

        var economics = promotionService.campaigns().stream()
                .filter(value -> value.id().equals(campaign.id())).findFirst().orElseThrow();
        assertThat(economics.reserved()).isZero();
        assertThat(economics.redeemed()).isEqualTo(1);
        assertThat(economics.attributedRevenueVnd()).isEqualTo(711_000);
        assertThat(economics.discountGrantedVnd()).isEqualTo(79_000);

        UUID otherOrganizationId = seedEntitlement(1_000);
        assertThatThrownBy(() -> billing.quote(otherOrganizationId, planId, "STUDENT_REF_10"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("capacity");
        assertThatThrownBy(() -> promotionService.create(new PromotionService.CreateCampaign(
                "too_deep", "Unsafe referral", 1_501, null,
                PromotionService.Channel.REFERRAL, null,
                now, now.plus(Duration.ofDays(1)), 10
        ), "operator-subject"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("margin guardrail");
    }

    @Test
    void paidInvoiceKeepsImmutableVietnameseBuyerSnapshot() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        var profiles = new BillingProfileService(jdbc);
        var first = profiles.update(owner, new BillingProfileService.UpdateProfile(
                BillingProfileService.BuyerType.BUSINESS,
                "Công ty TNHH Học Tốt", "0312345678", "12 Nguyễn Huệ, TP Hồ Chí Minh",
                "ketoan@hoctot.vn", "VN", true, 0
        ));
        assertThat(first.version()).isEqualTo(1);
        assertThatThrownBy(() -> profiles.update(owner, new BillingProfileService.UpdateProfile(
                BillingProfileService.BuyerType.BUSINESS,
                "Tên ghi đè", "0312345678", "12 Nguyễn Huệ, TP Hồ Chí Minh",
                "ketoan@hoctot.vn", "VN", true, 0
        ))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("reload");

        BillingService billing = billing((orderCode, amount, description) ->
                new PaymentGateway.CheckoutLink("tax-link", URI.create("https://pay.payos.vn/web/tax-link"))
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                billing, "requireProfileBeforeCheckout", true
        );
        var checkout = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000101"), "tax-snapshot-checkout"
        );
        pay(billing, checkout, "tax-link", "tax-snapshot-reference");

        profiles.update(owner, new BillingProfileService.UpdateProfile(
                BillingProfileService.BuyerType.BUSINESS,
                "Công ty TNHH Học Tốt - Tên mới", "0312345678", "99 Lê Lợi, TP Hồ Chí Minh",
                "invoice@hoctot.vn", "VN", true, 1
        ));
        var invoice = billing.invoices(organizationId).getFirst();
        assertThat(invoice.buyerLegalName()).isEqualTo("Công ty TNHH Học Tốt");
        assertThat(invoice.buyerTaxIdentifier()).isEqualTo("0312345678");
        assertThat(invoice.buyerAddress()).isEqualTo("12 Nguyễn Huệ, TP Hồ Chí Minh");
        assertThat(invoice.buyerEmail()).isEqualTo("ketoan@hoctot.vn");
        assertThat(invoice.billingProfileVersion()).isEqualTo(1L);
        assertThat(invoice.taxDocumentRequested()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE organization_id = ? AND action = 'BILLING_PROFILE_UPDATED'",
                Long.class, organizationId
        )).isEqualTo(2L);
    }

    @Test
    void productionCheckoutGuardRejectsMissingBillingProfile() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        BillingService billing = billing((orderCode, amount, description) ->
                new PaymentGateway.CheckoutLink("unused", URI.create("https://pay.payos.vn/web/unused"))
        );
        org.springframework.test.util.ReflectionTestUtils.setField(
                billing, "requireProfileBeforeCheckout", true
        );

        assertThatThrownBy(() -> billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000101"), null, "profile-required"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("billing information");
    }

    @Test
    void supportDiagnosticsRequireShortLivedExplicitOwnerGrantAndAreFullyAudited() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        var support = new SupportAccessService(jdbc);
        var grant = support.create(owner, new SupportAccessService.CreateGrant(
                "Investigate failed content generation reported by buyer", "SUP-1042", 30
        ));

        assertThat(grant.state()).isEqualTo("ACTIVE");
        assertThat(grant.expiresAt()).isAfter(grant.createdAt());
        var diagnostics = support.diagnostics(organizationId, grant.id(), "support-service-account");
        assertThat(diagnostics.organizationName()).isEqualTo("Test Organization");
        assertThat(diagnostics.activeMembers()).isEqualTo(1);
        assertThat(diagnostics.analysisJobsLast7Days()).isEmpty();
        assertThat(diagnostics.pendingBillingOrders()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM support_access_events WHERE support_access_grant_id = ?",
                Long.class, grant.id()
        )).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT support_subject_hash FROM support_access_events WHERE support_access_grant_id = ? AND action = 'ACCESSED'",
                String.class, grant.id()
        )).hasSize(64).doesNotContain("support-service-account");

        support.create(owner, new SupportAccessService.CreateGrant("Second bounded diagnostic session", null, 15));
        support.create(owner, new SupportAccessService.CreateGrant("Third bounded diagnostic session", null, 15));
        assertThatThrownBy(() -> support.create(owner, new SupportAccessService.CreateGrant(
                "Too many concurrent grants", null, 15
        ))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("At most 3");

        support.revoke(owner, grant.id());
        assertThatThrownBy(() -> support.diagnostics(organizationId, grant.id(), "support-service-account"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("explicit support grant");
        assertThat(support.grants(organizationId)).filteredOn(item -> item.id().equals(grant.id()))
                .singleElement().extracting(SupportAccessService.Grant::state).isEqualTo("REVOKED");
        assertThatThrownBy(() -> support.create(owner, new SupportAccessService.CreateGrant(
                "Unsafe long-lived access", null, 1_441
        ))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void signedUnknownWebhookIsRetainedWithoutGrantingAccess() {
        PaymentGateway gateway = (orderCode, amount, description) ->
                new PaymentGateway.CheckoutLink("unused", URI.create("https://pay.payos.vn/web/unused"));
        var billing = billing(gateway);
        var mapper = new ObjectMapper();
        var data = mapper.createObjectNode()
                .put("orderCode", 999_999_999L)
                .put("amount", 10_000)
                .put("reference", "payos-confirmation-sample")
                .put("currency", "VND")
                .put("paymentLinkId", "sample-link")
                .put("code", "00");
        var envelope = mapper.createObjectNode()
                .put("code", "00")
                .put("success", true)
                .set("data", data);
        envelope.put("signature", PayOsSignature.signWebhook(data, "secret"));

        billing.processWebhook(envelope);

        assertThat(jdbc.queryForObject(
                "SELECT error_code FROM payment_webhook_inbox WHERE event_key = ?",
                String.class, "payos-confirmation-sample|999999999"
        )).isEqualTo("REJECTED_400");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE provider_reference = ?",
                Long.class, "payos-confirmation-sample"
        )).isZero();
    }

    @Test
    void reconciliationRecoversPaidOrderAndCreatesRevenueRecordsOnce() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        class ReconcilingGateway implements PaymentGateway {
            @Override
            public CheckoutLink createCheckout(long orderCode, long amountVnd, String description) {
                return new CheckoutLink("reconcile-link", URI.create("https://pay.payos.vn/web/reconcile-link"));
            }

            @Override
            public java.util.Optional<PaymentStatus> getPayment(long orderCode) {
                return java.util.Optional.of(new PaymentStatus(
                        orderCode, 790_000, 790_000, "PAID", "reconcile-link"
                ));
            }
        }
        var billing = billing(new ReconcilingGateway());
        var checkout = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000101"), "reconcile-checkout"
        );

        var first = billing.reconcilePendingPayments();
        var replay = billing.reconcilePendingPayments();

        assertThat(first.paid()).isEqualTo(1);
        assertThat(replay.paid()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE billing_order_id = ?", Long.class, checkout.orderId()
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoices WHERE billing_order_id = ?", Long.class, checkout.orderId()
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscription_billing_periods WHERE billing_order_id = ?",
                Long.class, checkout.orderId()
        )).isEqualTo(1L);
    }

    @Test
    void failedCheckoutProviderCallReleasesClaimForSafeRetry() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        AtomicInteger calls = new AtomicInteger();
        PaymentGateway gateway = (orderCode, amount, description) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary provider outage");
            }
            return new PaymentGateway.CheckoutLink("recovered-link", URI.create("https://pay.payos.vn/web/recovered"));
        };
        var billing = billing(gateway);
        UUID planId = UUID.fromString("00000000-0000-7000-8000-000000000101");

        assertThatThrownBy(() -> billing.checkout(owner, planId, "recoverable-checkout"))
                .isInstanceOf(IllegalStateException.class);
        var recovered = billing.checkout(owner, planId, "recoverable-checkout");

        assertThat(recovered.checkoutUrl()).endsWith("/recovered");
        assertThat(calls).hasValue(2);
        assertThat(jdbc.queryForObject(
                "SELECT checkout_claim_token IS NULL FROM billing_orders WHERE id = ?",
                Boolean.class, recovered.orderId()
        )).isTrue();
    }

    @Test
    void samePlanRenewalExtendsOneSubscriptionAndKeepsEachPaidPeriod() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        AtomicInteger links = new AtomicInteger();
        PaymentGateway gateway = (orderCode, amount, description) -> {
            String id = "renew-link-" + links.incrementAndGet();
            return new PaymentGateway.CheckoutLink(id, URI.create("https://pay.payos.vn/web/" + id));
        };
        var billing = billing(gateway);
        UUID planId = UUID.fromString("00000000-0000-7000-8000-000000000101");

        var first = billing.checkout(owner, planId, "renew-first");
        pay(billing, first, "renew-link-1", "renew-reference-1");
        var second = billing.checkout(owner, planId, "renew-second");
        pay(billing, second, "renew-link-2", "renew-reference-2");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE organization_id = ?", Long.class, organizationId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscription_billing_periods WHERE organization_id = ?",
                Long.class, organizationId
        )).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoices WHERE organization_id = ? AND state = 'PAID'",
                Long.class, organizationId
        )).isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                """
                SELECT EXTRACT(EPOCH FROM (current_period_end - current_period_start)) > 5000000
                FROM subscriptions WHERE organization_id = ?
                """,
                Boolean.class, organizationId
        )).isTrue();
    }

    @Test
    void cancellingSubscriptionWritesAuditAndOutboxAtomically() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        PaymentGateway gateway = (orderCode, amount, description) ->
                new PaymentGateway.CheckoutLink("cancel-link", URI.create("https://pay.payos.vn/web/cancel-link"));
        var billing = billing(gateway);
        var checkout = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000101"), "cancel-checkout"
        );
        pay(billing, checkout, "cancel-link", "cancel-reference");
        UUID subscriptionId = billing.subscription(organizationId).orElseThrow().id();

        billing.cancelAtPeriodEnd(owner, subscriptionId, "cancel-correlation");

        assertThat(jdbc.queryForObject(
                "SELECT cancel_at_period_end FROM subscriptions WHERE id = ?", Boolean.class, subscriptionId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM audit_logs
                WHERE resource_id = ? AND action = 'SUBSCRIPTION_CANCEL_AT_PERIOD_END'
                """,
                Long.class, subscriptionId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND event_type = 'SubscriptionCancellationScheduled'
                """,
                Long.class, subscriptionId
        )).isEqualTo(1L);
    }

    @Test
    void planChangeWaitsUntilRenewalAndNeverChargesAFullSecondPlanEarly() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        AtomicInteger links = new AtomicInteger();
        PaymentGateway gateway = (orderCode, amount, description) -> {
            String id = "change-link-" + links.incrementAndGet();
            return new PaymentGateway.CheckoutLink(id, URI.create("https://pay.payos.vn/web/" + id));
        };
        var billing = billing(gateway);
        UUID creatorPlan = UUID.fromString("00000000-0000-7000-8000-000000000101");
        UUID teamPlan = UUID.fromString("00000000-0000-7000-8000-000000000201");
        var initial = billing.checkout(owner, creatorPlan, "change-base");
        pay(billing, initial, "change-link-1", "change-base-reference");
        UUID subscriptionId = billing.subscription(organizationId).orElseThrow().id();

        assertThatThrownBy(() -> billing.checkout(owner, teamPlan, null, "unsafe-early-charge"))
                .hasMessageContaining("end-of-period plan change");
        var scheduled = billing.schedulePlanChange(
                owner, subscriptionId, teamPlan, "change-scheduled-correlation"
        );
        assertThat(scheduled.nextPlanCode()).isEqualTo("TRAINING_TEAM_MONTHLY");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM billing_orders WHERE organization_id = ?", Long.class, organizationId
        )).isEqualTo(1L);

        var cancelled = billing.cancelPlanChange(owner, subscriptionId, "change-cancelled-correlation");
        assertThat(cancelled.nextPlanCode()).isNull();
        billing.schedulePlanChange(owner, subscriptionId, teamPlan, "change-rescheduled-correlation");
        Instant nearExpiry = Instant.now().plus(Duration.ofDays(2));
        jdbc.update(
                "UPDATE subscriptions SET current_period_end = ? WHERE id = ?",
                Timestamp.from(nearExpiry), subscriptionId
        );

        assertThat(billing.reconcilePendingPayments().renewalsPrepared()).isEqualTo(1);
        UUID renewalOrderId = jdbc.queryForObject(
                "SELECT billing_order_id FROM renewal_attempts WHERE subscription_id = ?",
                UUID.class, subscriptionId
        );
        assertThat(jdbc.queryForObject(
                "SELECT plan_id FROM billing_orders WHERE id = ?", UUID.class, renewalOrderId
        )).isEqualTo(teamPlan);
        Long renewalOrderCode = jdbc.queryForObject(
                "SELECT order_code FROM billing_orders WHERE id = ?", Long.class, renewalOrderId
        );
        pay(billing, new BillingService.Checkout(
                renewalOrderId, renewalOrderCode, 2_490_000,
                "https://pay.payos.vn/web/change-link-2", Instant.now().plus(Duration.ofMinutes(30))
        ), "change-link-2", "change-renewal-reference");

        assertThat(jdbc.queryForObject(
                "SELECT cancel_at_period_end FROM subscriptions WHERE id = ?", Boolean.class, subscriptionId
        )).isTrue();
        var paidChange = billing.subscription(organizationId).orElseThrow();
        assertThat(paidChange.nextPlanCode()).isEqualTo("TRAINING_TEAM_MONTHLY");
        assertThat(paidChange.nextPlanPaid()).isTrue();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM subscriptions
                WHERE organization_id = ? AND plan_id = ? AND status = 'SCHEDULED'
                """,
                Long.class, organizationId, teamPlan
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ? AND event_type IN (
                    'SubscriptionPlanChangeScheduled', 'SubscriptionPlanChangeCancelled'
                )
                """,
                Long.class, subscriptionId
        )).isEqualTo(3L);
    }

    @Test
    void qualifiedPilotLeadIsIdempotentScoredAndHasAnImmutableSalesTrail() {
        var leads = new PilotLeadService(jdbc, transactions);
        var request = new PilotLeadService.LeadRequest(
                "Nguyen Minh", "minh@example.vn", "Hoc vien Minh",
                PilotLeadService.BuyerRole.TRAINING_MANAGER,
                PilotLeadService.Minutes.BETWEEN_600_1499,
                PilotLeadService.Learners.BETWEEN_200_499,
                PilotLeadService.Goal.PROVE_LEARNING, "One paid cohort ready",
                PilotLeadService.Source.PARTNER, "trainer-network", true
        );

        var submitted = leads.submit(request, "pilot-lead-idempotency-001", "127.0.0.1|test-agent");
        var replay = leads.submit(request, "pilot-lead-idempotency-001", "127.0.0.1|test-agent");
        assertThat(replay.id()).isEqualTo(submitted.id());
        assertThat(submitted.priority()).isEqualTo("HOT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pilot_leads", Long.class)).isEqualTo(1L);
        var queued = leads.queue(null).getFirst();
        assertThat(java.time.Duration.between(queued.createdAt(), queued.contactDueAt()))
                .isEqualTo(java.time.Duration.ofHours(4));
        assertThat(queued.contactOverdue()).isFalse();
        jdbc.update("UPDATE pilot_leads SET contact_due_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id = ?",
                submitted.id());
        assertThat(leads.queue(null).getFirst().contactOverdue()).isTrue();
        assertThatThrownBy(() -> leads.submit(
                new PilotLeadService.LeadRequest(
                        "Another buyer", "other@example.vn", "Other academy",
                        PilotLeadService.BuyerRole.OWNER, PilotLeadService.Minutes.UNDER_100,
                        PilotLeadService.Learners.UNDER_50, PilotLeadService.Goal.OTHER,
                        null, PilotLeadService.Source.DIRECT, null, true
                ), "pilot-lead-idempotency-001", "127.0.0.1|test-agent"
        )).hasMessageContaining("Idempotency key");

        leads.update(submitted.id(), new PilotLeadService.StatusUpdate(
                PilotLeadService.Status.CONTACTED, null, null
        ), "sales-operator");
        assertThatThrownBy(() -> leads.update(submitted.id(), new PilotLeadService.StatusUpdate(
                PilotLeadService.Status.CONTACTED, null, null
        ), "sales-operator")).hasMessageContaining("already has this status");
        leads.update(submitted.id(), new PilotLeadService.StatusUpdate(
                PilotLeadService.Status.QUALIFIED, null, null
        ), "sales-operator");
        leads.update(submitted.id(), new PilotLeadService.StatusUpdate(
                PilotLeadService.Status.PROPOSAL, null, null
        ), "sales-operator");
        var won = leads.update(submitted.id(), new PilotLeadService.StatusUpdate(
                PilotLeadService.Status.WON, organizationId, null
        ), "sales-operator");
        assertThat(won.organizationId()).isEqualTo(organizationId);
        assertThat(leads.queue(null)).isEmpty();
        assertThat(leads.queue(PilotLeadService.Status.WON)).extracting(PilotLeadService.LeadView::id)
                .containsExactly(submitted.id());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pilot_lead_events WHERE pilot_lead_id = ?", Long.class, submitted.id()
        )).isEqualTo(5L);
        var funnel = leads.funnel().getFirst();
        assertThat(funnel.acquisitionSource()).isEqualTo("PARTNER");
        assertThat(funnel.leads()).isEqualTo(1);
        assertThat(funnel.contacted()).isEqualTo(1);
        assertThat(funnel.won()).isEqualTo(1);
        assertThat(funnel.overdue()).isZero();
        assertThat(funnel.avgMinutesToContact()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThatThrownBy(() -> leads.update(submitted.id(), new PilotLeadService.StatusUpdate(
                PilotLeadService.Status.LOST, null, "Changed mind"
        ), "sales-operator")).hasMessageContaining("transition");
    }

    @Test
    void paidTopUpExtendsCurrentEntitlementsWithoutCreatingAnotherSubscription() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        AtomicInteger links = new AtomicInteger();
        PaymentGateway gateway = (orderCode, amount, description) -> {
            String id = "topup-link-" + links.incrementAndGet();
            return new PaymentGateway.CheckoutLink(id, URI.create("https://pay.payos.vn/web/" + id));
        };
        var billing = billing(gateway);
        var subscription = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000101"), "topup-base"
        );
        pay(billing, subscription, "topup-link-1", "topup-base-reference");
        var topUp = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000301"), "topup-credit"
        );
        pay(billing, topUp, "topup-link-2", "topup-credit-reference");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM subscriptions WHERE organization_id = ?", Long.class, organizationId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM credit_grants WHERE billing_order_id = ?", Long.class, topUp.orderId()
        )).isEqualTo(2L);
        assertThat(billing.usage(organizationId).allowanceSeconds()).isEqualTo(25_200L);
        assertThat(billing.usage(organizationId).qaQueryAllowance()).isEqualTo(1_250L);
        assertThat(jdbc.queryForObject(
                "SELECT invoice_type FROM invoices WHERE billing_order_id = ?", String.class, topUp.orderId()
        )).isEqualTo("TOP_UP");
    }

    @Test
    void unusedTopUpRefundRequiresManualProviderConfirmationAndRevokesCreditsOnce() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        AtomicInteger links = new AtomicInteger();
        PaymentGateway gateway = (orderCode, amount, description) -> {
            String id = "refund-link-" + links.incrementAndGet();
            return new PaymentGateway.CheckoutLink(id, URI.create("https://pay.payos.vn/web/" + id));
        };
        var billing = billing(gateway);
        var base = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000101"), "refund-base"
        );
        pay(billing, base, "refund-link-1", "refund-base-reference");
        var topUp = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000301"), "refund-topup"
        );
        pay(billing, topUp, "refund-link-2", "refund-topup-reference");
        UUID invoiceId = jdbc.queryForObject(
                "SELECT id FROM invoices WHERE billing_order_id = ?", UUID.class, topUp.orderId()
        );

        var requested = billing.requestRefund(
                owner, invoiceId, "Khách hàng chưa sử dụng gói nạp thêm", "refund-request-correlation"
        );
        assertThat(requested.state()).isEqualTo("REQUESTED");
        assertThat(billing.usage(organizationId).allowanceSeconds()).isEqualTo(25_200L);

        var confirmed = billing.resolveRefund(requested.id(), true, "BANK-REFUND-0001", null);
        var replay = billing.resolveRefund(requested.id(), true, "BANK-REFUND-0001", null);

        assertThat(confirmed.state()).isEqualTo("SUCCEEDED");
        assertThat(replay.state()).isEqualTo("SUCCEEDED");
        assertThat(billing.usage(organizationId).allowanceSeconds()).isEqualTo(18_000L);
        assertThat(billing.usage(organizationId).qaQueryAllowance()).isEqualTo(1_000L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM billing_adjustments WHERE refund_request_id = ?",
                Long.class, requested.id()
        )).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT state FROM invoices WHERE id = ?", String.class, invoiceId))
                .isEqualTo("REFUNDED");
    }

    @Test
    void reconciliationPreparesOneRenewalInvoiceAndPaidLinkExtendsSubscription() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        AtomicInteger links = new AtomicInteger();
        PaymentGateway gateway = (orderCode, amount, description) -> {
            String id = "dunning-link-" + links.incrementAndGet();
            return new PaymentGateway.CheckoutLink(id, URI.create("https://pay.payos.vn/web/" + id));
        };
        var billing = billing(gateway);
        var initial = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000101"), "dunning-base"
        );
        pay(billing, initial, "dunning-link-1", "dunning-base-reference");
        UUID subscriptionId = billing.subscription(organizationId).orElseThrow().id();
        Instant nearExpiry = Instant.now().plus(Duration.ofDays(2));
        jdbc.update(
                "UPDATE subscriptions SET current_period_end = ? WHERE id = ?",
                Timestamp.from(nearExpiry), subscriptionId
        );

        var prepared = billing.reconcilePendingPayments();
        var replay = billing.reconcilePendingPayments();
        assertThat(prepared.renewalsPrepared()).isEqualTo(1);
        assertThat(replay.renewalsPrepared()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM renewal_attempts WHERE subscription_id = ?", Long.class, subscriptionId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoices WHERE subscription_id = ? AND state = 'OPEN'",
                Long.class, subscriptionId
        )).isEqualTo(1L);

        Long renewalOrderCode = jdbc.queryForObject(
                """
                SELECT o.order_code FROM renewal_attempts r
                JOIN billing_orders o ON o.id = r.billing_order_id
                WHERE r.subscription_id = ?
                """,
                Long.class, subscriptionId
        );
        UUID renewalOrderId = jdbc.queryForObject(
                "SELECT billing_order_id FROM renewal_attempts WHERE subscription_id = ?",
                UUID.class, subscriptionId
        );
        pay(billing, new BillingService.Checkout(
                renewalOrderId, renewalOrderCode, 790_000, "https://pay.payos.vn/web/dunning-link-2",
                Instant.now().plus(Duration.ofMinutes(30))
        ), "dunning-link-2", "dunning-renewal-reference");

        assertThat(jdbc.queryForObject(
                "SELECT state FROM renewal_attempts WHERE subscription_id = ?", String.class, subscriptionId
        )).isEqualTo("PAID");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invoices WHERE billing_order_id = ?", Long.class, renewalOrderId
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM invoices WHERE billing_order_id = ?", String.class, renewalOrderId
        )).isEqualTo("PAID");
    }

    @Test
    void profitabilityRefusesGreenStatusUntilCostsAreConfirmedAndUsesReconciledRevenue() {
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class, organizationId
        );
        CurrentActor owner = new CurrentActor(ownerId, organizationId, CurrentActor.Role.OWNER);
        PaymentGateway gateway = (orderCode, amount, description) ->
                new PaymentGateway.CheckoutLink("profit-link", URI.create("https://pay.payos.vn/web/profit-link"));
        var billing = billing(gateway);
        var checkout = billing.checkout(
                owner, UUID.fromString("00000000-0000-7000-8000-000000000101"), "profitability-payment"
        );
        pay(billing, checkout, "profit-link", "profit-reference");
        var profitability = new ProfitabilityService(jdbc);
        Instant from = Instant.now().minus(Duration.ofDays(1));
        Instant to = Instant.now().plus(Duration.ofDays(31));

        assertThat(profitability.report(organizationId, from, to).status()).isEqualTo("UNCONFIGURED");
        profitability.updateProfile(owner, new ProfitabilityService.EconomicProfile(
                26_000, 150, 0, 100_000, 120, 200_000,
                1_000, 1_000_000, 300, true, null
        ));
        profitability.addCost(owner, "SUPPORT", 10_000, Instant.now(), "Support trực tiếp bổ sung");

        var report = profitability.report(organizationId, from, to);
        assertThat(report.grossCashVnd()).isEqualTo(790_000L);
        assertThat(report.recognizedRevenueVnd()).isEqualTo(790_000L);
        assertThat(report.paymentFeesVnd()).isEqualTo(11_850L);
        assertThat(report.manualDirectCostsVnd()).isEqualTo(10_000L);
        assertThat(report.status()).isEqualTo("BELOW_FLOOR");
        assertThat(report.cacPaybackMonths()).isPositive();
        assertThat(report.contributionLtvVnd()).isPositive();
        assertThat(report.ltvCacRatio()).isPositive();

        UUID paymentId = jdbc.queryForObject(
                "SELECT id FROM payments WHERE organization_id = ? AND provider_reference = ?",
                UUID.class, organizationId, "profit-reference"
        );
        UUID invoiceId = jdbc.queryForObject(
                "SELECT id FROM invoices WHERE organization_id = ? AND billing_order_id = ?",
                UUID.class, organizationId, checkout.orderId()
        );
        Instant refundedAt = Instant.now();
        jdbc.update(
                """
                INSERT INTO refund_requests(
                    id, organization_id, payment_id, invoice_id, amount_vnd, reason,
                    state, provider_reference, requested_by, requested_at, resolved_at
                ) VALUES (?, ?, ?, ?, 790000, 'Customer cancellation', 'SUCCEEDED', ?, ?, ?, ?)
                """,
                UUID.randomUUID(), organizationId, paymentId, invoiceId, "profit-refund-reference",
                ownerId, Timestamp.from(refundedAt), Timestamp.from(refundedAt)
        );
        var refundPeriod = profitability.report(
                organizationId, refundedAt.minus(Duration.ofHours(1)), refundedAt.plus(Duration.ofHours(1))
        );
        assertThat(refundPeriod.netCashVnd()).isZero();
        assertThat(refundPeriod.recognizedRevenueVnd()).isNegative();
        assertThat(refundPeriod.taxReserveVnd()).isZero();
        assertThat(refundPeriod.status()).isEqualTo("NEGATIVE");
    }

    @Test
    void createsJobReservationAndOutboxAtomicallyAndReplaysSafely() {
        UUID sourceId = seedSourceWithRights(organizationId);
        var service = new RequestAnalysisService(
                new JdbcAnalysisJobStore(jdbc),
                quota,
                new ObjectMapper()
        );
        var command = new RequestAnalysisCommand(
                organizationId,
                sourceId,
                600,
                "{\"language\":\"vi\",\"flashcards\":10}",
                "request-job-1",
                "GOOGLE_GEMINI",
                "gemini-test",
                "correlation-job-1"
        );

        var first = transactions.execute(status -> service.request(command));
        var replay = transactions.execute(status -> service.request(command));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM analysis_jobs WHERE organization_id = ?", Long.class, organizationId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, first.id()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations WHERE organization_id = ?", Long.class, organizationId))
                .isEqualTo(1L);
    }

    @Test
    void rollsBackQuotaWhenJobPersistenceFails() {
        UUID sourceId = seedSourceWithRights(organizationId);
        var service = new RequestAnalysisService(
                new JdbcAnalysisJobStore(jdbc),
                quota,
                new ObjectMapper()
        );
        var invalid = new RequestAnalysisCommand(
                organizationId,
                sourceId,
                600,
                "{}",
                "request-job-rollback",
                "GOOGLE_GEMINI",
                "gemini-test",
                "x".repeat(129)
        );

        assertThatThrownBy(() -> transactions.execute(status -> service.request(invalid)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_reservations WHERE organization_id = ?", Long.class, organizationId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_ledger WHERE organization_id = ?", Long.class, organizationId))
                .isZero();
    }

    @Test
    void workerClaimUsesLeaseAndAllowsRecoveryAfterExpiry() {
        UUID sourceId = seedSourceWithRights(organizationId);
        var store = new JdbcAnalysisJobStore(jdbc);
        var service = new RequestAnalysisService(store, quota, new ObjectMapper());
        var command = new RequestAnalysisCommand(
                organizationId, sourceId, 300, "{}", "request-lease-1",
                "GOOGLE_GEMINI", "gemini-test", "correlation-lease-1"
        );
        var job = transactions.execute(status -> service.request(command));
        Instant firstClaimAt = Instant.now();

        var first = store.claim(job.id(), "worker-1", Duration.ofMinutes(5), firstClaimAt);
        var duplicate = store.claim(job.id(), "worker-2", Duration.ofMinutes(5), firstClaimAt.plusSeconds(1));
        var recovered = store.claim(job.id(), "worker-2", Duration.ofMinutes(5), firstClaimAt.plus(Duration.ofMinutes(6)));

        assertThat(first).get().extracting(item -> item.job().attempt()).isEqualTo(1);
        assertThat(duplicate).isEmpty();
        assertThat(recovered).get().extracting(item -> item.job().attempt()).isEqualTo(2);
    }

    @Test
    void completionAtomicallyPersistsPackageCostAndCommittedUsage() {
        UUID sourceId = seedSourceWithRights(organizationId);
        var store = new JdbcAnalysisJobStore(jdbc);
        var service = new RequestAnalysisService(store, quota, new ObjectMapper());
        var completion = new AnalysisCompletionService(store, quota);
        var job = transactions.execute(status -> service.request(new RequestAnalysisCommand(
                organizationId, sourceId, 300, "{}", "request-complete-1",
                "GOOGLE_GEMINI", "gemini-test", "correlation-complete-1"
        )));
        var item = store.claim(job.id(), "worker-complete", Duration.ofMinutes(5), Instant.now()).orElseThrow();

        transactions.executeWithoutResult(status -> completion.complete(
                item, "worker-complete", new GenerationAccounting(generation("{\"valid\":true}"), 120, 240),
                "{\"valid\":true}", "prompt-v1", "schema-v1", Instant.now()
        ));

        assertThat(jdbc.queryForObject("SELECT state FROM analysis_jobs WHERE id = ?", String.class, job.id()))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM usage_reservations WHERE id = ?", String.class, job.usageReservationId()))
                .isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_runs WHERE job_id = ?", Long.class, job.id()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_ledger WHERE organization_id = ?", Long.class, organizationId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_revisions WHERE organization_id = ?", Long.class, organizationId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'AnalysisCompleted'",
                Long.class, job.id()
        )).isEqualTo(1L);
    }

    @Test
    void invalidPaidGenerationIsCostedAndReleasesUsage() {
        UUID sourceId = seedSourceWithRights(organizationId);
        var store = new JdbcAnalysisJobStore(jdbc);
        var service = new RequestAnalysisService(store, quota, new ObjectMapper());
        var completion = new AnalysisCompletionService(store, quota);
        var job = transactions.execute(status -> service.request(new RequestAnalysisCommand(
                organizationId, sourceId, 300, "{}", "request-invalid-1",
                "GOOGLE_GEMINI", "gemini-test", "correlation-invalid-1"
        )));
        var item = store.claim(job.id(), "worker-invalid", Duration.ofMinutes(5), Instant.now()).orElseThrow();

        transactions.executeWithoutResult(status -> completion.fail(
                item, "worker-invalid", new GenerationAccounting(generation("not-json"), 120, 240),
                "INVALID_AI_OUTPUT", "Malformed output", true, Instant.now(),
                "prompt-v1", "schema-v1", Instant.now()
        ));

        assertThat(jdbc.queryForObject("SELECT state FROM analysis_jobs WHERE id = ?", String.class, job.id()))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM usage_reservations WHERE id = ?", String.class, job.usageReservationId()))
                .isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT validation_state FROM generation_runs WHERE job_id = ?", String.class, job.id()))
                .isEqualTo("INVALID");
        assertThat(jdbc.queryForObject("SELECT raw_output FROM generation_runs WHERE job_id = ?", String.class, job.id()))
                .isEqualTo("not-json");
    }

    @Test
    void staleWorkerCannotWriteGenerationOrChargeUsage() {
        UUID sourceId = seedSourceWithRights(organizationId);
        var store = new JdbcAnalysisJobStore(jdbc);
        var service = new RequestAnalysisService(store, quota, new ObjectMapper());
        var completion = new AnalysisCompletionService(store, quota);
        var job = transactions.execute(status -> service.request(new RequestAnalysisCommand(
                organizationId, sourceId, 300, "{}", "request-fenced-1",
                "GOOGLE_GEMINI", "gemini-test", "correlation-fenced-1"
        )));
        Instant claimedAt = Instant.now();
        var stale = store.claim(job.id(), "worker-stale", Duration.ofSeconds(1), claimedAt).orElseThrow();
        store.claim(job.id(), "worker-current", Duration.ofMinutes(5), claimedAt.plusSeconds(2)).orElseThrow();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> completion.complete(
                stale, "worker-stale", new GenerationAccounting(generation("{}"), 1, 2), "{}",
                "prompt-v1", "schema-v1", claimedAt.plusSeconds(3)
        ))).isInstanceOf(AnalysisLeaseLostException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_runs WHERE job_id = ?", Long.class, job.id()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM usage_reservations WHERE id = ?", String.class, job.usageReservationId()))
                .isEqualTo("RESERVED");
    }

    @Test
    void outboxLeasePreventsConcurrentDispatchAndRecoversAfterExpiry() {
        jdbc.update("DELETE FROM outbox_events");
        UUID sourceId = seedSourceWithRights(organizationId);
        var service = new RequestAnalysisService(new JdbcAnalysisJobStore(jdbc), quota, new ObjectMapper());
        var job = transactions.execute(status -> service.request(new RequestAnalysisCommand(
                organizationId, sourceId, 300, "{}", "request-outbox-1",
                "GOOGLE_GEMINI", "gemini-test", "correlation-outbox-1"
        )));
        var outbox = new JdbcOutboxStore(jdbc, transactions);
        Instant firstClaimAt = Instant.now();

        var first = outbox.claim("dispatcher-1", 10, Duration.ofMinutes(5), firstClaimAt);
        var duplicate = outbox.claim("dispatcher-2", 10, Duration.ofMinutes(5), firstClaimAt.plusSeconds(1));
        var recovered = outbox.claim("dispatcher-2", 10, Duration.ofMinutes(5), firstClaimAt.plus(Duration.ofMinutes(6)));

        assertThat(first).extracting(event -> event.aggregateId()).contains(job.id());
        assertThat(duplicate).isEmpty();
        assertThat(recovered).extracting(event -> event.id())
                .containsExactlyInAnyOrderElementsOf(first.stream().map(event -> event.id()).toList());
        UUID targetEventId = first.stream()
                .filter(event -> event.aggregateId().equals(job.id()))
                .findFirst()
                .orElseThrow()
                .id();
        assertThat(outbox.markPublished(targetEventId, "dispatcher-1", Instant.now())).isFalse();
        assertThat(outbox.markPublished(targetEventId, "dispatcher-2", Instant.now())).isTrue();
    }

    private UUID seedEntitlement(long allowance) {
        UUID userId = UuidV7Generator.generate();
        UUID orgId = UuidV7Generator.generate();
        UUID entitlementId = UuidV7Generator.generate();
        String unique = orgId.toString();
        Instant now = Instant.now();

        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, ?)",
                userId, "subject-" + unique, unique + "@example.com", unique + "@example.com", "Test Owner"
        );
        jdbc.update(
                "INSERT INTO organizations(id, name, slug) VALUES (?, ?, ?)",
                orgId, "Test Organization", "org-" + unique
        );
        jdbc.update(
                "INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'OWNER')",
                orgId, userId
        );
        jdbc.update(
                """
                INSERT INTO entitlements(id, organization_id, metric, allowance, period_start, period_end)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                entitlementId,
                orgId,
                UsageMetric.PROCESSED_VIDEO_SECOND.name(),
                allowance,
                Timestamp.from(now.minus(Duration.ofDays(1))),
                Timestamp.from(now.plus(Duration.ofDays(30)))
        );
        return orgId;
    }

    private UUID seedSourceWithRights(UUID orgId) {
        UUID sourceId = UuidV7Generator.generate();
        UUID attestationId = UuidV7Generator.generate();
        String videoId = sourceId.toString().replace("-", "").substring(21);
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class,
                orgId
        );
        jdbc.update(
                """
                INSERT INTO sources(
                    id, organization_id, type, canonical_uri, external_id, created_by,
                    duration_seconds, metadata_verified_at
                ) VALUES (?, ?, 'YOUTUBE', ?, ?, ?, 3600, CURRENT_TIMESTAMP)
                """,
                sourceId,
                orgId,
                "https://www.youtube.com/watch?v=" + videoId,
                videoId,
                ownerId
        );
        jdbc.update(
                """
                INSERT INTO rights_attestations(
                    id, organization_id, source_id, attested_by, basis, terms_version
                ) VALUES (?, ?, ?, ?, 'OWNER', 'v1')
                """,
                attestationId,
                orgId,
                sourceId,
                ownerId
        );
        return sourceId;
    }

    private UUID seedLearner(UUID orgId) {
        UUID id = UuidV7Generator.generate();
        String unique = id.toString();
        jdbc.update(
                "INSERT INTO users(id, auth_subject, email, normalized_email, display_name) VALUES (?, ?, ?, ?, ?)",
                id, "learner-" + unique, unique + "@learner.test", unique + "@learner.test", "Learner"
        );
        jdbc.update(
                "INSERT INTO memberships(organization_id, user_id, role) VALUES (?, ?, 'LEARNER')",
                orgId, id
        );
        return id;
    }

    private UUID seedPublishedPackage(UUID orgId, UUID ownerId) {
        return seedPackage(orgId, ownerId, "PUBLISHED");
    }

    private BillingService billing(PaymentGateway gateway) {
        var properties = new PayOsProperties(
                true, "client", "api", "secret", URI.create("https://api-merchant.payos.vn"),
                URI.create("https://app.example/success"), URI.create("https://app.example/cancel"),
                Duration.ofMinutes(30)
        );
        return new BillingService(jdbc, transactions, gateway, properties, new DisabledNotificationQueue());
    }

    private static void pay(
            BillingService billing,
            BillingService.Checkout checkout,
            String paymentLinkId,
            String reference
    ) {
        var mapper = new ObjectMapper();
        var data = mapper.createObjectNode()
                .put("orderCode", checkout.orderCode())
                .put("amount", checkout.amountVnd())
                .put("reference", reference)
                .put("currency", "VND")
                .put("paymentLinkId", paymentLinkId)
                .put("code", "00");
        var envelope = mapper.createObjectNode()
                .put("code", "00")
                .put("success", true)
                .set("data", data);
        envelope.put("signature", PayOsSignature.signWebhook(data, "secret"));
        billing.processWebhook(envelope);
    }

    private UUID seedPackage(UUID orgId, UUID ownerId, String state) {
        UUID sourceId = seedSourceWithRights(orgId);
        UUID packageId = UuidV7Generator.generate();
        UUID revisionId = UuidV7Generator.generate();
        String content = """
                {"video":{"youtubeUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","title":"Test","language":"vi"},
                 "summary":{"overview":"Overview","sections":[{"title":"One","content":["Text"]}]},
                 "keyTakeaways":["One"],
                 "flashcards":[
                   {"id":"flash-1","question":"F1?","answer":"A1","source":{"timestampSeconds":10,"verificationStatus":"verified"}},
                   {"id":"flash-2","question":"F2?","answer":"A2","source":{"timestampSeconds":20,"verificationStatus":"verified"}}
                 ],
                 "quiz":[
                   {"id":"quiz-1","question":"Q1","options":["A","B","C","D"],"correctAnswerIndex":1,"explanation":"E1","source":{"timestampSeconds":30,"evidence":"Evidence one"}},
                   {"id":"quiz-2","question":"Q2","options":["A","B","C","D"],"correctAnswerIndex":2,"explanation":"E2","source":{"timestampSeconds":40,"evidence":"Evidence two"}}
                 ]}
                """;
        jdbc.update(
                "INSERT INTO learning_packages(id, organization_id, source_id, publication_state) VALUES (?, ?, ?, 'GENERATED')",
                packageId, orgId, sourceId
        );
        jdbc.update(
                """
                INSERT INTO package_revisions(
                    id, organization_id, package_id, revision_no, content_json, edited_by, verification_state
                ) VALUES (?, ?, ?, 1, CAST(? AS jsonb), ?, 'HUMAN_VERIFIED')
                """,
                revisionId, orgId, packageId, content, ownerId
        );
        jdbc.update(
                "UPDATE learning_packages SET current_revision_id = ?, publication_state = ? WHERE id = ?",
                revisionId, state, packageId
        );
        return packageId;
    }

    private static ObjectNode validPackageContent(ObjectMapper mapper, String canonicalUri) {
        String videoId = canonicalUri.substring(canonicalUri.lastIndexOf('=') + 1);
        ObjectNode root = mapper.createObjectNode().put("schemaVersion", "1.0");
        root.set("video", mapper.createObjectNode()
                .put("youtubeUrl", canonicalUri)
                .put("videoId", videoId).put("title", "Test").put("language", "vi"));
        ObjectNode source = mapper.createObjectNode().put("timestampSeconds", 10).put("evidence", "Evidence");
        var sections = mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "section-one").put("title", "One").set("source", source.deepCopy())
                .set("content", mapper.createArrayNode().add("Text")));
        root.set("summary", mapper.createObjectNode().put("overview", "Overview").set("sections", sections));
        root.set("keyTakeaways", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", "takeaway-one").put("text", "One").set("source", source.deepCopy())));
        var cards = mapper.createArrayNode();
        for (int index = 1; index <= 10; index++) {
            cards.add(mapper.createObjectNode().put("id", "flash-" + index)
                    .put("question", "F" + index + "?").put("answer", "A" + index)
                    .set("source", source.deepCopy()));
        }
        root.set("flashcards", cards);
        var quiz = mapper.createArrayNode();
        for (int index = 1; index <= 5; index++) {
            quiz.add(mapper.createObjectNode().put("id", "quiz-" + index)
                    .put("question", "Q" + index)
                    .set("options", mapper.createArrayNode().add("A").add("B").add("C").add("D"))
                    .put("correctAnswerIndex", 1).put("explanation", "Explanation")
                    .set("source", source.deepCopy()));
        }
        root.set("quiz", quiz);
        return root;
    }

    private static AiGenerationResult generation(String output) {
        return new AiGenerationResult(
                "GOOGLE_GEMINI", "gemini-test", "gemini-test-001",
                100, 20, 10, 500, 0, output
        );
    }
}
