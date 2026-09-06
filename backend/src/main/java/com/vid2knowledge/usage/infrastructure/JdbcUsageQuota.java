package com.vid2knowledge.usage.infrastructure;

import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.usage.application.EntitlementNotFoundException;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import com.vid2knowledge.usage.application.UsageQuota;
import com.vid2knowledge.usage.domain.QuotaExceededException;
import com.vid2knowledge.usage.domain.UsageMetric;
import com.vid2knowledge.usage.domain.UsageReservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcUsageQuota implements UsageQuota {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public JdbcUsageQuota(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this(jdbc, transactions, Clock.systemUTC());
    }

    JdbcUsageQuota(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public UsageReservation reserve(
            UUID organizationId,
            UsageMetric metric,
            long units,
            String idempotencyKey,
            Duration ttl,
            String correlationId
    ) {
        requirePositive(units, "Reservation units must be positive");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Reservation TTL must be positive");
        }

        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.queryForObject(
                    "SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                    String.class,
                    organizationId + "|" + metric.name() + "|" + idempotencyKey
            );
            List<UsageReservation> existing = jdbc.query(
                    """
                    SELECT id, organization_id, entitlement_id, metric, reserved_units,
                           committed_units, status, idempotency_key, expires_at
                    FROM usage_reservations
                    WHERE organization_id = ? AND metric = ? AND idempotency_key = ?
                    """,
                    JdbcUsageQuota::mapReservation,
                    organizationId,
                    metric.name(),
                    idempotencyKey
            );
            if (!existing.isEmpty()) {
                UsageReservation reservation = existing.getFirst();
                if (reservation.status() == UsageReservation.Status.RESERVED
                        && !reservation.expiresAt().isAfter(clock.instant())) {
                    throw new IllegalStateException("The usage reservation has expired");
                }
                if (reservation.reservedUnits() != units) {
                    throw new IdempotencyConflictException();
                }
                return reservation;
            }

            EntitlementBalance entitlement = lockActiveEntitlement(organizationId, metric);
            long available = entitlement.allowance() - entitlement.committed() - entitlement.reserved();
            if (units > available) {
                throw new QuotaExceededException(units, available);
            }

            UUID reservationId = UuidV7Generator.generate();
            Instant now = clock.instant();
            Instant expiresAt = now.plus(ttl);
            jdbc.update(
                    """
                    INSERT INTO usage_reservations(
                        id, organization_id, entitlement_id, metric, reserved_units, status,
                        idempotency_key, expires_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, ?, ?, ?)
                    """,
                    reservationId,
                    organizationId,
                    entitlement.id(),
                    metric.name(),
                    units,
                    idempotencyKey,
                    Timestamp.from(expiresAt),
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
            appendLedger(organizationId, entitlement.id(), reservationId, "RESERVED", units, correlationId, now);
            return findReservation(reservationId);
        }));
    }

    @Override
    public UsageReservation commit(UUID reservationId, long actualUnits, String correlationId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            UsageReservation reservation = lockReservation(reservationId);
            if (reservation.status() == UsageReservation.Status.COMMITTED) {
                if (!Objects.equals(reservation.committedUnits(), actualUnits)) {
                    throw new IdempotencyConflictException();
                }
                return reservation;
            }
            requireReserved(reservation);
            if (!reservation.expiresAt().isAfter(clock.instant())) {
                throw new IllegalStateException("The usage reservation has expired");
            }
            if (actualUnits < 0 || actualUnits > reservation.reservedUnits()) {
                throw new IllegalArgumentException("Actual units must be between zero and reserved units");
            }

            Instant now = clock.instant();
            jdbc.update(
                    "UPDATE usage_reservations SET status = 'COMMITTED', committed_units = ?, updated_at = ? WHERE id = ?",
                    actualUnits,
                    Timestamp.from(now),
                    reservationId
            );
            appendLedger(
                    reservation.organizationId(), reservation.entitlementId(), reservationId,
                    "COMMITTED", actualUnits, correlationId, now
            );
            long releasedUnits = reservation.reservedUnits() - actualUnits;
            if (releasedUnits > 0) {
                appendLedger(
                        reservation.organizationId(), reservation.entitlementId(), reservationId,
                        "RELEASED", releasedUnits, correlationId, now
                );
            }
            return findReservation(reservationId);
        }));
    }

    @Override
    public UsageReservation release(UUID reservationId, String correlationId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            UsageReservation reservation = lockReservation(reservationId);
            if (reservation.status() == UsageReservation.Status.RELEASED) {
                return reservation;
            }
            requireReserved(reservation);

            Instant now = clock.instant();
            jdbc.update(
                    "UPDATE usage_reservations SET status = 'RELEASED', updated_at = ? WHERE id = ?",
                    Timestamp.from(now),
                    reservationId
            );
            appendLedger(
                    reservation.organizationId(), reservation.entitlementId(), reservationId,
                    "RELEASED", reservation.reservedUnits(), correlationId, now
            );
            return findReservation(reservationId);
        }));
    }

    private EntitlementBalance lockActiveEntitlement(UUID organizationId, UsageMetric metric) {
        Instant now = clock.instant();
        try {
            return jdbc.queryForObject(
                    """
                    SELECT e.id, e.allowance,
                           COALESCE((SELECT sum(r.committed_units) FROM usage_reservations r
                                     WHERE r.entitlement_id = e.id AND r.status = 'COMMITTED'), 0) AS committed,
                           COALESCE((SELECT sum(r.reserved_units) FROM usage_reservations r
                                     WHERE r.entitlement_id = e.id AND r.status = 'RESERVED'
                                       AND r.expires_at > ?), 0) AS reserved
                    FROM entitlements e
                    WHERE e.organization_id = ? AND e.metric = ?
                      AND e.period_start <= ? AND e.period_end > ?
                    ORDER BY e.period_start DESC
                    LIMIT 1
                    FOR UPDATE OF e
                    """,
                    (result, row) -> new EntitlementBalance(
                            result.getObject("id", UUID.class),
                            result.getLong("allowance"),
                            result.getLong("committed"),
                            result.getLong("reserved")
                    ),
                    Timestamp.from(now),
                    organizationId,
                    metric.name(),
                    Timestamp.from(now),
                    Timestamp.from(now)
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new EntitlementNotFoundException();
        }
    }

    private UsageReservation lockReservation(UUID reservationId) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT id, organization_id, entitlement_id, metric, reserved_units,
                           committed_units, status, idempotency_key, expires_at
                    FROM usage_reservations WHERE id = ? FOR UPDATE
                    """,
                    JdbcUsageQuota::mapReservation,
                    reservationId
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("Usage reservation does not exist");
        }
    }

    private UsageReservation findReservation(UUID reservationId) {
        return jdbc.queryForObject(
                """
                SELECT id, organization_id, entitlement_id, metric, reserved_units,
                       committed_units, status, idempotency_key, expires_at
                FROM usage_reservations WHERE id = ?
                """,
                JdbcUsageQuota::mapReservation,
                reservationId
        );
    }

    private void appendLedger(
            UUID organizationId,
            UUID entitlementId,
            UUID reservationId,
            String eventType,
            long units,
            String correlationId,
            Instant occurredAt
    ) {
        jdbc.update(
                """
                INSERT INTO usage_ledger(
                    id, organization_id, entitlement_id, reservation_id, event_type,
                    units, correlation_id, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(),
                organizationId,
                entitlementId,
                reservationId,
                eventType,
                units,
                correlationId,
                Timestamp.from(occurredAt)
        );
    }

    private static UsageReservation mapReservation(ResultSet result, int rowNumber) throws SQLException {
        long committed = result.getLong("committed_units");
        Long committedUnits = result.wasNull() ? null : committed;
        return new UsageReservation(
                result.getObject("id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getObject("entitlement_id", UUID.class),
                UsageMetric.valueOf(result.getString("metric")),
                result.getLong("reserved_units"),
                committedUnits,
                UsageReservation.Status.valueOf(result.getString("status")),
                result.getString("idempotency_key"),
                result.getTimestamp("expires_at").toInstant()
        );
    }

    private static void requireReserved(UsageReservation reservation) {
        if (reservation.status() != UsageReservation.Status.RESERVED) {
            throw new IllegalStateException("Only an active reservation can change state");
        }
    }

    private static void requirePositive(long units, String message) {
        if (units <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private record EntitlementBalance(UUID id, long allowance, long committed, long reserved) {
    }
}
