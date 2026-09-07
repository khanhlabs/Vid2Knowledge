package com.vid2knowledge.storage;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.config.ObjectStorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class PrivateSourcePlaybackService {
    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final ObjectStorageProperties properties;
    private final Clock clock;

    public PrivateSourcePlaybackService(
            JdbcTemplate jdbc, ObjectStorage storage, ObjectStorageProperties properties
    ) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    public PlaybackUrl create(CurrentActor actor, UUID sourceId) {
        if (actor.role() == CurrentActor.Role.LEARNER && !assigned(actor, sourceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        List<StoredSource> matches = jdbc.query(
                """
                SELECT metadata_json->>'objectKey' AS object_key,
                       metadata_json->>'contentType' AS content_type
                FROM sources WHERE id = ? AND organization_id = ? AND type = 'UPLOAD'
                """,
                (result, row) -> new StoredSource(
                        result.getString("object_key"), result.getString("content_type")
                ), sourceId, actor.organizationId()
        );
        StoredSource source = matches.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found")
        );
        if (source.objectKey() == null || source.contentType() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        }
        Instant expiresAt = clock.instant().plus(properties.presignDuration());
        URI url = storage.presignGet(source.objectKey(), properties.presignDuration());
        return new PlaybackUrl(sourceId, source.contentType(), url, expiresAt);
    }

    private boolean assigned(CurrentActor actor, UUID sourceId) {
        Boolean allowed = jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM assignment_recipients ar
                    JOIN assignments a ON a.id = ar.assignment_id AND a.organization_id = ar.organization_id
                    JOIN lessons l ON l.id = a.lesson_id AND l.organization_id = a.organization_id
                    JOIN learning_packages p ON p.id = l.package_id AND p.organization_id = l.organization_id
                    WHERE ar.organization_id = ? AND ar.user_id = ? AND p.source_id = ?
                      AND a.state = 'PUBLISHED' AND a.available_at <= ?
                )
                """,
                Boolean.class, actor.organizationId(), actor.userId(), sourceId,
                java.sql.Timestamp.from(clock.instant())
        );
        return Boolean.TRUE.equals(allowed);
    }

    private record StoredSource(String objectKey, String contentType) { }

    public record PlaybackUrl(UUID sourceId, String contentType, URI url, Instant expiresAt) { }
}
