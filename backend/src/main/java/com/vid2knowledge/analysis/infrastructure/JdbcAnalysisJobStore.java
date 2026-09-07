package com.vid2knowledge.analysis.infrastructure;

import com.vid2knowledge.analysis.application.SourceRightsRequiredException;
import com.vid2knowledge.analysis.application.AnalysisLeaseLostException;
import com.vid2knowledge.analysis.application.port.AnalysisJobStore;
import com.vid2knowledge.analysis.domain.AnalysisJob;
import com.vid2knowledge.analysis.domain.AnalysisWorkItem;
import com.vid2knowledge.analysis.domain.AnalysisSource;
import com.vid2knowledge.analysis.domain.GenerationAccounting;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
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
    public Optional<AnalysisJob> findById(UUID organizationId, UUID jobId) {
        List<AnalysisJob> results = jdbc.query(
                """
                SELECT id, organization_id, source_id, usage_reservation_id, state,
                       request_fingerprint, idempotency_key, provider, model, attempt, queued_at
                FROM analysis_jobs WHERE organization_id = ? AND id = ?
                """,
                JdbcAnalysisJobStore::mapJob,
                organizationId,
                jobId
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
    @Transactional
    public Optional<AnalysisWorkItem> claim(UUID jobId, String workerId, Duration leaseDuration, Instant now) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("Worker ID is required");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Lease duration must be positive");
        }

        List<AnalysisWorkItem> claimed = jdbc.query(
                """
                WITH claimed AS (
                    UPDATE analysis_jobs
                    SET state = 'PROCESSING', attempt = attempt + 1, lease_owner = ?,
                        lease_expires_at = ?, started_at = COALESCE(started_at, ?),
                        next_attempt_at = NULL, version = version + 1, updated_at = ?
                    WHERE id = ?
                      AND (
                        state = 'QUEUED'
                        OR (state = 'RETRY_SCHEDULED' AND next_attempt_at <= ?)
                        OR (state = 'PROCESSING' AND lease_expires_at <= ?)
                      )
                    RETURNING *
                  )
                SELECT c.id, c.organization_id, c.source_id, c.usage_reservation_id, c.state,
                       c.request_fingerprint, c.idempotency_key, c.provider, c.model, c.attempt,
                       c.queued_at, c.output_profile_json::text, s.canonical_uri, s.type AS source_type,
                       s.metadata_json->>'objectKey' AS object_key,
                       s.metadata_json->>'contentType' AS content_type,
                       COALESCE((s.metadata_json->>'sizeBytes')::bigint, 0) AS content_length,
                       s.metadata_json->>'geminiFileName' AS provider_file_name,
                       e.correlation_id, r.reserved_units
                FROM claimed c
                JOIN sources s ON s.id = c.source_id AND s.organization_id = c.organization_id
                JOIN usage_reservations r ON r.id = c.usage_reservation_id
                JOIN LATERAL (
                    SELECT correlation_id FROM outbox_events
                    WHERE aggregate_type = 'AnalysisJob' AND aggregate_id = c.id
                      AND event_type = 'AnalysisRequested'
                    ORDER BY occurred_at LIMIT 1
                ) e ON TRUE
                """,
                JdbcAnalysisJobStore::mapWorkItem,
                workerId,
                Timestamp.from(now.plus(leaseDuration)),
                Timestamp.from(now),
                Timestamp.from(now),
                jobId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        claimed.stream().findFirst().ifPresent(item -> jdbc.update(
                """
                UPDATE usage_reservations SET expires_at = GREATEST(expires_at, ?), updated_at = ?
                WHERE id = ? AND status = 'RESERVED'
                """,
                Timestamp.from(now.plus(leaseDuration).plusSeconds(30)),
                Timestamp.from(now),
                item.job().usageReservationId()
        ));
        return claimed.stream().findFirst();
    }

    @Override
    public void complete(
            AnalysisWorkItem workItem,
            String workerId,
            GenerationAccounting accounting,
            String validatedContentJson,
            String promptVersion,
            String schemaVersion,
            Instant now
    ) {
        lockOwnedAttempt(workItem, workerId);
        UUID generationId = insertGeneration(
                workItem, accounting, validatedContentJson, "VALID", null, promptVersion, schemaVersion, now
        );
        insertCost(workItem, accounting, now);

        UUID packageId = upsertAndLockPackage(workItem, now);
        Integer revisionNo = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_no), 0) + 1 FROM package_revisions WHERE package_id = ?",
                Integer.class,
                packageId
        );
        UUID revisionId = UuidV7Generator.generate();
        jdbc.update(
                """
                INSERT INTO package_revisions(
                    id, organization_id, package_id, revision_no, based_on_generation_id,
                    content_json, verification_state, created_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), 'AUTO_VALIDATED', ?)
                """,
                revisionId, workItem.job().organizationId(), packageId, revisionNo,
                generationId, validatedContentJson, Timestamp.from(now)
        );
        jdbc.update(
                """
                UPDATE learning_packages
                SET current_revision_id = ?, publication_state = 'GENERATED',
                    version = version + 1, updated_at = ?
                WHERE id = ?
                """,
                revisionId, Timestamp.from(now), packageId
        );
        int updated = jdbc.update(
                """
                UPDATE analysis_jobs
                SET state = 'COMPLETED', completed_at = ?, lease_owner = NULL,
                    lease_expires_at = NULL, error_code = NULL, error_detail = NULL,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND state = 'PROCESSING' AND lease_owner = ? AND attempt = ?
                """,
                Timestamp.from(now), Timestamp.from(now), workItem.job().id(), workerId, workItem.job().attempt()
        );
        if (updated != 1) {
            throw new AnalysisLeaseLostException();
        }
        insertOutbox(workItem, "AnalysisCompleted", revisionId, now);
    }

    @Override
    public void fail(
            AnalysisWorkItem workItem,
            String workerId,
            GenerationAccounting accounting,
            String errorCode,
            String safeErrorDetail,
            boolean terminal,
            Instant retryAt,
            String promptVersion,
            String schemaVersion,
            Instant now
    ) {
        lockOwnedAttempt(workItem, workerId);
        if (accounting != null) {
            insertGeneration(
                    workItem, accounting, null, "INVALID", safeErrorDetail, promptVersion, schemaVersion, now
            );
            insertCost(workItem, accounting, now);
        }
        int updated = jdbc.update(
                """
                UPDATE analysis_jobs
                SET state = ?, error_code = ?, error_detail = ?, next_attempt_at = ?,
                    completed_at = CAST(? AS timestamptz),
                    lease_owner = NULL, lease_expires_at = NULL,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND state = 'PROCESSING' AND lease_owner = ? AND attempt = ?
                """,
                terminal ? "FAILED" : "RETRY_SCHEDULED",
                errorCode,
                safeErrorDetail,
                terminal ? null : Timestamp.from(retryAt),
                terminal ? Timestamp.from(now) : null,
                Timestamp.from(now),
                workItem.job().id(),
                workerId,
                workItem.job().attempt()
        );
        if (updated != 1) {
            throw new AnalysisLeaseLostException();
        }
        if (terminal) {
            insertOutbox(workItem, "AnalysisFailed", workItem.job().id(), now);
        }
    }

    private void lockOwnedAttempt(AnalysisWorkItem workItem, String workerId) {
        List<UUID> owned = jdbc.query(
                """
                SELECT id FROM analysis_jobs
                WHERE id = ? AND state = 'PROCESSING' AND lease_owner = ? AND attempt = ?
                FOR UPDATE
                """,
                (result, row) -> result.getObject("id", UUID.class),
                workItem.job().id(), workerId, workItem.job().attempt()
        );
        if (owned.size() != 1) {
            throw new AnalysisLeaseLostException();
        }
    }

    private UUID insertGeneration(
            AnalysisWorkItem workItem,
            GenerationAccounting accounting,
            String outputJson,
            String validationState,
            String validationError,
            String promptVersion,
            String schemaVersion,
            Instant now
    ) {
        UUID id = UuidV7Generator.generate();
        var generation = accounting.generation();
        jdbc.update(
                """
                INSERT INTO generation_runs(
                    id, organization_id, job_id, attempt, provider, model, model_version,
                    prompt_version, schema_version, request_fingerprint, input_tokens,
                    output_tokens, thought_tokens, latency_ms, retry_count,
                    actual_cost_microusd, shadow_cost_microusd, output_json, raw_output,
                    validation_state, validation_error, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                id, workItem.job().organizationId(), workItem.job().id(), workItem.job().attempt(),
                generation.provider(), generation.model(), generation.modelVersion(), promptVersion,
                schemaVersion, workItem.job().requestFingerprint(), generation.inputTokens(),
                generation.outputTokens(), generation.thoughtTokens(), generation.latencyMs(),
                generation.retryCount(), accounting.actualCostMicrousd(), accounting.shadowCostMicrousd(),
                outputJson, generation.output(), validationState, validationError, Timestamp.from(now)
        );
        return id;
    }

    private void insertCost(AnalysisWorkItem workItem, GenerationAccounting accounting, Instant now) {
        var generation = accounting.generation();
        jdbc.update(
                """
                INSERT INTO cost_ledger(
                    id, organization_id, operation, provider, model, input_tokens, output_tokens,
                    thought_tokens, actual_cost_microusd, shadow_cost_microusd, latency_ms,
                    retry_count, correlation_id, occurred_at
                ) VALUES (?, ?, 'LEARNING_PACKAGE_GENERATION', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), workItem.job().organizationId(), generation.provider(),
                generation.model(), generation.inputTokens(), generation.outputTokens(),
                generation.thoughtTokens(), accounting.actualCostMicrousd(), accounting.shadowCostMicrousd(),
                generation.latencyMs(), generation.retryCount(), workItem.correlationId(), Timestamp.from(now)
        );
    }

    private UUID upsertAndLockPackage(AnalysisWorkItem workItem, Instant now) {
        jdbc.update(
                """
                INSERT INTO learning_packages(id, organization_id, source_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, source_id) DO NOTHING
                """,
                UuidV7Generator.generate(), workItem.job().organizationId(), workItem.job().sourceId(),
                Timestamp.from(now), Timestamp.from(now)
        );
        return jdbc.queryForObject(
                "SELECT id FROM learning_packages WHERE organization_id = ? AND source_id = ? FOR UPDATE",
                UUID.class,
                workItem.job().organizationId(), workItem.job().sourceId()
        );
    }

    private void insertOutbox(AnalysisWorkItem workItem, String eventType, UUID relatedId, Instant now) {
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, ?, 1, 'AnalysisJob', ?, ?,
                          jsonb_build_object('jobId', CAST(? AS text), 'relatedId', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), workItem.job().organizationId(), eventType, workItem.job().id(),
                workItem.correlationId(), workItem.job().id(), relatedId,
                Timestamp.from(now), Timestamp.from(now)
        );
    }

    private static AnalysisWorkItem mapWorkItem(ResultSet result, int rowNumber) throws SQLException {
        return new AnalysisWorkItem(
                mapJob(result, rowNumber),
                new AnalysisSource(
                        result.getObject("source_id", UUID.class),
                        AnalysisSource.Type.valueOf(result.getString("source_type")),
                        result.getString("canonical_uri"), result.getString("object_key"),
                        result.getString("content_type"), result.getLong("content_length"),
                        result.getString("provider_file_name")
                ),
                result.getString("output_profile_json"),
                result.getString("correlation_id"),
                result.getLong("reserved_units")
        );
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
