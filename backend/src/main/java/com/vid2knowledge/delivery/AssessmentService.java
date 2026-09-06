package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class AssessmentService {

    private static final Duration ATTEMPT_WINDOW = Duration.ofHours(2);
    private static final Duration DELAYED_RECALL_DELAY = Duration.ofDays(3);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public AssessmentService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, Clock.systemUTC(), new SecureRandom());
    }

    AssessmentService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock, SecureRandom random) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.random = random;
    }

    @Transactional
    public AssessmentSnapshot start(
            CurrentActor learner, UUID assignmentId, Mode mode, String idempotencyKey, String correlationId
    ) {
        lock("assessment-start:" + assignmentId + ":" + learner.userId() + ":" + idempotencyKey);
        List<SnapshotLookup> existing = snapshots(learner, assignmentId, idempotencyKey);
        if (!existing.isEmpty()) {
            if (existing.getFirst().mode() != mode) {
                throw new IdempotencyConflictException();
            }
            return existing.getFirst().snapshot();
        }

        AssignmentContent assignment = assignment(learner, assignmentId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (mode == Mode.PRACTICE && assignment.dueAt() != null && !assignment.dueAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Assignment deadline has passed");
        }
        if (mode == Mode.DELAYED_RECALL) {
            Instant availableAt = delayedAvailableAt(learner, assignmentId);
            if (availableAt == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Complete a practice assessment first");
            }
            if (now.isBefore(availableAt)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Delayed recall becomes available at " + availableAt);
            }
        }

        JsonNode quiz = assignment.content().path("quiz");
        if (!quiz.isArray() || quiz.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This lesson has no approved questions");
        }
        SnapshotPayload payload = randomized(assignment.content());
        UUID snapshotId = UuidV7Generator.generate();
        Instant expiresAt = now.plus(ATTEMPT_WINDOW);
        jdbc.update(
                """
                INSERT INTO assessment_snapshots(
                    id, organization_id, assignment_id, package_revision_id, user_id, mode,
                    start_idempotency_key, questions_json, answer_key_json, question_count,
                    created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                """,
                snapshotId, learner.organizationId(), assignmentId, assignment.revisionId(), learner.userId(),
                mode.name(), idempotencyKey, objectMapper.writeValueAsString(payload.questions()),
                objectMapper.writeValueAsString(payload.answerKey()), payload.questions().size(),
                Timestamp.from(now), Timestamp.from(expiresAt)
        );
        jdbc.update(
                """
                UPDATE learner_progress
                SET status = CASE WHEN status = 'ASSIGNED' THEN 'STARTED' ELSE status END,
                    progress_percent = GREATEST(progress_percent, 1),
                    started_at = COALESCE(started_at, ?), updated_at = ?
                WHERE organization_id = ? AND assignment_id = ? AND user_id = ?
                """,
                Timestamp.from(now), Timestamp.from(now), learner.organizationId(), assignmentId, learner.userId()
        );
        audit(learner, "ASSESSMENT_STARTED", "AssessmentSnapshot", snapshotId, correlationId, now);
        return new AssessmentSnapshot(snapshotId, assignmentId, mode, payload.questions(), now, expiresAt);
    }

    @Transactional
    public AssessmentResult submit(
            CurrentActor learner, UUID snapshotId, List<Integer> answers,
            String idempotencyKey, String correlationId
    ) {
        lock("assessment-submit:" + snapshotId);
        SnapshotData snapshot = snapshot(learner, snapshotId);
        lock("assessment-submit-key:" + snapshot.assignmentId() + ":" + learner.userId() + ":" + idempotencyKey);
        List<UUID> reusedKeys = jdbc.query(
                """
                SELECT snapshot_id FROM assessment_attempts
                WHERE organization_id = ? AND assignment_id = ? AND user_id = ?
                  AND submission_idempotency_key = ?
                """,
                (result, row) -> result.getObject("snapshot_id", UUID.class),
                learner.organizationId(), snapshot.assignmentId(), learner.userId(), idempotencyKey
        );
        if (!reusedKeys.isEmpty() && !reusedKeys.getFirst().equals(snapshotId)) {
            throw new IdempotencyConflictException();
        }
        String answersJson = objectMapper.writeValueAsString(answers);
        List<AttemptLookup> existing = attempts(learner, snapshotId);
        if (!existing.isEmpty()) {
            if (!objectMapper.readTree(existing.getFirst().answersJson()).equals(objectMapper.readTree(answersJson))
                    || !existing.getFirst().idempotencyKey().equals(idempotencyKey)) {
                throw new IdempotencyConflictException();
            }
            return result(existing.getFirst().attemptId(), snapshot, answers, existing.getFirst().submittedAt());
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!snapshot.expiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Assessment attempt has expired");
        }
        if (answers.size() != snapshot.answerKey().size()) {
            throw new IllegalArgumentException("One answer is required for every assessment question");
        }
        for (int answer : answers) {
            if (answer < 0 || answer > 3) {
                throw new IllegalArgumentException("Assessment answers must be between 0 and 3");
            }
        }

        int correct = 0;
        for (int index = 0; index < answers.size(); index++) {
            if (answers.get(index) == snapshot.answerKey().get(index).path("correctAnswerIndex").asInt()) {
                correct++;
            }
        }
        int score = (int) Math.round(correct * 100.0 / answers.size());
        UUID attemptId = UuidV7Generator.generate();
        jdbc.update(
                """
                INSERT INTO assessment_attempts(
                    id, organization_id, snapshot_id, assignment_id, user_id,
                    submission_idempotency_key, answers_json, score_percent,
                    correct_count, question_count, submitted_at
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """,
                attemptId, learner.organizationId(), snapshotId, snapshot.assignmentId(), learner.userId(),
                idempotencyKey, answersJson, score, correct, answers.size(), Timestamp.from(now)
        );
        for (int index = 0; index < answers.size(); index++) {
            JsonNode key = snapshot.answerKey().get(index);
            boolean isCorrect = answers.get(index) == key.path("correctAnswerIndex").asInt();
            jdbc.update(
                    """
                    INSERT INTO assessment_attempt_answers(
                        attempt_id, position, question_id, question_text, selected_answer_index,
                        correct_answer_index, is_correct, source_timestamp_seconds
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    attemptId, index, key.path("id").asText(), key.path("question").asText(), answers.get(index),
                    key.path("correctAnswerIndex").asInt(), isCorrect,
                    key.path("source").path("timestampSeconds").asLong(0)
            );
        }
        jdbc.update(
                """
                UPDATE learner_progress
                SET status = 'COMPLETED', progress_percent = 100,
                    best_score_percent = GREATEST(COALESCE(best_score_percent, 0), ?),
                    completed_at = COALESCE(completed_at, ?), updated_at = ?
                WHERE organization_id = ? AND assignment_id = ? AND user_id = ?
                """,
                score, Timestamp.from(now), Timestamp.from(now), learner.organizationId(),
                snapshot.assignmentId(), learner.userId()
        );
        String event = snapshot.mode() == Mode.DELAYED_RECALL
                ? "DelayedRecallCompleted" : "AssessmentSubmitted";
        String action = snapshot.mode() == Mode.DELAYED_RECALL
                ? "DELAYED_RECALL_COMPLETED" : "ASSESSMENT_SUBMITTED";
        audit(learner, action, "AssessmentAttempt", attemptId, correlationId, now);
        outbox(learner, event, "AssessmentAttempt", attemptId, correlationId, now);
        return result(attemptId, snapshot, answers, now);
    }

    public AssessmentOverview overview(CurrentActor learner, UUID assignmentId) {
        assignment(learner, assignmentId);
        int practiceAttempts = countAttempts(learner, assignmentId, Mode.PRACTICE);
        int delayedAttempts = countAttempts(learner, assignmentId, Mode.DELAYED_RECALL);
        Integer bestScore = jdbc.queryForObject(
                """
                SELECT MAX(aa.score_percent) FROM assessment_attempts aa
                JOIN assessment_snapshots s ON s.id = aa.snapshot_id
                WHERE aa.organization_id = ? AND aa.assignment_id = ? AND aa.user_id = ?
                """,
                Integer.class, learner.organizationId(), assignmentId, learner.userId()
        );
        Instant delayedAt = delayedAvailableAt(learner, assignmentId);
        List<WeakArea> weakAreas = jdbc.query(
                """
                SELECT aaa.question_id, MAX(aaa.question_text) AS question_text,
                       COUNT(*) AS wrong_count, COUNT(DISTINCT aaa.attempt_id) AS affected_attempts
                FROM assessment_attempt_answers aaa
                JOIN assessment_attempts aa ON aa.id = aaa.attempt_id
                WHERE aa.organization_id = ? AND aa.assignment_id = ? AND aa.user_id = ?
                  AND aaa.is_correct = FALSE
                GROUP BY aaa.question_id
                ORDER BY wrong_count DESC, aaa.question_id
                LIMIT 5
                """,
                (result, row) -> new WeakArea(
                        result.getString("question_id"), result.getString("question_text"),
                        result.getInt("wrong_count"), result.getInt("affected_attempts")
                ),
                learner.organizationId(), assignmentId, learner.userId()
        );
        return new AssessmentOverview(
                practiceAttempts, delayedAttempts, bestScore, delayedAt,
                delayedAt != null && !clock.instant().isBefore(delayedAt), delayedAttempts > 0, weakAreas
        );
    }

    private SnapshotPayload randomized(JsonNode content) {
        List<JsonNode> questions = new ArrayList<>();
        content.path("quiz").forEach(questions::add);
        Collections.shuffle(questions, random);
        ArrayNode publicQuestions = objectMapper.createArrayNode();
        ArrayNode answerKey = objectMapper.createArrayNode();
        String youtubeUrl = content.path("video").path("youtubeUrl").asText("");
        for (int position = 0; position < questions.size(); position++) {
            JsonNode original = questions.get(position);
            List<Integer> optionOrder = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(optionOrder, random);
            ArrayNode options = objectMapper.createArrayNode();
            int randomizedCorrectIndex = -1;
            for (int newIndex = 0; newIndex < optionOrder.size(); newIndex++) {
                int oldIndex = optionOrder.get(newIndex);
                options.add(original.path("options").get(oldIndex).asText());
                if (oldIndex == original.path("correctAnswerIndex").asInt()) {
                    randomizedCorrectIndex = newIndex;
                }
            }
            String id = original.path("id").asText("question-" + (position + 1));
            ObjectNode question = objectMapper.createObjectNode();
            question.put("id", id);
            question.put("question", original.path("question").asText());
            question.set("options", options);
            publicQuestions.add(question);

            ObjectNode key = (ObjectNode) original.deepCopy();
            key.put("id", id);
            key.put("correctAnswerIndex", randomizedCorrectIndex);
            key.put("youtubeUrl", youtubeUrl);
            answerKey.add(key);
        }
        return new SnapshotPayload(publicQuestions, answerKey);
    }

    private AssessmentResult result(UUID attemptId, SnapshotData snapshot, List<Integer> answers, Instant submittedAt) {
        List<QuestionResult> results = new ArrayList<>();
        int correct = 0;
        for (int index = 0; index < answers.size(); index++) {
            JsonNode question = snapshot.questions().get(index);
            JsonNode key = snapshot.answerKey().get(index);
            int correctIndex = key.path("correctAnswerIndex").asInt();
            boolean isCorrect = answers.get(index) == correctIndex;
            if (isCorrect) {
                correct++;
            }
            results.add(new QuestionResult(
                    key.path("id").asText(), question.path("question").asText(), answers.get(index), correctIndex,
                    isCorrect, key.path("explanation").asText(), key.path("youtubeUrl").asText(),
                    key.path("source").path("timestampSeconds").asLong(0),
                    key.path("source").path("evidence").asText()
            ));
        }
        return new AssessmentResult(
                attemptId, snapshot.id(), snapshot.mode(), (int) Math.round(correct * 100.0 / answers.size()),
                correct, answers.size(), submittedAt, results
        );
    }

    private List<SnapshotLookup> snapshots(CurrentActor learner, UUID assignmentId, String key) {
        return jdbc.query(
                """
                SELECT id, mode, questions_json::text, created_at, expires_at
                FROM assessment_snapshots
                WHERE organization_id = ? AND assignment_id = ? AND user_id = ? AND start_idempotency_key = ?
                """,
                (result, row) -> {
                    Mode mode = Mode.valueOf(result.getString("mode"));
                    return new SnapshotLookup(mode, new AssessmentSnapshot(
                            result.getObject("id", UUID.class), assignmentId, mode,
                            objectMapper.readTree(result.getString("questions_json")),
                            result.getTimestamp("created_at").toInstant(), result.getTimestamp("expires_at").toInstant()
                    ));
                },
                learner.organizationId(), assignmentId, learner.userId(), key
        );
    }

    private SnapshotData snapshot(CurrentActor learner, UUID snapshotId) {
        List<SnapshotData> matches = jdbc.query(
                """
                SELECT s.id, s.assignment_id, s.mode, s.questions_json::text, s.answer_key_json::text, s.expires_at
                FROM assessment_snapshots s
                JOIN assignment_recipients ar ON ar.assignment_id = s.assignment_id AND ar.user_id = s.user_id
                WHERE s.organization_id = ? AND s.user_id = ? AND s.id = ?
                """,
                (result, row) -> new SnapshotData(
                        result.getObject("id", UUID.class), result.getObject("assignment_id", UUID.class),
                        Mode.valueOf(result.getString("mode")),
                        objectMapper.readTree(result.getString("questions_json")),
                        objectMapper.readTree(result.getString("answer_key_json")),
                        result.getTimestamp("expires_at").toInstant()
                ),
                learner.organizationId(), learner.userId(), snapshotId
        );
        return matches.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment not found"));
    }

    private List<AttemptLookup> attempts(CurrentActor learner, UUID snapshotId) {
        return jdbc.query(
                """
                SELECT id, submission_idempotency_key, answers_json::text, submitted_at
                FROM assessment_attempts
                WHERE organization_id = ? AND user_id = ? AND snapshot_id = ?
                """,
                (result, row) -> new AttemptLookup(
                        result.getObject("id", UUID.class), result.getString("submission_idempotency_key"),
                        result.getString("answers_json"), result.getTimestamp("submitted_at").toInstant()
                ),
                learner.organizationId(), learner.userId(), snapshotId
        );
    }

    private AssignmentContent assignment(CurrentActor learner, UUID assignmentId) {
        List<AssignmentContent> matches = jdbc.query(
                """
                SELECT a.package_revision_id, a.due_at, pr.content_json::text AS content
                FROM assignment_recipients ar
                JOIN assignments a ON a.id = ar.assignment_id AND a.organization_id = ar.organization_id
                JOIN package_revisions pr ON pr.id = a.package_revision_id AND pr.organization_id = a.organization_id
                WHERE ar.organization_id = ? AND ar.user_id = ? AND ar.assignment_id = ?
                  AND a.state = 'PUBLISHED' AND a.available_at <= ?
                """,
                (result, row) -> new AssignmentContent(
                        result.getObject("package_revision_id", UUID.class),
                        result.getTimestamp("due_at") == null ? null : result.getTimestamp("due_at").toInstant(),
                        objectMapper.readTree(result.getString("content"))
                ),
                learner.organizationId(), learner.userId(), assignmentId, Timestamp.from(clock.instant())
        );
        return matches.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found"));
    }

    private Instant delayedAvailableAt(CurrentActor learner, UUID assignmentId) {
        Timestamp baseline = jdbc.queryForObject(
                """
                SELECT MIN(submitted_at) FROM (
                    SELECT submitted_at FROM quiz_attempts
                    WHERE organization_id = ? AND assignment_id = ? AND user_id = ?
                    UNION ALL
                    SELECT aa.submitted_at FROM assessment_attempts aa
                    JOIN assessment_snapshots s ON s.id = aa.snapshot_id
                    WHERE aa.organization_id = ? AND aa.assignment_id = ? AND aa.user_id = ?
                      AND s.mode = 'PRACTICE'
                ) completed_practice
                """,
                Timestamp.class,
                learner.organizationId(), assignmentId, learner.userId(),
                learner.organizationId(), assignmentId, learner.userId()
        );
        return baseline == null ? null : baseline.toInstant().plus(DELAYED_RECALL_DELAY);
    }

    private int countAttempts(CurrentActor learner, UUID assignmentId, Mode mode) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM assessment_attempts aa
                JOIN assessment_snapshots s ON s.id = aa.snapshot_id
                WHERE aa.organization_id = ? AND aa.assignment_id = ? AND aa.user_id = ? AND s.mode = ?
                """,
                Integer.class, learner.organizationId(), assignmentId, learner.userId(), mode.name()
        );
        return count == null ? 0 : count;
    }

    private void lock(String key) {
        jdbc.queryForObject(
                "SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                String.class, key
        );
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

    public enum Mode { PRACTICE, DELAYED_RECALL }

    public record AssessmentSnapshot(
            UUID snapshotId, UUID assignmentId, Mode mode, JsonNode questions,
            Instant startedAt, Instant expiresAt
    ) { }

    public record QuestionResult(
            String questionId, String question, int selectedAnswerIndex, int correctAnswerIndex,
            boolean correct, String explanation, String youtubeUrl,
            long timestampSeconds, String evidence
    ) { }

    public record AssessmentResult(
            UUID attemptId, UUID snapshotId, Mode mode, int scorePercent,
            int correctCount, int questionCount, Instant submittedAt,
            List<QuestionResult> questions
    ) { }

    public record WeakArea(String questionId, String question, int wrongCount, int affectedAttempts) { }

    public record AssessmentOverview(
            int practiceAttempts, int delayedRecallAttempts, Integer bestScorePercent,
            Instant delayedRecallAvailableAt, boolean delayedRecallAvailable,
            boolean delayedRecallCompleted, List<WeakArea> weakAreas
    ) { }

    private record SnapshotPayload(ArrayNode questions, ArrayNode answerKey) { }
    private record SnapshotLookup(Mode mode, AssessmentSnapshot snapshot) { }
    private record SnapshotData(
            UUID id, UUID assignmentId, Mode mode, JsonNode questions, JsonNode answerKey, Instant expiresAt
    ) { }
    private record AttemptLookup(UUID attemptId, String idempotencyKey, String answersJson, Instant submittedAt) { }
    private record AssignmentContent(UUID revisionId, Instant dueAt, JsonNode content) { }
}
