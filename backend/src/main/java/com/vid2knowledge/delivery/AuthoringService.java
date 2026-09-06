package com.vid2knowledge.delivery;

import com.vid2knowledge.analysis.application.AnalysisOutputProfile;
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
public class AuthoringService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuthoringService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = Clock.systemUTC();
    }

    public String resolveProfile(UUID organizationId, UUID templateId, JsonNode inlineProfile) {
        if ((templateId == null) == (inlineProfile == null)) {
            throw new IllegalArgumentException("Choose exactly one of templateId or outputProfile");
        }
        if (inlineProfile != null) {
            return normalize(inlineProfile.toString());
        }
        return jdbc.query(
                """
                SELECT output_profile_json::text FROM content_templates
                WHERE organization_id = ? AND id = ? AND state = 'ACTIVE'
                """,
                (result, row) -> normalize(result.getString(1)), organizationId, templateId
        ).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Active content template not found")
        );
    }

    public List<TemplateView> templates(UUID organizationId) {
        return jdbc.query(
                """
                SELECT id, name, output_profile_json::text, state, version, created_at, updated_at
                FROM content_templates WHERE organization_id = ?
                ORDER BY state, lower(name), id LIMIT 1000
                """,
                (result, row) -> new TemplateView(
                        result.getObject("id", UUID.class), result.getString("name"),
                        mapper.readTree(result.getString("output_profile_json")), result.getString("state"),
                        result.getLong("version"), result.getTimestamp("created_at").toInstant(),
                        result.getTimestamp("updated_at").toInstant()
                ), organizationId
        );
    }

    @Transactional
    public TemplateView createTemplate(CurrentActor actor, String name, JsonNode profile, String correlationId) {
        validateNameAvailable(actor.organizationId(), null, name);
        UUID id = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO content_templates(
                    id, organization_id, name, output_profile_json, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """,
                id, actor.organizationId(), cleanName(name), normalize(profile.toString()), actor.userId(),
                Timestamp.from(now), Timestamp.from(now)
        );
        audit(actor, "CONTENT_TEMPLATE_CREATED", "ContentTemplate", id, correlationId, now);
        return template(actor.organizationId(), id);
    }

    @Transactional
    public TemplateView updateTemplate(
            CurrentActor actor, UUID templateId, long expectedVersion, String name,
            JsonNode profile, String correlationId
    ) {
        validateNameAvailable(actor.organizationId(), templateId, name);
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE content_templates SET name = ?, output_profile_json = CAST(? AS jsonb),
                    version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND state = 'ACTIVE' AND version = ?
                """,
                cleanName(name), normalize(profile.toString()), Timestamp.from(now), actor.organizationId(),
                templateId, expectedVersion
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Template version has changed");
        }
        audit(actor, "CONTENT_TEMPLATE_UPDATED", "ContentTemplate", templateId, correlationId, now);
        return template(actor.organizationId(), templateId);
    }

    @Transactional
    public void archiveTemplate(CurrentActor actor, UUID templateId, String correlationId) {
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE content_templates SET state = 'ARCHIVED', version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND state = 'ACTIVE'
                """,
                Timestamp.from(now), actor.organizationId(), templateId
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active content template not found");
        }
        audit(actor, "CONTENT_TEMPLATE_ARCHIVED", "ContentTemplate", templateId, correlationId, now);
    }

    public List<ReviewQueueItem> reviewQueue(UUID organizationId) {
        return jdbc.query(
                """
                SELECT p.id, r.id AS revision_id,
                       COALESCE(r.content_json #>> '{video,title}', 'Untitled') AS title,
                       r.revision_no, p.updated_at,
                       (SELECT count(*) FROM quality_feedback q
                        WHERE q.organization_id = p.organization_id
                          AND q.package_revision_id = r.id AND q.status = 'OPEN') AS open_feedback
                FROM learning_packages p
                JOIN package_revisions r ON r.id = p.current_revision_id AND r.organization_id = p.organization_id
                WHERE p.organization_id = ? AND p.publication_state = 'IN_REVIEW'
                ORDER BY p.updated_at, p.id LIMIT 1000
                """,
                (result, row) -> new ReviewQueueItem(
                        result.getObject("id", UUID.class), result.getObject("revision_id", UUID.class),
                        result.getString("title"), result.getInt("revision_no"),
                        result.getLong("open_feedback"), result.getTimestamp("updated_at").toInstant()
                ), organizationId
        );
    }

    public List<QuestionBankItem> questionBank(UUID organizationId) {
        return jdbc.query(
                """
                SELECT id, package_revision_id, source_item_id, question, options_json::text,
                       correct_answer_index, explanation, source_timestamp_seconds,
                       tags, difficulty, validation_state, usage_count, created_at
                FROM question_bank_items WHERE organization_id = ?
                ORDER BY validation_state, created_at DESC, id DESC LIMIT 2000
                """,
                (result, row) -> new QuestionBankItem(
                        result.getObject("id", UUID.class), result.getObject("package_revision_id", UUID.class),
                        result.getString("source_item_id"), result.getString("question"),
                        mapper.readTree(result.getString("options_json")), result.getInt("correct_answer_index"),
                        result.getString("explanation"), result.getLong("source_timestamp_seconds"),
                        List.of((String[]) result.getArray("tags").getArray()), result.getString("difficulty"),
                        result.getString("validation_state"), result.getLong("usage_count"),
                        result.getTimestamp("created_at").toInstant()
                ), organizationId
        );
    }

    public Settings settings(UUID organizationId) {
        return jdbc.query(
                "SELECT approval_required FROM organizations WHERE id = ?",
                (result, row) -> new Settings(result.getBoolean(1)), organizationId
        ).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found")
        );
    }

    @Transactional
    public Settings updateSettings(CurrentActor actor, boolean approvalRequired, String correlationId) {
        jdbc.update("UPDATE organizations SET approval_required = ? WHERE id = ?", approvalRequired, actor.organizationId());
        audit(actor, "AUTHORING_SETTINGS_UPDATED", "Organization", actor.organizationId(), correlationId, clock.instant());
        return new Settings(approvalRequired);
    }

    @Transactional
    public void resolveFeedback(
            CurrentActor actor, UUID feedbackId, Resolution resolution, String note, String correlationId
    ) {
        if (note == null || note.trim().length() < 3 || note.trim().length() > 1000) {
            throw new IllegalArgumentException("A resolution note between 3 and 1000 characters is required");
        }
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE quality_feedback SET status = ?, resolved_by = ?, resolution_note = ?,
                    resolved_at = ?, updated_at = ?
                WHERE organization_id = ? AND id = ? AND status = 'OPEN'
                """,
                resolution.name(), actor.userId(), note.trim(), Timestamp.from(now), Timestamp.from(now),
                actor.organizationId(), feedbackId
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Open feedback not found");
        }
        audit(actor, "QUALITY_FEEDBACK_" + resolution.name(), "QualityFeedback", feedbackId, correlationId, now);
    }

    private String normalize(String json) {
        return AnalysisOutputProfile.parse(json, mapper).normalizedJson(mapper);
    }

    private TemplateView template(UUID organizationId, UUID id) {
        return templates(organizationId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private void validateNameAvailable(UUID organizationId, UUID excludedId, String name) {
        String cleaned = cleanName(name);
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM content_templates
                    WHERE organization_id = ? AND lower(name) = lower(?) AND state = 'ACTIVE'
                      AND (?::uuid IS NULL OR id <> ?::uuid))
                """,
                Boolean.class, organizationId, cleaned, excludedId, excludedId
        ));
        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An active template with this name already exists");
        }
    }

    private static String cleanName(String name) {
        if (name == null || name.trim().length() < 2 || name.trim().length() > 120) {
            throw new IllegalArgumentException("Template name must be between 2 and 120 characters");
        }
        return name.trim();
    }

    private void audit(CurrentActor actor, String action, String type, UUID id, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(id, organization_id, actor_user_id, action, resource_type,
                                       resource_id, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action, type, id,
                correlationId, Timestamp.from(now)
        );
    }

    public enum Resolution { RESOLVED, DISMISSED }
    public record Settings(boolean approvalRequired) {}
    public record TemplateView(
            UUID id, String name, JsonNode outputProfile, String state, long version,
            Instant createdAt, Instant updatedAt
    ) {}
    public record ReviewQueueItem(
            UUID packageId, UUID revisionId, String title, int revisionNo,
            long openFeedback, Instant submittedAt
    ) {}
    public record QuestionBankItem(
            UUID id, UUID packageRevisionId, String sourceItemId, String question, JsonNode options,
            int correctAnswerIndex, String explanation, long sourceTimestampSeconds, List<String> tags,
            String difficulty, String validationState, long usageCount, Instant createdAt
    ) {}
}
