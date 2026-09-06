package com.vid2knowledge.usage.infrastructure;

import com.vid2knowledge.analysis.application.RequestAnalysisCommand;
import com.vid2knowledge.analysis.application.RequestAnalysisService;
import com.vid2knowledge.analysis.application.AnalysisCompletionService;
import com.vid2knowledge.analysis.application.AnalysisLeaseLostException;
import com.vid2knowledge.analysis.application.port.AiGenerationResult;
import com.vid2knowledge.analysis.domain.GenerationAccounting;
import com.vid2knowledge.analysis.infrastructure.JdbcAnalysisJobStore;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.common.outbox.JdbcOutboxStore;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
                "x".repeat(121),
                "correlation-job-rollback"
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

    private static AiGenerationResult generation(String output) {
        return new AiGenerationResult(
                "GOOGLE_GEMINI", "gemini-test", "gemini-test-001",
                100, 20, 10, 500, 0, output
        );
    }
}
