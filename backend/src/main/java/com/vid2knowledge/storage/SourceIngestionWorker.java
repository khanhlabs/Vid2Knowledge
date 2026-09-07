package com.vid2knowledge.storage;

import com.vid2knowledge.analysis.application.AiProviderException;
import com.vid2knowledge.analysis.infrastructure.GeminiFileClient;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.ObjectStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class SourceIngestionWorker {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(20);
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStorage storage;
    private final GeminiFileClient files;
    private final ObjectStorageProperties properties;
    private final Clock clock;

    public SourceIngestionWorker(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectStorage storage,
            GeminiFileClient files, ObjectStorageProperties properties
    ) {
        this(jdbc, transactions, storage, files, properties, Clock.systemUTC());
    }

    SourceIngestionWorker(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectStorage storage,
            GeminiFileClient files, ObjectStorageProperties properties, Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.storage = storage;
        this.files = files;
        this.properties = properties;
        this.clock = clock;
    }

    public WorkResult process(UUID uploadId, String workerId) {
        Ingestion item = claim(uploadId, workerId);
        if (item == null) return WorkResult.NOT_CLAIMED;
        GeminiFileClient.UploadedFile uploaded = null;
        boolean retainedForAnalysis = false;
        String durableKey = "organizations/" + item.organizationId() + "/sources/" + item.targetSourceId()
                + item.objectKey().substring(item.objectKey().lastIndexOf('.'));
        try {
            uploaded = files.uploadAndAwait(
                    item.objectKey(), item.filename(), item.contentType(), item.sizeBytes()
            );
            if (uploaded.durationSeconds() <= 0
                    || uploaded.durationSeconds() > properties.maxVideoDurationSeconds()) {
                throw new AiProviderException("Uploaded video duration is outside the configured limit", false);
            }
            storage.copy(item.objectKey(), durableKey);
            complete(item, workerId, item.targetSourceId(), durableKey, uploaded);
            retainedForAnalysis = true;
            deleteQuietly(item.objectKey());
            return WorkResult.COMPLETED;
        } catch (RuntimeException exception) {
            boolean retryable = !(exception instanceof AiProviderException provider) || provider.retryable();
            boolean terminal = !retryable || item.attempt() >= MAX_ATTEMPTS;
            fail(item, workerId, safeMessage(exception), terminal);
            if (terminal) {
                deleteQuietly(item.objectKey());
                deleteQuietly(durableKey);
            }
            return terminal ? WorkResult.FAILED : WorkResult.RETRY_SCHEDULED;
        } finally {
            if (uploaded != null && !retainedForAnalysis) files.deleteQuietly(uploaded.name());
        }
    }

    private Ingestion claim(UUID uploadId, String workerId) {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("Worker ID is required");
        Instant now = clock.instant();
        List<Ingestion> claimed = jdbc.query(
                """
                WITH claimed AS (
                    UPDATE source_uploads
                    SET processing_attempt = processing_attempt + 1, lease_owner = ?, lease_expires_at = ?,
                        updated_at = ?
                    WHERE id = ? AND state = 'PROCESSING' AND source_id IS NULL
                      AND (lease_expires_at IS NULL OR lease_expires_at <= ?)
                    RETURNING *
                )
                SELECT c.id, c.organization_id, c.object_key, c.original_filename,
                       c.declared_content_type, c.declared_size_bytes, c.rights_basis,
                       c.terms_version, c.language_hint, c.processing_attempt, c.created_by,
                       c.target_source_id,
                       e.correlation_id
                FROM claimed c
                JOIN LATERAL (
                    SELECT correlation_id FROM outbox_events
                    WHERE aggregate_type = 'SourceUpload' AND aggregate_id = c.id
                      AND event_type = 'SourceUploadIngestionRequested'
                    ORDER BY occurred_at LIMIT 1
                ) e ON TRUE
                """,
                (result, row) -> new Ingestion(
                        result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                        result.getString("object_key"), result.getString("original_filename"),
                        result.getString("declared_content_type"), result.getLong("declared_size_bytes"),
                        result.getString("rights_basis"), result.getString("terms_version"),
                        result.getString("language_hint"), result.getInt("processing_attempt"),
                        result.getObject("created_by", UUID.class),
                        result.getObject("target_source_id", UUID.class), result.getString("correlation_id")
                ),
                workerId, Timestamp.from(now.plus(LEASE_DURATION)), Timestamp.from(now), uploadId, Timestamp.from(now)
        );
        return claimed.stream().findFirst().orElse(null);
    }

    private void complete(Ingestion item, String workerId, UUID sourceId, String durableKey,
                          GeminiFileClient.UploadedFile uploaded) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            requireLease(item, workerId);
            jdbc.update(
                    """
                    INSERT INTO sources(
                        id, organization_id, type, canonical_uri, external_id, metadata_json,
                        duration_seconds, metadata_verified_at, created_by, created_at
                    ) VALUES (?, ?, 'UPLOAD', ?, ?,
                              jsonb_build_object('title', ?, 'language', ?, 'objectKey', ?,
                                                 'contentType', ?, 'sizeBytes', ?,
                                                 'geminiFileName', ?), ?, ?, ?, ?)
                    """,
                    sourceId, item.organizationId(), "v2k-upload:" + item.id(), item.id().toString(),
                    item.filename(), item.languageHint(), durableKey, item.contentType(), item.sizeBytes(),
                    uploaded.name(), uploaded.durationSeconds(), Timestamp.from(now),
                    item.createdBy(), Timestamp.from(now)
            );
            jdbc.update(
                    """
                    INSERT INTO rights_attestations(
                        id, organization_id, source_id, attested_by, basis, terms_version, attested_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UuidV7Generator.generate(), item.organizationId(), sourceId, item.createdBy(),
                    item.rightsBasis(), item.termsVersion(), Timestamp.from(now)
            );
            jdbc.update(
                    """
                    UPDATE source_uploads SET state = 'READY', source_id = ?, failure_reason = NULL,
                        lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                    WHERE id = ? AND organization_id = ?
                    """,
                    sourceId, Timestamp.from(now), item.id(), item.organizationId()
            );
            outbox(item, sourceId, now);
            audit(item, "SOURCE_UPLOAD_READY", sourceId, now);
        });
    }

    private void fail(Ingestion item, String workerId, String reason, boolean terminal) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            requireLease(item, workerId);
            jdbc.update(
                    """
                    UPDATE source_uploads SET state = ?, failure_reason = ?, lease_owner = NULL,
                        lease_expires_at = NULL, updated_at = ? WHERE id = ? AND organization_id = ?
                    """,
                    terminal ? "REJECTED" : "PROCESSING", reason, Timestamp.from(now),
                    item.id(), item.organizationId()
            );
            if (terminal) audit(item, "SOURCE_UPLOAD_REJECTED", item.id(), now);
        });
    }

    private void requireLease(Ingestion item, String workerId) {
        List<UUID> owned = jdbc.query(
                """
                SELECT id FROM source_uploads
                WHERE id = ? AND organization_id = ? AND state = 'PROCESSING'
                  AND source_id IS NULL AND lease_owner = ? AND processing_attempt = ?
                FOR UPDATE
                """,
                (result, row) -> result.getObject("id", UUID.class),
                item.id(), item.organizationId(), workerId, item.attempt()
        );
        if (owned.size() != 1) throw new IllegalStateException("Source ingestion lease was lost");
    }

    private void outbox(Ingestion item, UUID sourceId, Instant now) {
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'SourceRegistered', 1, 'Source', ?, ?,
                          jsonb_build_object('sourceId', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), item.organizationId(), sourceId, item.correlationId(), sourceId,
                Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void audit(Ingestion item, String action, UUID resourceId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(id, organization_id, actor_user_id, action, resource_type,
                                       resource_id, correlation_id, created_at)
                VALUES (?, ?, ?, ?, 'SourceUpload', ?, ?, ?)
                """,
                UuidV7Generator.generate(), item.organizationId(), item.createdBy(), action,
                resourceId, item.correlationId(), Timestamp.from(now)
        );
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException ignored) {
            // Pending-prefix lifecycle is the final cleanup guarantee.
        }
    }

    private static String safeMessage(RuntimeException failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) value = failure.getClass().getSimpleName();
        return value.substring(0, Math.min(value.length(), 500));
    }

    private record Ingestion(
            UUID id, UUID organizationId, String objectKey, String filename, String contentType,
            long sizeBytes, String rightsBasis, String termsVersion, String languageHint,
            int attempt, UUID createdBy, UUID targetSourceId, String correlationId
    ) { }

    public enum WorkResult { NOT_CLAIMED, COMPLETED, RETRY_SCHEDULED, FAILED }
}
