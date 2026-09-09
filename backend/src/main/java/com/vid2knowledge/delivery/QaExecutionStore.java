package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import com.vid2knowledge.usage.application.UsageQuota;
import com.vid2knowledge.usage.domain.UsageMetric;
import com.vid2knowledge.usage.domain.UsageReservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Durable ownership spans provider HTTP calls without holding database transactions open. */
final class QaExecutionStore {
    private static final Logger log = LoggerFactory.getLogger(QaExecutionStore.class);
    private static final Duration DEADLINE = Duration.ofMinutes(10);
    private final JdbcTemplate jdbc;
    private final UsageQuota quota;
    private final TransactionTemplate transactions;
    private final Clock clock;

    QaExecutionStore(JdbcTemplate jdbc, UsageQuota quota, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.quota = quota;
        this.transactions = transactions;
        this.clock = clock;
    }

    Claim claim(CurrentActor learner, UUID assignmentId, String question, String key, String correlationId) {
        Claim claim = transactions.execute(status -> {
            String scope = learner.organizationId() + ":" + assignmentId + ":" + learner.userId() + ":" + key;
            jdbc.queryForObject("SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                    String.class, "qa-request:" + scope);
            List<Existing> existing = jdbc.query("""
                    SELECT id, usage_reservation_id, question_fingerprint, state FROM qa_execution_requests
                    WHERE organization_id = ? AND assignment_id = ? AND user_id = ? AND idempotency_key = ?
                    FOR UPDATE
                    """, (row, index) -> new Existing(row.getObject("id", UUID.class),
                    row.getObject("usage_reservation_id", UUID.class), row.getString("question_fingerprint"),
                    row.getString("state")), learner.organizationId(), assignmentId, learner.userId(), key);
            if (!existing.isEmpty()) {
                Existing value = existing.getFirst();
                if (!value.fingerprint().equals(RequestFingerprint.sha256(question))) {
                    throw new IdempotencyConflictException();
                }
                return new Claim(value.id(), value.reservationId(), value.state(), false);
            }
            // A pre-V36 failure may already have spent money. Never silently execute its old key again.
            Long legacy = jdbc.queryForObject("""
                    SELECT count(*) FROM usage_reservations
                    WHERE organization_id = ? AND metric = 'QA_QUERY' AND idempotency_key = ?
                    """, Long.class, learner.organizationId(), "qa:" + assignmentId + ":" + learner.userId() + ":" + key);
            if (legacy != null && legacy > 0) throw unavailable();
            UUID id = UuidV7Generator.generate();
            UsageReservation reservation = quota.reserve(learner.organizationId(), UsageMetric.QA_QUERY, 1,
                    "qa-request:" + id, DEADLINE, correlationId);
            // reserve() holds the active entitlement row until this transaction commits.
            // Customer allowance is refunded for failed answers, but provider attempts are
            // bounded separately: allowance + 5% (minimum three) for failures per period.
            // Rotating request keys/users cannot repeatedly spend a refunded reservation.
            Boolean budgetExhausted = jdbc.queryForObject("""
                    SELECT (SELECT count(*) FROM qa_execution_requests q
                            JOIN usage_reservations r ON r.id = q.usage_reservation_id
                            WHERE r.entitlement_id = e.id)
                           >= e.allowance + GREATEST(3, CEIL(e.allowance / 20.0))
                    FROM entitlements e WHERE e.id = ?
                    """, Boolean.class, reservation.entitlementId());
            if (Boolean.TRUE.equals(budgetExhausted)) {
                log.error("QA_EXECUTION_BUDGET_EXHAUSTED organizationId={} entitlementId={}",
                        learner.organizationId(), reservation.entitlementId());
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Q&A processing is temporarily paused after repeated provider failures; contact support");
            }
            jdbc.update("""
                    INSERT INTO qa_execution_requests(id, organization_id, assignment_id, user_id,
                        idempotency_key, question_fingerprint, usage_reservation_id, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, learner.organizationId(), assignmentId, learner.userId(), key,
                    RequestFingerprint.sha256(question), reservation.id(), Timestamp.from(clock.instant().plus(DEADLINE)),
                    Timestamp.from(clock.instant()));
            return new Claim(id, reservation.id(), "RUNNING", true);
        });
        if (claim == null) throw new IllegalStateException("Q&A claim was not persisted");
        if (!claim.owner() && !claim.state().equals("SUCCEEDED")) {
            if (claim.state().equals("RUNNING")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This Q&A request is already processing; retry the same request later");
            }
            throw unavailable();
        }
        return claim;
    }

    UUID startCall(Claim claim, UUID organizationId, String operation) {
        return transactions.execute(status -> {
            requireRunning(claim.id());
            UUID id = UuidV7Generator.generate();
            jdbc.update("""
                    INSERT INTO qa_provider_calls(id, organization_id, execution_request_id, operation, started_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, organizationId, claim.id(), operation, Timestamp.from(clock.instant()));
            return id;
        });
    }

    void completeCall(UUID callId, java.util.function.Supplier<UUID> recordCost) {
        transactions.executeWithoutResult(status -> {
            String state = jdbc.queryForObject("SELECT state FROM qa_provider_calls WHERE id = ? FOR UPDATE",
                    String.class, callId);
            if ("SUCCEEDED".equals(state)) return;
            UUID costId = recordCost.get();
            jdbc.update("""
                    UPDATE qa_provider_calls SET state = 'SUCCEEDED', cost_ledger_id = ?, completed_at = ? WHERE id = ?
                    """, costId, Timestamp.from(clock.instant()), callId);
        });
    }

    void succeed(Claim claim, String correlationId) {
        requireRunning(claim.id());
        quota.commit(claim.reservationId(), 1, correlationId);
        jdbc.update("UPDATE qa_execution_requests SET state = 'SUCCEEDED', completed_at = ? WHERE id = ?",
                Timestamp.from(clock.instant()), claim.id());
    }

    void fail(Claim claim, String correlationId) {
        transactions.executeWithoutResult(status -> finishFailure(claim.id(), correlationId));
    }

    int recoverExpired() {
        List<UUID> due = jdbc.query("""
                SELECT id FROM qa_execution_requests WHERE state = 'RUNNING' AND expires_at <= ?
                ORDER BY expires_at, id LIMIT 100
                """, (row, index) -> row.getObject("id", UUID.class), Timestamp.from(clock.instant()));
        for (UUID id : due) transactions.executeWithoutResult(status -> finishFailure(id, "qa-recovery"));
        return due.size();
    }

    private void finishFailure(UUID id, String correlationId) {
        List<Existing> values = jdbc.query("""
                SELECT id, usage_reservation_id, question_fingerprint, state FROM qa_execution_requests
                WHERE id = ? FOR UPDATE
                """, (row, index) -> new Existing(row.getObject("id", UUID.class),
                row.getObject("usage_reservation_id", UUID.class), row.getString("question_fingerprint"),
                row.getString("state")), id);
        if (values.isEmpty() || !values.getFirst().state().equals("RUNNING")) return;
        int unknown = jdbc.update("""
                UPDATE qa_provider_calls SET state = 'UNKNOWN', completed_at = ?
                WHERE execution_request_id = ? AND state = 'STARTED'
                """, Timestamp.from(clock.instant()), id);
        // Unsuccessful learner requests do not consume an allowance. The provider cost remains attributable.
        quota.release(values.getFirst().reservationId(), correlationId);
        jdbc.update("UPDATE qa_execution_requests SET state = ?, completed_at = ? WHERE id = ?",
                unknown > 0 ? "UNCERTAIN" : "FAILED", Timestamp.from(clock.instant()), id);
        if (unknown > 0) log.error("QA_PROVIDER_USAGE_UNKNOWN requestId={} calls={}", id, unknown);
    }

    private void requireRunning(UUID id) {
        Boolean running = jdbc.queryForObject("""
                SELECT state = 'RUNNING' AND expires_at > ? FROM qa_execution_requests WHERE id = ? FOR UPDATE
                """, Boolean.class, Timestamp.from(clock.instant()), id);
        if (!Boolean.TRUE.equals(running)) throw unavailable();
    }

    static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "This Q&A request could not be completed; submit a new request to try again");
    }

    record Claim(UUID id, UUID reservationId, String state, boolean owner) { }
    private record Existing(UUID id, UUID reservationId, String fingerprint, String state) { }
}
