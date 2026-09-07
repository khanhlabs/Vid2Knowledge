package com.vid2knowledge.storage;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.ObjectStorageProperties;
import com.vid2knowledge.analysis.application.RegisterYoutubeSourceService.RightsBasis;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class SourceUploadService {
    private static final Map<String, String> EXTENSIONS = Map.of(
            "video/mp4", ".mp4",
            "video/webm", ".webm"
    );

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectStorage storage;
    private final ObjectStorageProperties properties;
    private final Clock clock;

    public SourceUploadService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectStorage storage,
            ObjectStorageProperties properties
    ) {
        this(jdbc, transactions, storage, properties, Clock.systemUTC());
    }

    SourceUploadService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            ObjectStorage storage,
            ObjectStorageProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    public UploadReservation reserve(CurrentActor actor, String filename, String contentType, long sizeBytes,
                                     String correlationId) {
        requirePaidPrivateSources(actor.organizationId());
        String normalizedType = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.get(normalizedType);
        if (extension == null) {
            throw new IllegalArgumentException("Only MP4 and WebM video uploads are currently supported");
        }
        String safeFilename = filename(filename, extension);
        if (sizeBytes < 1_024 || sizeBytes > properties.maxUploadBytes()) {
            throw new IllegalArgumentException("Upload size is outside the configured limit");
        }
        Long pending = jdbc.queryForObject(
                "SELECT count(*) FROM source_uploads WHERE organization_id = ? AND state = 'REQUESTED' AND expires_at > ?",
                Long.class, actor.organizationId(), Timestamp.from(clock.instant())
        );
        if (pending != null && pending >= 10) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many pending uploads");
        }

        UUID id = UuidV7Generator.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.presignDuration());
        String objectKey = "pending-source-uploads/organizations/" + actor.organizationId()
                + "/" + id + "/original" + extension;
        jdbc.update(
                """
                INSERT INTO source_uploads(
                    id, organization_id, object_key, original_filename, declared_content_type,
                    declared_size_bytes, expires_at, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), objectKey, safeFilename, normalizedType, sizeBytes,
                Timestamp.from(expiresAt), actor.userId(), Timestamp.from(now), Timestamp.from(now)
        );
        audit(actor, "SOURCE_UPLOAD_RESERVED", id, correlationId, now);
        URI uploadUrl;
        try {
            uploadUrl = storage.presignPut(objectKey, normalizedType, sizeBytes, properties.presignDuration());
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE source_uploads SET state = 'REJECTED', failure_reason = 'PRESIGN_FAILED', updated_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant()), id);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not create upload URL", failure);
        }
        return new UploadReservation(
                id, safeFilename, normalizedType, sizeBytes, uploadUrl, Map.of("Content-Type", normalizedType), expiresAt
        );
    }

    public UploadView complete(CurrentActor actor, UUID uploadId, String correlationId) {
        UploadRecord record = record(actor.organizationId(), uploadId);
        if (!"REQUESTED".equals(record.state())) return view(record);
        Instant now = clock.instant();
        if (!record.expiresAt().isAfter(now)) {
            deleteQuietly(record.objectKey());
            reject(actor, record, "UPLOAD_EXPIRED", "EXPIRED", correlationId);
            throw new ResponseStatusException(HttpStatus.GONE, "Upload reservation expired");
        }

        ObjectStorage.StoredObject object;
        byte[] prefix;
        try {
            object = storage.head(record.objectKey());
            prefix = storage.readPrefix(record.objectKey(), 16);
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Uploaded object could not be verified", failure);
        }
        String actualType = object.contentType() == null ? "" : object.contentType().toLowerCase(Locale.ROOT);
        if (object.contentLength() != record.sizeBytes() || !actualType.equals(record.contentType())
                || !matchesMagic(record.contentType(), prefix)) {
            deleteQuietly(record.objectKey());
            reject(actor, record, "OBJECT_METADATA_OR_SIGNATURE_MISMATCH", "REJECTED", correlationId);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Uploaded file failed verification");
        }
        if (object.eTag() == null || object.eTag().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Object storage did not return an ETag");
        }

        UploadView updated = transactions.execute(status -> {
            int changed = jdbc.update(
                    """
                    UPDATE source_uploads SET state = 'STORAGE_VERIFIED', object_etag = ?, verified_at = ?, updated_at = ?
                    WHERE id = ? AND organization_id = ? AND state = 'REQUESTED'
                    """,
                    object.eTag(), Timestamp.from(now), Timestamp.from(now), uploadId, actor.organizationId()
            );
            if (changed == 1) audit(actor, "SOURCE_UPLOAD_STORAGE_VERIFIED", uploadId, correlationId, now);
            return view(record(actor.organizationId(), uploadId));
        });
        return updated == null ? view(record(actor.organizationId(), uploadId)) : updated;
    }

    public UploadView requestIngestion(
            CurrentActor actor, UUID uploadId, RightsBasis rightsBasis, boolean termsAccepted,
            String languageHint, String correlationId
    ) {
        requirePaidPrivateSources(actor.organizationId());
        if (!termsAccepted) throw new IllegalArgumentException("Source rights terms must be accepted");
        if (rightsBasis == null) throw new IllegalArgumentException("Source rights basis is required");
        String language = languageHint == null ? "" : languageHint.trim().toLowerCase(Locale.ROOT);
        if (!language.matches("^[a-z]{2,3}(-[a-z0-9]{2,8})?$") || language.length() > 16) {
            throw new IllegalArgumentException("Language hint is invalid");
        }
        UploadRecord record = record(actor.organizationId(), uploadId);
        if ("READY".equals(record.state()) || "PROCESSING".equals(record.state())) return view(record);
        if (!"STORAGE_VERIFIED".equals(record.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload is not ready for ingestion");
        }
        Instant now = clock.instant();
        UUID targetSourceId = UuidV7Generator.generate();
        UploadView updated = transactions.execute(status -> {
            int changed = jdbc.update(
                    """
                    UPDATE source_uploads
                    SET state = 'PROCESSING', rights_basis = ?, terms_version = 'source-rights-v1',
                        language_hint = ?, target_source_id = ?, failure_reason = NULL, updated_at = ?
                    WHERE id = ? AND organization_id = ? AND state = 'STORAGE_VERIFIED'
                    """,
                    rightsBasis.name(), language, targetSourceId, Timestamp.from(now), uploadId, actor.organizationId()
            );
            if (changed == 1) {
                audit(actor, "SOURCE_UPLOAD_INGESTION_REQUESTED", uploadId, correlationId, now);
                jdbc.update(
                        """
                        INSERT INTO outbox_events(
                            id, organization_id, event_type, event_version, aggregate_type,
                            aggregate_id, correlation_id, payload_json, occurred_at, available_at
                        ) VALUES (?, ?, 'SourceUploadIngestionRequested', 1, 'SourceUpload', ?, ?,
                                  jsonb_build_object('uploadId', CAST(? AS text)), ?, ?)
                        """,
                        UuidV7Generator.generate(), actor.organizationId(), uploadId, correlationId, uploadId,
                        Timestamp.from(now), Timestamp.from(now)
                );
            }
            return view(record(actor.organizationId(), uploadId));
        });
        return updated == null ? view(record(actor.organizationId(), uploadId)) : updated;
    }

    public List<UploadView> list(UUID organizationId) {
        expireDatabaseReservations(organizationId);
        return jdbc.query(
                """
                SELECT id, original_filename, declared_content_type, declared_size_bytes, state,
                       failure_reason, expires_at, verified_at, created_at, source_id
                FROM source_uploads WHERE organization_id = ? ORDER BY created_at DESC, id DESC LIMIT 100
                """,
                (result, row) -> new UploadView(
                        result.getObject("id", UUID.class), result.getString("original_filename"),
                        result.getString("declared_content_type"), result.getLong("declared_size_bytes"),
                        result.getString("state"), result.getString("failure_reason"),
                        result.getTimestamp("expires_at").toInstant(),
                        result.getTimestamp("verified_at") == null ? null : result.getTimestamp("verified_at").toInstant(),
                        result.getTimestamp("created_at").toInstant(), result.getObject("source_id", UUID.class)
                ), organizationId
        );
    }

    private void requirePaidPrivateSources(UUID organizationId) {
        Boolean active = jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM subscriptions s JOIN pricing_plans p ON p.id = s.plan_id
                    WHERE s.organization_id = ? AND s.status = 'ACTIVE' AND s.current_period_end > ?
                      AND (p.code LIKE 'TRAINING\\_TEAM\\_%' ESCAPE '\\' OR p.code LIKE 'BUSINESS\\_%' ESCAPE '\\')
                )
                """,
                Boolean.class, organizationId, Timestamp.from(clock.instant())
        );
        if (!Boolean.TRUE.equals(active)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Private source upload requires an active Training Team or Business plan");
        }
    }

    private UploadRecord record(UUID organizationId, UUID id) {
        return jdbc.query(
                """
                SELECT id, object_key, original_filename, declared_content_type, declared_size_bytes,
                       state, failure_reason, expires_at, object_etag, verified_at, created_at, source_id
                FROM source_uploads WHERE organization_id = ? AND id = ?
                """,
                (result, row) -> new UploadRecord(
                        result.getObject("id", UUID.class), result.getString("object_key"),
                        result.getString("original_filename"), result.getString("declared_content_type"),
                        result.getLong("declared_size_bytes"), result.getString("state"),
                        result.getString("failure_reason"), result.getTimestamp("expires_at").toInstant(),
                        result.getString("object_etag"),
                        result.getTimestamp("verified_at") == null ? null : result.getTimestamp("verified_at").toInstant(),
                        result.getTimestamp("created_at").toInstant(), result.getObject("source_id", UUID.class)
                ), organizationId, id
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found"));
    }

    private void reject(CurrentActor actor, UploadRecord record, String reason, String state, String correlationId) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            int changed = jdbc.update(
                    "UPDATE source_uploads SET state = ?, failure_reason = ?, updated_at = ? WHERE id = ? AND organization_id = ? AND state = 'REQUESTED'",
                    state, reason, Timestamp.from(now), record.id(), actor.organizationId()
            );
            if (changed == 1) audit(actor, "SOURCE_UPLOAD_" + state, record.id(), correlationId, now);
        });
    }

    private void expireDatabaseReservations(UUID organizationId) {
        jdbc.update(
                "UPDATE source_uploads SET state = 'EXPIRED', failure_reason = 'UPLOAD_EXPIRED', updated_at = ? WHERE organization_id = ? AND state = 'REQUESTED' AND expires_at <= ?",
                Timestamp.from(clock.instant()), organizationId, Timestamp.from(clock.instant())
        );
    }

    private static boolean matchesMagic(String type, byte[] bytes) {
        if ("video/mp4".equals(type)) {
            return bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
        }
        return bytes.length >= 4 && (bytes[0] & 0xff) == 0x1a && (bytes[1] & 0xff) == 0x45
                && (bytes[2] & 0xff) == 0xdf && (bytes[3] & 0xff) == 0xa3;
    }

    private static String filename(String filename, String extension) {
        String value = filename == null ? "" : filename.trim();
        if (value.isEmpty() || value.length() > 255 || value.contains("/") || value.contains("\\")
                || value.codePoints().anyMatch(Character::isISOControl)
                || !value.toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new IllegalArgumentException("Filename is invalid or does not match the content type");
        }
        return value;
    }

    private void deleteQuietly(String objectKey) {
        try {
            storage.delete(objectKey);
        } catch (RuntimeException ignored) {
            // The database state still blocks processing; lifecycle cleanup retries external deletion.
        }
    }

    private void audit(CurrentActor actor, String action, UUID resourceId, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(id, organization_id, actor_user_id, action, resource_type,
                                       resource_id, correlation_id, created_at)
                VALUES (?, ?, ?, ?, 'SourceUpload', ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action,
                resourceId, correlationId, Timestamp.from(now)
        );
    }

    private static UploadView view(UploadRecord record) {
        return new UploadView(record.id(), record.filename(), record.contentType(), record.sizeBytes(),
                record.state(), record.failureReason(), record.expiresAt(), record.verifiedAt(), record.createdAt(),
                record.sourceId());
    }

    private record UploadRecord(
            UUID id, String objectKey, String filename, String contentType, long sizeBytes,
            String state, String failureReason, Instant expiresAt, String eTag, Instant verifiedAt, Instant createdAt,
            UUID sourceId
    ) {}

    public record UploadReservation(
            UUID id, String filename, String contentType, long sizeBytes, URI uploadUrl,
            Map<String, String> requiredHeaders, Instant expiresAt
    ) {}

    public record UploadView(
            UUID id, String filename, String contentType, long sizeBytes, String state,
            String failureReason, Instant expiresAt, Instant verifiedAt, Instant createdAt, UUID sourceId
    ) {}
}
