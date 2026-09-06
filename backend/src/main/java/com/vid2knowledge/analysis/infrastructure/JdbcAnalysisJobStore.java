package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.SourceRightsRequiredException;
import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.analysis.domain.AnalysisJob;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnBean(JdbcTemplate.class)
public class JdbcAnalysisJobStore implements AnalysisJobStore {

    private final JdbcTemplate jdbc;

    public JdbcAnalysisJobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void requireActiveRights(UUID organizationId, UUID sourceId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM sources s
                WHERE s.id = ? AND s.organization_id = ?
                  AND EXISTS (
                    SELECT 1 FROM rights_attestations r
                    WHERE r.source_id = s.id AND r.organization_id = s.organization_id
                      AND r.revoked_at IS NULL
                  )
                """,
                Integer.class,
                sourceId,
                organizationId
        );
        if (count == null || count == 0) {
            throw new SourceRightsRequiredException();
        }
    }

    @Override
    public Optional<AnalysisJob> findByIdempotencyKey(UUID organizationId, String idempotencyKey) {
        List<AnalysisJob> results = jdbc.query(
                """
                SELECT id, organization_id, source_id, usage_reservation_id, state,
                       request_fingerprint, idempotency_key, provider, model, attempt, queued_at
                FROM analysis_jobs WHERE organization_id = ? AND idempotency_key = ?
                """,
                JdbcAnalysisJobStore::mapJob,
                organizationId,
                idempotencyKey
        );
        return results.stream().findFirst();
    }

    @Override
    public AnalysisJob create(
            UUID organizationId,
            UUID sourceId,
            UUID usageReservationId,
            String outputProfileJson,
            String requestFingerprint,
            String idempotencyKey,
            String provider,
            String model,
            String correlationId,
            Instant now
    ) {
        UUID jobId = UuidV7Generator.generate();
        jdbc.update(
                """
                INSERT INTO analysis_jobs(
                    id, organization_id, source_id, usage_reservation_id, state,
                    output_profile_json, request_fingerprint, idempotency_key,
                    provider, model, queued_at, updated_at
                ) VALUES (?, ?, ?, ?, 'QUEUED', CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
                """,
                jobId,
                organizationId,
                sourceId,
                usageReservationId,
                outputProfileJson,
                requestFingerprint,
                idempotencyKey,
                provider,
                model,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'AnalysisRequested', 1, 'AnalysisJob', ?, ?,
                          jsonb_build_object('jobId', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(),
                organizationId,
                jobId,
                correlationId,
                jobId,
                Timestamp.from(now),
                Timestamp.from(now)
        );

        return findByIdempotencyKey(organizationId, idempotencyKey).orElseThrow();
    }

    @Override
    public Optional<AnalysisJob> claim(UUID jobId, String workerId, Duration leaseDuration, Instant now) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("Worker ID is required");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Lease duration must be positive");
        }

        List<AnalysisJob> claimed = jdbc.query(
                """
                UPDATE analysis_jobs
                SET state = 'PROCESSING', attempt = attempt + 1, lease_owner = ?,
                    lease_expires_at = ?, started_at = COALESCE(started_at, ?),
                    version = version + 1, updated_at = ?
                WHERE id = ?
                  AND (
                    state IN ('QUEUED', 'RETRY_SCHEDULED')
                    OR (state = 'PROCESSING' AND lease_expires_at <= ?)
                  )
                RETURNING id, organization_id, source_id, usage_reservation_id, state,
                          request_fingerprint, idempotency_key, provider, model, attempt, queued_at
                """,
                JdbcAnalysisJobStore::mapJob,
                workerId,
                Timestamp.from(now.plus(leaseDuration)),
                Timestamp.from(now),
                Timestamp.from(now),
                jobId,
                Timestamp.from(now)
        );
        return claimed.stream().findFirst();
    }

    private static AnalysisJob mapJob(ResultSet result, int rowNumber) throws SQLException {
        return new AnalysisJob(
                result.getObject("id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getObject("source_id", UUID.class),
                result.getObject("usage_reservation_id", UUID.class),
                AnalysisJob.State.valueOf(result.getString("state")),
                result.getString("request_fingerprint"),
                result.getString("idempotency_key"),
                result.getString("provider"),
                result.getString("model"),
                result.getInt("attempt"),
                result.getTimestamp("queued_at").toInstant()
        );
    }
}
