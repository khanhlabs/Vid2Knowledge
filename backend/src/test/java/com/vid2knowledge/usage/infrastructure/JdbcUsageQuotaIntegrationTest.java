package com.vid2knowledge.usage.infrastructure;

import com.vid2knowledge.analysis.application.RequestAnalysisCommand;
import com.vid2knowledge.analysis.application.RequestAnalysisService;
import com.vid2knowledge.analysis.application.AnalysisCompletionService;
import com.vid2knowledge.analysis.application.AnalysisLeaseLostException;
import com.vid2knowledge.analysis.application.RegisterYoutubeSourceService;
import com.vid2knowledge.analysis.application.YoutubeUrlParser;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.analysis.application.port.VideoMetadataProvider;
import com.vid2knowledge.analysis.domain.GenerationAccounting;
import com.vid2knowledge.analysis.infrastructure.JdbcAnalysisJobStore;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.common.outbox.JdbcOutboxStore;
import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.auth.IdentityService;
import com.vid2knowledge.config.CommercialProperties;
import com.vid2knowledge.delivery.CatalogService;
import com.vid2knowledge.delivery.LearnerService;
import com.vid2knowledge.delivery.OutcomeAnalyticsService;
import com.vid2knowledge.delivery.PackageWorkflowService;
import com.vid2knowledge.analysis.application.LearningPackageCodec;
import jakarta.validation.Validation;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class JdbcUsageQuotaIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

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
                new CommercialProperties(3_600, Duration.ofDays(14)),
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
                "Acme Academy", "acme-academy", "correlation-org-1"
        ));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(organization.role()).isEqualTo(CurrentActor.Role.OWNER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE auth_subject = 'jwt-subject-new'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT allowance FROM entitlements WHERE organization_id = ?", Long.class, organization.id()
        )).isEqualTo(3_600L);
        assertThat(jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (period_end - period_start))::bigint FROM entitlements WHERE organization_id = ?",
                Long.class, organization.id()
        )).isEqualTo(Duration.ofDays(14).toSeconds());
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

        packages.transition(owner, packageId, PackageWorkflowService.Transition.SUBMIT_REVIEW, "package-1");
        packages.transition(owner, packageId, PackageWorkflowService.Transition.APPROVE, "package-2");
        var published = packages.transition(
                owner, packageId, PackageWorkflowService.Transition.PUBLISH, "package-3"
        );

        assertThat(published.state()).isEqualTo("PUBLISHED");
        assertThat(published.verificationState()).isEqualTo("HUMAN_VERIFIED");
        assertThatThrownBy(() -> packages.get(UUID.randomUUID(), packageId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE resource_id = ?", Long.class, packageId
        )).isEqualTo(3L);
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
        UUID ownerId = jdbc.queryForObject(
                "SELECT user_id FROM memberships WHERE organization_id = ? AND role = 'OWNER'",
                UUID.class,
                orgId
        );
        jdbc.update(
                """
                INSERT INTO sources(id, organization_id, type, canonical_uri, external_id, created_by)
                VALUES (?, ?, 'YOUTUBE', ?, ?, ?)
                """,
                sourceId,
                orgId,
                "https://www.youtube.com/watch?v=" + sourceId.toString().substring(0, 11),
                sourceId.toString().substring(0, 11),
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

    private UUID seedPackage(UUID orgId, UUID ownerId, String state) {
        UUID sourceId = seedSourceWithRights(orgId);
        UUID packageId = UuidV7Generator.generate();
        UUID revisionId = UuidV7Generator.generate();
        String content = """
                {"video":{"youtubeUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","title":"Test","language":"vi"},
                 "summary":{"overview":"Overview","sections":[{"title":"One","content":["Text"]}]},
                 "keyTakeaways":["One"],"flashcards":[],
                 "quiz":[
                   {"question":"Q1","options":["A","B","C","D"],"correctAnswerIndex":1,"explanation":"E1"},
                   {"question":"Q2","options":["A","B","C","D"],"correctAnswerIndex":2,"explanation":"E2"}
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

    private static AiGenerationResult generation(String output) {
        return new AiGenerationResult(
                "GOOGLE_GEMINI", "gemini-test", "gemini-test-001",
                100, 20, 10, 500, 0, output
        );
    }
}
