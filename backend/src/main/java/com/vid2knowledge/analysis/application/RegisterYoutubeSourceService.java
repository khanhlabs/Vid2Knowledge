package com.vid2knowledge.analysis.application;

import com.vid2knowledge.analysis.application.port.VideoMetadataProvider;
import com.vid2knowledge.analysis.domain.RegisteredSource;
import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class RegisterYoutubeSourceService {

    private static final String TERMS_VERSION = "source-rights-v1";

    private final JdbcTemplate jdbc;
    private final YoutubeUrlParser urlParser;
    private final VideoMetadataProvider metadataProvider;
    private final Clock clock;

    @Autowired
    public RegisterYoutubeSourceService(
            JdbcTemplate jdbc,
            YoutubeUrlParser urlParser,
            VideoMetadataProvider metadataProvider
    ) {
        this(jdbc, urlParser, metadataProvider, Clock.systemUTC());
    }

    RegisterYoutubeSourceService(
            JdbcTemplate jdbc,
            YoutubeUrlParser urlParser,
            VideoMetadataProvider metadataProvider,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.urlParser = urlParser;
        this.metadataProvider = metadataProvider;
        this.clock = clock;
    }

    @Transactional
    public RegisteredSource register(
            CurrentActor actor,
            String youtubeUrl,
            RightsBasis rightsBasis,
            boolean termsAccepted,
            String correlationId
    ) {
        if (!termsAccepted) {
            throw new IllegalArgumentException("Source rights terms must be accepted");
        }
        var normalized = urlParser.parse(youtubeUrl);
        var metadata = metadataProvider.fetch(normalized.videoId());
        Instant now = clock.instant();

        List<UUID> existing = jdbc.query(
                "SELECT id FROM sources WHERE organization_id = ? AND canonical_uri = ?",
                (result, row) -> result.getObject("id", UUID.class),
                actor.organizationId(), normalized.canonicalUrl()
        );
        UUID sourceId = existing.stream().findFirst().orElseGet(UuidV7Generator::generate);
        if (existing.isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO sources(
                        id, organization_id, type, canonical_uri, external_id, metadata_json,
                        duration_seconds, metadata_verified_at, created_by, created_at
                    ) VALUES (?, ?, 'YOUTUBE', ?, ?,
                              jsonb_build_object('title', ?, 'language', ?), ?, ?, ?, ?)
                    """,
                    sourceId, actor.organizationId(), normalized.canonicalUrl(), normalized.videoId(),
                    metadata.title(), metadata.language(), metadata.durationSeconds(), Timestamp.from(now),
                    actor.userId(), Timestamp.from(now)
            );
            insertOutbox(actor, sourceId, correlationId, now);
        } else {
            jdbc.update(
                    """
                    UPDATE sources
                    SET metadata_json = jsonb_build_object('title', ?, 'language', ?),
                        duration_seconds = ?, metadata_verified_at = ?
                    WHERE id = ? AND organization_id = ?
                    """,
                    metadata.title(), metadata.language(), metadata.durationSeconds(), Timestamp.from(now),
                    sourceId, actor.organizationId()
            );
        }

        jdbc.update(
                """
                INSERT INTO rights_attestations(
                    id, organization_id, source_id, attested_by, basis, terms_version, attested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_id, terms_version, attested_by)
                DO UPDATE SET basis = EXCLUDED.basis, attested_at = EXCLUDED.attested_at, revoked_at = NULL
                """,
                UuidV7Generator.generate(), actor.organizationId(), sourceId, actor.userId(),
                rightsBasis.name(), TERMS_VERSION, Timestamp.from(now)
        );
        audit(actor, sourceId, rightsBasis, correlationId, now);
        return find(actor.organizationId(), sourceId);
    }

    private RegisteredSource find(UUID organizationId, UUID sourceId) {
        return jdbc.queryForObject(
                """
                SELECT id, organization_id, canonical_uri, external_id,
                       metadata_json->>'title' AS title, metadata_json->>'language' AS language,
                       duration_seconds, metadata_verified_at
                FROM sources WHERE id = ? AND organization_id = ?
                """,
                (result, row) -> new RegisteredSource(
                        result.getObject("id", UUID.class),
                        result.getObject("organization_id", UUID.class),
                        result.getString("canonical_uri"),
                        result.getString("external_id"),
                        result.getString("title"),
                        result.getString("language"),
                        result.getLong("duration_seconds"),
                        result.getTimestamp("metadata_verified_at").toInstant()
                ),
                sourceId, organizationId
        );
    }

    private void insertOutbox(CurrentActor actor, UUID sourceId, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'SourceRegistered', 1, 'Source', ?, ?,
                          jsonb_build_object('sourceId', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), sourceId, correlationId, sourceId,
                Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void audit(
            CurrentActor actor,
            UUID sourceId,
            RightsBasis basis,
            String correlationId,
            Instant now
    ) {
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type,
                    resource_id, metadata_json, correlation_id, created_at
                ) VALUES (?, ?, ?, 'SOURCE_RIGHTS_ATTESTED', 'Source', ?,
                          jsonb_build_object('basis', ?, 'termsVersion', ?), ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), sourceId,
                basis.name(), TERMS_VERSION, correlationId, Timestamp.from(now)
        );
    }

    public enum RightsBasis {
        OWNER,
        LICENSED,
        PERMISSION,
        PUBLIC_DOMAIN
    }
}
