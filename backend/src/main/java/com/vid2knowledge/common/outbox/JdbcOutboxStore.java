package com.vid2knowledge.common.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcOutboxStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<OutboxEvent> claim(String workerId, int limit, Duration leaseDuration, Instant now) {
        if (workerId == null || workerId.isBlank() || limit <= 0 || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("Valid worker, limit and lease duration are required");
        }
        return Objects.requireNonNull(transactions.execute(status -> jdbc.query(
                """
                WITH candidates AS (
                    SELECT id FROM outbox_events
                    WHERE published_at IS NULL AND dead_lettered_at IS NULL
                      AND available_at <= ?
                      AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                    ORDER BY available_at, occurred_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE outbox_events e
                SET lease_owner = ?, lease_expires_at = ?
                FROM candidates c
                WHERE e.id = c.id
                RETURNING e.id, e.organization_id, e.event_type, e.event_version,
                          e.aggregate_type, e.aggregate_id, e.correlation_id, e.causation_id,
                          e.payload_json::text, e.occurred_at, e.attempt_count
                """,
                JdbcOutboxStore::mapEvent,
                Timestamp.from(now),
                Timestamp.from(now),
                limit,
                workerId,
                Timestamp.from(now.plus(leaseDuration))
        )));
    }

    @Override
    public boolean markPublished(UUID eventId, String workerId, Instant publishedAt) {
        return jdbc.update(
                """
                UPDATE outbox_events
                SET published_at = ?, lease_owner = NULL, lease_expires_at = NULL, last_error = NULL
                WHERE id = ? AND lease_owner = ? AND published_at IS NULL AND dead_lettered_at IS NULL
                """,
                Timestamp.from(publishedAt),
                eventId,
                workerId
        ) == 1;
    }

    @Override
    public boolean markFailed(
            UUID eventId,
            String workerId,
            String error,
            Instant retryAt,
            boolean deadLetter,
            Instant now
    ) {
        return jdbc.update(
                """
                UPDATE outbox_events
                SET attempt_count = attempt_count + 1, last_error = ?, available_at = ?,
                    dead_lettered_at = CASE WHEN ? THEN ? ELSE NULL END,
                    lease_owner = NULL, lease_expires_at = NULL
                WHERE id = ? AND lease_owner = ? AND published_at IS NULL AND dead_lettered_at IS NULL
                """,
                error,
                Timestamp.from(retryAt),
                deadLetter,
                Timestamp.from(now),
                eventId,
                workerId
        ) == 1;
    }

    private static OutboxEvent mapEvent(ResultSet result, int rowNumber) throws SQLException {
        return new OutboxEvent(
                result.getObject("id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getString("event_type"),
                result.getInt("event_version"),
                result.getString("aggregate_type"),
                result.getObject("aggregate_id", UUID.class),
                result.getString("correlation_id"),
                result.getObject("causation_id", UUID.class),
                result.getString("payload_json"),
                result.getTimestamp("occurred_at").toInstant(),
                result.getInt("attempt_count")
        );
    }
}
