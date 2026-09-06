package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class LearnerService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LearnerService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    public List<AssignmentSummary> assignments(CurrentActor learner) {
        return jdbc.query(
                """
                SELECT a.id, a.title, a.available_at, a.due_at, lp.status,
                       lp.progress_percent, lp.best_score_percent
                FROM assignment_recipients ar
                JOIN assignments a ON a.id = ar.assignment_id AND a.organization_id = ar.organization_id
                JOIN learner_progress lp ON lp.assignment_id = ar.assignment_id AND lp.user_id = ar.user_id
                WHERE ar.organization_id = ? AND ar.user_id = ? AND a.state = 'PUBLISHED'
                ORDER BY a.available_at DESC, a.id DESC
                LIMIT 200
                """,
                (result, row) -> new AssignmentSummary(
                        result.getObject("id", UUID.class), result.getString("title"),
                        result.getTimestamp("available_at").toInstant(),
                        result.getTimestamp("due_at") == null ? null : result.getTimestamp("due_at").toInstant(),
                        result.getString("status"), result.getInt("progress_percent"),
                        result.getObject("best_score_percent", Integer.class)
                ),
                learner.organizationId(), learner.userId()
        );
    }

    public AssignmentView get(CurrentActor learner, UUID assignmentId) {
        List<AssignmentView> matches = jdbc.query(
                """
                SELECT a.id, a.title, a.available_at, a.due_at, a.package_revision_id,
                       pr.content_json::text AS content, lp.status, lp.progress_percent,
                       lp.best_score_percent
                FROM assignment_recipients ar
                JOIN assignments a ON a.id = ar.assignment_id AND a.organization_id = ar.organization_id
                JOIN package_revisions pr ON pr.id = a.package_revision_id AND pr.organization_id = a.organization_id
                JOIN learner_progress lp ON lp.assignment_id = ar.assignment_id AND lp.user_id = ar.user_id
                WHERE ar.organization_id = ? AND ar.user_id = ? AND ar.assignment_id = ?
                  AND a.state = 'PUBLISHED' AND a.available_at <= ?
                """,
                (result, row) -> new AssignmentView(
                        result.getObject("id", UUID.class), result.getString("title"),
                        result.getTimestamp("available_at").toInstant(),
                        result.getTimestamp("due_at") == null ? null : result.getTimestamp("due_at").toInstant(),
                        result.getObject("package_revision_id", UUID.class),
                        hideQuizAnswers(objectMapper.readTree(result.getString("content"))),
                        result.getString("status"), result.getInt("progress_percent"),
                        result.getObject("best_score_percent", Integer.class)
                ),
                learner.organizationId(), learner.userId(), assignmentId, Timestamp.from(clock.instant())
        );
        return matches.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found")
        );
    }

    @Transactional
    public AssignmentView start(CurrentActor learner, UUID assignmentId) {
        get(learner, assignmentId);
        Instant now = clock.instant();
        jdbc.update(
                """
                UPDATE learner_progress
                SET status = CASE WHEN status = 'ASSIGNED' THEN 'STARTED' ELSE status END,
                    started_at = COALESCE(started_at, ?), progress_percent = GREATEST(progress_percent, 1),
                    updated_at = ?
                WHERE organization_id = ? AND assignment_id = ? AND user_id = ?
                """,
                Timestamp.from(now), Timestamp.from(now), learner.organizationId(), assignmentId, learner.userId()
        );
        return get(learner, assignmentId);
    }

    @Transactional
    public AttemptResult submit(
            CurrentActor learner,
            UUID assignmentId,
            List<Integer> answers,
            String idempotencyKey,
            String correlationId
    ) {
        AssignmentContent assignment = loadContentForSubmission(learner, assignmentId);
        JsonNode questions = assignment.content().path("quiz");
        if (!questions.isArray() || questions.isEmpty() || answers.size() != questions.size()) {
            throw new IllegalArgumentException("One answer is required for every quiz question");
        }
        String answersJson = objectMapper.writeValueAsString(answers);
        List<AttemptLookup> existing = jdbc.query(
                """
                SELECT id, score_percent, correct_count, question_count, submitted_at, answers_json::text
                FROM quiz_attempts WHERE assignment_id = ? AND user_id = ? AND idempotency_key = ?
                """,
                (result, row) -> new AttemptLookup(
                        new AttemptResult(
                                result.getObject("id", UUID.class), result.getInt("score_percent"),
                                result.getInt("correct_count"), result.getInt("question_count"),
                                result.getTimestamp("submitted_at").toInstant()
                        ),
                        result.getString("answers_json")
                ),
                assignmentId, learner.userId(), idempotencyKey
        );
        if (!existing.isEmpty()) {
            if (!objectMapper.readTree(existing.getFirst().answersJson()).equals(objectMapper.readTree(answersJson))) {
                throw new IdempotencyConflictException();
            }
            return existing.getFirst().result();
        }
        int correct = 0;
        for (int index = 0; index < questions.size(); index++) {
            int answer = answers.get(index);
            if (answer < 0 || answer > 3) {
                throw new IllegalArgumentException("Quiz answers must be between 0 and 3");
            }
            if (answer == questions.get(index).path("correctAnswerIndex").asInt(-1)) {
                correct++;
            }
        }
        int score = (int) Math.round(correct * 100.0 / questions.size());
        UUID attemptId = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO quiz_attempts(
                    id, organization_id, assignment_id, user_id, idempotency_key,
                    answers_json, score_percent, correct_count, question_count, submitted_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                attemptId, learner.organizationId(), assignmentId, learner.userId(), idempotencyKey,
                answersJson, score, correct, questions.size(), Timestamp.from(now)
        );
        jdbc.update(
                """
                UPDATE learner_progress
                SET status = 'COMPLETED', progress_percent = 100,
                    best_score_percent = GREATEST(COALESCE(best_score_percent, 0), ?),
                    started_at = COALESCE(started_at, ?), completed_at = COALESCE(completed_at, ?), updated_at = ?
                WHERE organization_id = ? AND assignment_id = ? AND user_id = ?
                """,
                score, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                learner.organizationId(), assignmentId, learner.userId()
        );
        audit(learner, "ASSESSMENT_SUBMITTED", "QuizAttempt", attemptId, correlationId, now);
        outbox(learner, "AssessmentSubmitted", "QuizAttempt", attemptId, correlationId, now);
        return new AttemptResult(attemptId, score, correct, questions.size(), now);
    }

    @Transactional
    public void feedback(
            CurrentActor learner,
            UUID assignmentId,
            FeedbackKind kind,
            String itemType,
            String itemId,
            String detail,
            String correlationId
    ) {
        AssignmentContent assignment = loadContent(learner, assignmentId, false);
        UUID feedbackId = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO quality_feedback(
                    id, organization_id, package_revision_id, user_id, kind,
                    item_type, item_id, detail, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                feedbackId, learner.organizationId(), assignment.revisionId(), learner.userId(), kind.name(),
                itemType, itemId, detail, Timestamp.from(now)
        );
        audit(learner, "QUALITY_FEEDBACK_CREATED", "QualityFeedback", feedbackId, correlationId, now);
        if (kind == FeedbackKind.REPORT_ERROR) {
            outbox(learner, "QualityIssueReported", "QualityFeedback", feedbackId, correlationId, now);
        }
    }

    private AssignmentContent loadContentForSubmission(CurrentActor learner, UUID assignmentId) {
        AssignmentContent content = loadContent(learner, assignmentId, true);
        if (content.dueAt() != null && !content.dueAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Assignment deadline has passed");
        }
        return content;
    }

    private AssignmentContent loadContent(CurrentActor learner, UUID assignmentId, boolean requireAvailable) {
        String availability = requireAvailable ? " AND a.available_at <= ?" : "";
        List<Object> arguments = new ArrayList<>(List.of(
                learner.organizationId(), learner.userId(), assignmentId
        ));
        if (requireAvailable) {
            arguments.add(Timestamp.from(clock.instant()));
        }
        List<AssignmentContent> matches = jdbc.query(
                """
                SELECT a.package_revision_id, a.due_at, pr.content_json::text AS content
                FROM assignment_recipients ar
                JOIN assignments a ON a.id = ar.assignment_id AND a.organization_id = ar.organization_id
                JOIN package_revisions pr ON pr.id = a.package_revision_id AND pr.organization_id = a.organization_id
                WHERE ar.organization_id = ? AND ar.user_id = ? AND ar.assignment_id = ?
                  AND a.state = 'PUBLISHED'
                """ + availability,
                (result, row) -> new AssignmentContent(
                        result.getObject("package_revision_id", UUID.class),
                        result.getTimestamp("due_at") == null ? null : result.getTimestamp("due_at").toInstant(),
                        objectMapper.readTree(result.getString("content"))
                ),
                arguments.toArray()
        );
        return matches.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found")
        );
    }

    private static JsonNode hideQuizAnswers(JsonNode content) {
        JsonNode copy = content.deepCopy();
        for (JsonNode question : copy.path("quiz")) {
            if (question instanceof ObjectNode object) {
                object.remove("correctAnswerIndex");
                object.remove("explanation");
            }
        }
        return copy;
    }

    private void audit(CurrentActor actor, String action, String type, UUID id, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type, resource_id, correlation_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action, type, id,
                correlationId, Timestamp.from(now)
        );
    }

    private void outbox(CurrentActor actor, String event, String type, UUID id, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, ?, 1, ?, ?, ?, jsonb_build_object('id', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), event, type, id, correlationId, id,
                Timestamp.from(now), Timestamp.from(now)
        );
    }

    public enum FeedbackKind {
        HELPFUL,
        NOT_HELPFUL,
        REPORT_ERROR
    }

    public record AssignmentSummary(
            UUID id, String title, Instant availableAt, Instant dueAt,
            String status, int progressPercent, Integer bestScorePercent
    ) {
    }

    public record AssignmentView(
            UUID id, String title, Instant availableAt, Instant dueAt, UUID packageRevisionId,
            JsonNode content, String status, int progressPercent, Integer bestScorePercent
    ) {
    }

    public record AttemptResult(
            UUID attemptId, int scorePercent, int correctCount, int questionCount, Instant submittedAt
    ) {
    }

    private record AssignmentContent(UUID revisionId, Instant dueAt, JsonNode content) {
    }

    private record AttemptLookup(AttemptResult result, String answersJson) {
    }
}
