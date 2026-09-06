package com.vid2knowledge.delivery;

import com.vid2knowledge.analysis.application.LearningPackageCodec;
import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PackageWorkflowService {

    private final JdbcTemplate jdbc;
    private final LearningPackageCodec codec;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PackageWorkflowService(JdbcTemplate jdbc, LearningPackageCodec codec, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    public PackageView get(UUID organizationId, UUID packageId) {
        List<PackageView> matches = jdbc.query(
                """
                SELECT p.id, p.source_id, p.publication_state, p.version, r.id AS revision_id,
                       r.revision_no, r.verification_state, r.content_json::text AS content
                FROM learning_packages p
                JOIN package_revisions r ON r.id = p.current_revision_id AND r.package_id = p.id
                WHERE p.organization_id = ? AND p.id = ?
                """,
                (result, row) -> new PackageView(
                        result.getObject("id", UUID.class), result.getObject("source_id", UUID.class),
                        result.getString("publication_state"), result.getLong("version"),
                        result.getObject("revision_id", UUID.class), result.getInt("revision_no"),
                        result.getString("verification_state"), objectMapper.readTree(result.getString("content"))
                ),
                organizationId, packageId
        );
        return matches.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found")
        );
    }

    @Transactional
    public PackageView saveDraft(
            CurrentActor actor,
            UUID packageId,
            long expectedVersion,
            JsonNode content,
            String correlationId
    ) {
        List<PackageSource> sources = jdbc.query(
                """
                SELECT s.canonical_uri, s.duration_seconds FROM learning_packages p
                JOIN sources s ON s.id = p.source_id AND s.organization_id = p.organization_id
                WHERE p.id = ? AND p.organization_id = ? AND p.publication_state <> 'ARCHIVED'
                FOR UPDATE OF p
                """,
                (result, row) -> new PackageSource(
                        result.getString("canonical_uri"), result.getLong("duration_seconds")
                ), packageId, actor.organizationId()
        );
        PackageSource source = sources.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found")
        );
        String validated = codec.write(codec.parseAndValidate(
                content.toString(), source.uri(), source.durationSeconds()
        ));
        Integer nextRevision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision_no), 0) + 1 FROM package_revisions WHERE package_id = ?",
                Integer.class, packageId
        );
        UUID revisionId = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO package_revisions(
                    id, organization_id, package_id, revision_no, content_json,
                    edited_by, verification_state, created_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, 'UNVERIFIED', ?)
                """,
                revisionId, actor.organizationId(), packageId, nextRevision, validated,
                actor.userId(), Timestamp.from(now)
        );
        int updated = jdbc.update(
                """
                UPDATE learning_packages SET current_revision_id = ?, publication_state = 'DRAFT',
                    version = version + 1, updated_at = ?
                WHERE id = ? AND organization_id = ? AND version = ?
                """,
                revisionId, Timestamp.from(now), packageId, actor.organizationId(), expectedVersion
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Package version has changed");
        }
        audit(actor, "PACKAGE_DRAFT_SAVED", packageId, correlationId, now);
        return get(actor.organizationId(), packageId);
    }

    @Transactional
    public PackageView transition(
            CurrentActor actor,
            UUID packageId,
            Transition transition,
            String correlationId
    ) {
        String fromStates;
        String target;
        String verification = null;
        switch (transition) {
            case SUBMIT_REVIEW -> {
                fromStates = "('GENERATED','DRAFT','REJECTED')";
                target = "IN_REVIEW";
            }
            case APPROVE -> {
                fromStates = "('IN_REVIEW')";
                target = "APPROVED";
                verification = "HUMAN_VERIFIED";
            }
            case REJECT -> {
                fromStates = "('IN_REVIEW')";
                target = "REJECTED";
                verification = "REJECTED";
            }
            case PUBLISH -> {
                fromStates = "('APPROVED')";
                target = "PUBLISHED";
            }
            case ARCHIVE -> {
                fromStates = "('GENERATED','DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED')";
                target = "ARCHIVED";
            }
            default -> throw new IllegalStateException("Unsupported transition");
        }
        Instant now = clock.instant();
        int updated = jdbc.update(
                "UPDATE learning_packages SET publication_state = ?, version = version + 1, updated_at = ? "
                        + "WHERE id = ? AND organization_id = ? AND publication_state IN " + fromStates,
                target, Timestamp.from(now), packageId, actor.organizationId()
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Package transition is not allowed");
        }
        if (verification != null) {
            jdbc.update(
                    """
                    UPDATE package_revisions r SET verification_state = ?
                    FROM learning_packages p
                    WHERE p.id = ? AND p.organization_id = ? AND r.id = p.current_revision_id
                    """,
                    verification, packageId, actor.organizationId()
            );
        }
        audit(actor, "PACKAGE_" + target, packageId, correlationId, now);
        if (transition == Transition.PUBLISH) {
            outbox(actor, packageId, correlationId, now);
        }
        return get(actor.organizationId(), packageId);
    }

    private void audit(CurrentActor actor, String action, UUID packageId, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, created_at
                ) VALUES (?, ?, ?, ?, 'LearningPackage', ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action,
                packageId, correlationId, Timestamp.from(now)
        );
    }

    private void outbox(CurrentActor actor, UUID packageId, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'PackagePublished', 1, 'LearningPackage', ?, ?,
                          jsonb_build_object('packageId', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), packageId, correlationId, packageId,
                Timestamp.from(now), Timestamp.from(now)
        );
    }

    public enum Transition {
        SUBMIT_REVIEW,
        APPROVE,
        REJECT,
        PUBLISH,
        ARCHIVE
    }

    public record PackageView(
            UUID id,
            UUID sourceId,
            String state,
            long version,
            UUID revisionId,
            int revisionNo,
            String verificationState,
            JsonNode content
    ) {
    }

    private record PackageSource(String uri, long durationSeconds) {
    }
}
