package com.vid2knowledge.delivery;

import com.vid2knowledge.analysis.application.LearningPackageCodec;
import com.vid2knowledge.analysis.domain.AnalysisSource;
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

    public List<PackageSummary> list(UUID organizationId) {
        return jdbc.query(
                """
                SELECT p.id, p.source_id, p.publication_state, p.version, r.revision_no,
                       r.verification_state, COALESCE(r.content_json #>> '{video,title}', 'Untitled') AS title
                FROM learning_packages p
                JOIN package_revisions r ON r.id = p.current_revision_id AND r.package_id = p.id
                WHERE p.organization_id = ? ORDER BY p.updated_at DESC, p.id DESC LIMIT 1000
                """,
                (result, row) -> new PackageSummary(
                        result.getObject("id", UUID.class), result.getObject("source_id", UUID.class),
                        result.getString("title"), result.getString("publication_state"), result.getLong("version"),
                        result.getInt("revision_no"), result.getString("verification_state")
                ), organizationId
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
                SELECT s.id, s.type, s.canonical_uri, s.duration_seconds,
                       s.metadata_json->>'objectKey' AS object_key,
                       s.metadata_json->>'contentType' AS content_type,
                       COALESCE((s.metadata_json->>'sizeBytes')::bigint, 0) AS content_length,
                       s.metadata_json->>'geminiFileName' AS provider_file_name
                FROM learning_packages p
                JOIN sources s ON s.id = p.source_id AND s.organization_id = p.organization_id
                WHERE p.id = ? AND p.organization_id = ? AND p.publication_state <> 'ARCHIVED'
                FOR UPDATE OF p
                """,
                (result, row) -> new PackageSource(
                        new AnalysisSource(
                                result.getObject("id", UUID.class),
                                AnalysisSource.Type.valueOf(result.getString("type")),
                                result.getString("canonical_uri"), result.getString("object_key"),
                                result.getString("content_type"), result.getLong("content_length"),
                                result.getString("provider_file_name")
                        ), result.getLong("duration_seconds")
                ), packageId, actor.organizationId()
        );
        PackageSource source = sources.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found")
        );
        String validated = codec.write(codec.parseAndValidate(
                content.toString(), source.source(), source.durationSeconds()
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
        return transition(actor, packageId, transition, null, correlationId);
    }

    @Transactional
    public PackageView transition(
            CurrentActor actor,
            UUID packageId,
            Transition transition,
            String reason,
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
                if (reason == null || reason.trim().length() < 3 || reason.trim().length() > 1000) {
                    throw new IllegalArgumentException("A rejection reason between 3 and 1000 characters is required");
                }
                fromStates = "('IN_REVIEW')";
                target = "REJECTED";
                verification = "REJECTED";
            }
            case PUBLISH -> {
                boolean approvalRequired = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT approval_required FROM organizations WHERE id = ?",
                        Boolean.class, actor.organizationId()
                ));
                fromStates = approvalRequired ? "('APPROVED')" : "('GENERATED','DRAFT','REJECTED','APPROVED')";
                target = "PUBLISHED";
                if (!approvalRequired) {
                    verification = "HUMAN_VERIFIED";
                }
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
        if (transition == Transition.APPROVE || transition == Transition.REJECT) {
            recordDecision(actor, packageId, target, reason, now);
        }
        if (transition == Transition.APPROVE || transition == Transition.PUBLISH) {
            extractQuestionBank(actor.organizationId(), packageId, now);
        }
        audit(actor, "PACKAGE_" + target, packageId, correlationId, now);
        if (transition == Transition.PUBLISH) {
            outbox(actor, packageId, correlationId, now);
        }
        return get(actor.organizationId(), packageId);
    }

    private void recordDecision(
            CurrentActor actor, UUID packageId, String decision, String reason, Instant now
    ) {
        jdbc.update(
                """
                INSERT INTO review_decisions(
                    id, organization_id, package_id, package_revision_id,
                    reviewer_id, decision, reason, created_at
                )
                SELECT ?, p.organization_id, p.id, p.current_revision_id, ?, ?, ?, ?
                FROM learning_packages p WHERE p.organization_id = ? AND p.id = ?
                """,
                UuidV7Generator.generate(), actor.userId(), decision,
                reason == null ? null : reason.trim(), Timestamp.from(now), actor.organizationId(), packageId
        );
    }

    private void extractQuestionBank(UUID organizationId, UUID packageId, Instant now) {
        PackageView value = get(organizationId, packageId);
        for (JsonNode question : value.content().path("quiz")) {
            jdbc.update(
                    """
                    INSERT INTO question_bank_items(
                        id, organization_id, package_revision_id, source_item_id, question,
                        options_json, correct_answer_index, explanation, source_timestamp_seconds,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                    ON CONFLICT (package_revision_id, source_item_id) DO NOTHING
                    """,
                    UuidV7Generator.generate(), organizationId, value.revisionId(), question.path("id").asText(),
                    question.path("question").asText(), question.path("options").toString(),
                    question.path("correctAnswerIndex").asInt(), question.path("explanation").asText(),
                    question.path("source").path("timestampSeconds").asLong(),
                    Timestamp.from(now), Timestamp.from(now)
            );
        }
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

    public record PackageSummary(
            UUID id, UUID sourceId, String title, String state, long version,
            int revisionNo, String verificationState
    ) {}

    private record PackageSource(AnalysisSource source, long durationSeconds) {
    }
}
