package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class LearningPathService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock = Clock.systemUTC();

    public LearningPathService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void configure(
            CurrentActor actor, UUID courseId, int passingScorePercent,
            boolean requireDelayedRecall, String correlationId
    ) {
        requireCourse(actor.organizationId(), courseId);
        if (passingScorePercent < 1 || passingScorePercent > 100) {
            throw new IllegalArgumentException("Passing score must be between 1 and 100");
        }
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO course_completion_rules(
                    organization_id, course_id, passing_score_percent,
                    require_delayed_recall, updated_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (course_id) DO UPDATE SET
                    passing_score_percent = EXCLUDED.passing_score_percent,
                    require_delayed_recall = EXCLUDED.require_delayed_recall,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """,
                actor.organizationId(), courseId, passingScorePercent, requireDelayedRecall,
                actor.userId(), Timestamp.from(now)
        );
        audit(actor, "COURSE_COMPLETION_RULE_CONFIGURED", "Course", courseId, correlationId, now);
    }

    @Transactional
    public void addPrerequisite(
            CurrentActor actor, UUID lessonId, UUID prerequisiteLessonId, String correlationId
    ) {
        if (lessonId.equals(prerequisiteLessonId)) {
            throw new IllegalArgumentException("A lesson cannot require itself");
        }
        Integer sameCourse = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM lessons lesson
                JOIN course_modules lesson_module ON lesson_module.id = lesson.module_id
                JOIN lessons prerequisite ON prerequisite.id = ? AND prerequisite.organization_id = lesson.organization_id
                JOIN course_modules prerequisite_module ON prerequisite_module.id = prerequisite.module_id
                WHERE lesson.organization_id = ? AND lesson.id = ?
                  AND lesson_module.course_id = prerequisite_module.course_id
                """,
                Integer.class, prerequisiteLessonId, actor.organizationId(), lessonId
        );
        if (sameCourse == null || sameCourse != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lessons in the same course are required");
        }
        Integer createsCycle = jdbc.queryForObject(
                """
                WITH RECURSIVE dependencies(lesson_id) AS (
                    SELECT prerequisite_lesson_id FROM lesson_prerequisites
                    WHERE organization_id = ? AND lesson_id = ?
                    UNION
                    SELECT lp.prerequisite_lesson_id FROM lesson_prerequisites lp
                    JOIN dependencies d ON lp.lesson_id = d.lesson_id
                    WHERE lp.organization_id = ?
                )
                SELECT COUNT(*) FROM dependencies WHERE lesson_id = ?
                """,
                Integer.class, actor.organizationId(), prerequisiteLessonId,
                actor.organizationId(), lessonId
        );
        if (createsCycle != null && createsCycle > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Prerequisite would create a cycle");
        }
        jdbc.update(
                """
                INSERT INTO lesson_prerequisites(organization_id, lesson_id, prerequisite_lesson_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """,
                actor.organizationId(), lessonId, prerequisiteLessonId
        );
        audit(actor, "LESSON_PREREQUISITE_ADDED", "Lesson", lessonId, correlationId, clock.instant());
    }

    @Transactional
    public void publish(CurrentActor actor, UUID courseId, String correlationId) {
        requireCourse(actor.organizationId(), courseId);
        Integer invalidLessons = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM lessons l
                JOIN course_modules m ON m.id = l.module_id
                LEFT JOIN learning_packages p ON p.id = l.package_id
                    AND p.organization_id = l.organization_id
                WHERE m.organization_id = ? AND m.course_id = ?
                  AND (p.id IS NULL OR p.publication_state <> 'PUBLISHED')
                """,
                Integer.class, actor.organizationId(), courseId
        );
        Integer lessonCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM lessons l JOIN course_modules m ON m.id = l.module_id
                WHERE m.organization_id = ? AND m.course_id = ?
                """,
                Integer.class, actor.organizationId(), courseId
        );
        if (lessonCount == null || lessonCount == 0 || invalidLessons == null || invalidLessons > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Every course lesson needs a published learning package");
        }
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE courses SET state = 'PUBLISHED', version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND state = 'DRAFT'
                """,
                Timestamp.from(now), actor.organizationId(), courseId
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Course cannot be published");
        }
        jdbc.update(
                """
                INSERT INTO course_completion_rules(
                    organization_id, course_id, passing_score_percent,
                    require_delayed_recall, updated_by, updated_at
                ) VALUES (?, ?, 70, FALSE, ?, ?) ON CONFLICT DO NOTHING
                """,
                actor.organizationId(), courseId, actor.userId(), Timestamp.from(now)
        );
        audit(actor, "COURSE_PUBLISHED", "Course", courseId, correlationId, now);
    }

    public List<LearningPath> paths(CurrentActor learner) {
        List<PathRow> rows = jdbc.query(
                """
                SELECT DISTINCT c.id AS course_id, c.title AS course_title, c.description,
                       a.cohort_id, ch.name AS cohort_name, l.id AS lesson_id,
                       l.title AS lesson_title, m.position AS module_position,
                       l.position AS lesson_position, a.id AS assignment_id,
                       lp.status, lp.best_score_percent,
                       COALESCE(rule.passing_score_percent, 70) AS passing_score,
                       COALESCE(rule.require_delayed_recall, FALSE) AS require_delayed,
                       EXISTS (
                           SELECT 1 FROM assessment_attempts delayed_attempt
                           JOIN assessment_snapshots delayed_snapshot ON delayed_snapshot.id = delayed_attempt.snapshot_id
                           WHERE delayed_attempt.assignment_id = a.id AND delayed_attempt.user_id = ?
                             AND delayed_snapshot.mode = 'DELAYED_RECALL'
                       ) AS delayed_completed,
                       NOT EXISTS (
                           SELECT 1 FROM lesson_prerequisites prereq
                           WHERE prereq.lesson_id = l.id AND NOT EXISTS (
                               SELECT 1 FROM assignments prerequisite_assignment
                               JOIN learner_progress prerequisite_progress
                                 ON prerequisite_progress.assignment_id = prerequisite_assignment.id
                                AND prerequisite_progress.user_id = ?
                               WHERE prerequisite_assignment.cohort_id = a.cohort_id
                                 AND prerequisite_assignment.lesson_id = prereq.prerequisite_lesson_id
                                 AND prerequisite_progress.status = 'COMPLETED'
                                 AND prerequisite_progress.best_score_percent >= COALESCE(rule.passing_score_percent, 70)
                           )
                       ) AS unlocked
                FROM assignment_recipients ar
                JOIN assignments a ON a.id = ar.assignment_id AND a.organization_id = ar.organization_id
                JOIN learner_progress lp ON lp.assignment_id = a.id AND lp.user_id = ar.user_id
                JOIN lessons l ON l.id = a.lesson_id AND l.organization_id = a.organization_id
                JOIN course_modules m ON m.id = l.module_id AND m.organization_id = a.organization_id
                JOIN courses c ON c.id = m.course_id AND c.organization_id = a.organization_id
                JOIN cohorts ch ON ch.id = a.cohort_id AND ch.organization_id = a.organization_id
                LEFT JOIN course_completion_rules rule ON rule.course_id = c.id
                WHERE ar.organization_id = ? AND ar.user_id = ? AND a.state = 'PUBLISHED'
                  AND c.state = 'PUBLISHED'
                ORDER BY c.title, a.cohort_id, m.position, l.position, l.id
                """,
                (result, row) -> new PathRow(
                        result.getObject("course_id", UUID.class), result.getString("course_title"),
                        result.getString("description"), result.getObject("cohort_id", UUID.class),
                        result.getString("cohort_name"), result.getObject("lesson_id", UUID.class),
                        result.getString("lesson_title"), result.getObject("assignment_id", UUID.class),
                        result.getString("status"), result.getObject("best_score_percent", Integer.class),
                        result.getInt("passing_score"), result.getBoolean("require_delayed"),
                        result.getBoolean("delayed_completed"), result.getBoolean("unlocked")
                ),
                learner.userId(), learner.userId(), learner.organizationId(), learner.userId()
        );
        LinkedHashMap<String, PathAccumulator> grouped = new LinkedHashMap<>();
        for (PathRow row : rows) {
            String key = row.courseId() + ":" + row.cohortId();
            grouped.computeIfAbsent(key, ignored -> new PathAccumulator(row)).lessons.add(new PathLesson(
                    row.lessonId(), row.assignmentId(), row.lessonTitle(), row.status(),
                    row.bestScore(), row.delayedCompleted(), row.unlocked()
            ));
        }
        return grouped.values().stream().map(PathAccumulator::view).toList();
    }

    @Transactional
    public Certificate issue(CurrentActor learner, UUID courseId, UUID cohortId, String correlationId) {
        jdbc.queryForObject(
                "SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                String.class, "certificate:" + courseId + ":" + cohortId + ":" + learner.userId()
        );
        List<Certificate> existing = certificate(learner.organizationId(), courseId, cohortId, learner.userId());
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        CompletionStatus status = completionStatus(learner, courseId, cohortId);
        if (!status.eligible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Course completion criteria are not met");
        }
        UUID id = UuidV7Generator.generate();
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        Instant now = clock.instant();
        var criteria = objectMapper.createObjectNode()
                .put("passingScorePercent", status.passingScore())
                .put("requireDelayedRecall", status.requireDelayed())
                .put("completedLessons", status.completedLessons())
                .put("totalLessons", status.totalLessons())
                .put("courseTitle", status.courseTitle())
                .put("cohortName", status.cohortName());
        jdbc.update(
                """
                INSERT INTO completion_certificates(
                    id, organization_id, course_id, cohort_id, user_id,
                    verification_code, criteria_snapshot_json, issued_at
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """,
                id, learner.organizationId(), courseId, cohortId, learner.userId(), code,
                objectMapper.writeValueAsString(criteria), Timestamp.from(now)
        );
        audit(learner, "CERTIFICATE_ISSUED", "CompletionCertificate", id, correlationId, now);
        return certificate(learner.organizationId(), courseId, cohortId, learner.userId()).getFirst();
    }

    public VerifiedCertificate verify(String code) {
        Certificate certificate = jdbc.query(
                """
                SELECT cc.id, cc.course_id, cc.cohort_id, cc.user_id, cc.verification_code,
                       cc.issued_at, cc.revoked_at, c.title AS course_title, ch.name AS cohort_name,
                       o.name AS organization_name, u.display_name
                FROM completion_certificates cc
                JOIN courses c ON c.id = cc.course_id
                JOIN cohorts ch ON ch.id = cc.cohort_id
                JOIN organizations o ON o.id = cc.organization_id
                JOIN users u ON u.id = cc.user_id
                WHERE cc.verification_code = ?
                """,
                (result, row) -> mapCertificate(result), code.trim().toUpperCase()
        ).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found"));
        return new VerifiedCertificate(
                certificate.verificationCode(), certificate.learnerName(), certificate.courseTitle(),
                certificate.cohortName(), certificate.organizationName(), certificate.issuedAt(),
                certificate.revoked()
        );
    }

    @Transactional
    public void revoke(CurrentActor actor, UUID certificateId, String reason, String correlationId) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("A revoke reason is required");
        }
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE completion_certificates SET revoked_at = ?, revoke_reason = ?
                WHERE organization_id = ? AND id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(now), reason.trim(), actor.organizationId(), certificateId
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Certificate cannot be revoked");
        }
        audit(actor, "CERTIFICATE_REVOKED", "CompletionCertificate", certificateId, correlationId, now);
    }

    private CompletionStatus completionStatus(CurrentActor learner, UUID courseId, UUID cohortId) {
        List<CompletionStatus> statuses = jdbc.query(
                """
                SELECT c.title AS course_title, ch.name AS cohort_name,
                       COALESCE(rule.passing_score_percent, 70) AS passing_score,
                       COALESCE(rule.require_delayed_recall, FALSE) AS require_delayed,
                       COUNT(DISTINCT l.id) AS total_lessons,
                       COUNT(DISTINCT l.id) FILTER (WHERE lp.best_score_percent >= COALESCE(rule.passing_score_percent, 70)
                         AND (NOT COALESCE(rule.require_delayed_recall, FALSE) OR EXISTS (
                             SELECT 1 FROM assessment_attempts aa
                             JOIN assessment_snapshots s ON s.id = aa.snapshot_id
                             WHERE aa.assignment_id = a.id AND aa.user_id = ? AND s.mode = 'DELAYED_RECALL'
                         ))) AS completed_lessons
                FROM courses c
                JOIN course_modules m ON m.course_id = c.id
                JOIN lessons l ON l.module_id = m.id
                JOIN cohorts ch ON ch.id = ? AND ch.organization_id = c.organization_id
                LEFT JOIN assignments a ON a.lesson_id = l.id AND a.cohort_id = ch.id AND a.state = 'PUBLISHED'
                LEFT JOIN learner_progress lp ON lp.assignment_id = a.id AND lp.user_id = ?
                LEFT JOIN course_completion_rules rule ON rule.course_id = c.id
                WHERE c.organization_id = ? AND c.id = ? AND c.state = 'PUBLISHED'
                  AND EXISTS (
                      SELECT 1 FROM cohort_members cm
                      WHERE cm.cohort_id = ch.id AND cm.organization_id = c.organization_id AND cm.user_id = ?
                  )
                GROUP BY c.title, ch.name, rule.passing_score_percent, rule.require_delayed_recall
                """,
                (result, row) -> {
                    int total = result.getInt("total_lessons");
                    int completed = result.getInt("completed_lessons");
                    return new CompletionStatus(
                            total > 0 && total == completed, result.getInt("passing_score"),
                            result.getBoolean("require_delayed"), completed, total,
                            result.getString("course_title"), result.getString("cohort_name")
                    );
                },
                learner.userId(), cohortId, learner.userId(), learner.organizationId(), courseId, learner.userId()
        );
        return statuses.stream().findFirst().orElse(new CompletionStatus(false, 70, false, 0, 0, "", ""));
    }

    private List<Certificate> certificate(UUID organizationId, UUID courseId, UUID cohortId, UUID userId) {
        return jdbc.query(
                """
                SELECT cc.id, cc.course_id, cc.cohort_id, cc.user_id, cc.verification_code,
                       cc.issued_at, cc.revoked_at, c.title AS course_title, ch.name AS cohort_name,
                       o.name AS organization_name, u.display_name
                FROM completion_certificates cc
                JOIN courses c ON c.id = cc.course_id
                JOIN cohorts ch ON ch.id = cc.cohort_id
                JOIN organizations o ON o.id = cc.organization_id
                JOIN users u ON u.id = cc.user_id
                WHERE cc.organization_id = ? AND cc.course_id = ? AND cc.cohort_id = ? AND cc.user_id = ?
                """,
                (result, row) -> mapCertificate(result), organizationId, courseId, cohortId, userId
        );
    }

    private static Certificate mapCertificate(java.sql.ResultSet result) throws java.sql.SQLException {
        return new Certificate(
                result.getObject("id", UUID.class), result.getObject("course_id", UUID.class),
                result.getObject("cohort_id", UUID.class), result.getObject("user_id", UUID.class),
                result.getString("verification_code"), result.getString("display_name"),
                result.getString("course_title"), result.getString("cohort_name"),
                result.getString("organization_name"), result.getTimestamp("issued_at").toInstant(),
                result.getTimestamp("revoked_at") != null
        );
    }

    private void requireCourse(UUID organizationId, UUID courseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM courses WHERE organization_id = ? AND id = ?",
                Integer.class, organizationId, courseId
        );
        if (count == null || count != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }
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

    public record PathLesson(
            UUID lessonId, UUID assignmentId, String title, String status,
            Integer bestScorePercent, boolean delayedRecallCompleted, boolean unlocked
    ) { }

    public record LearningPath(
            UUID courseId, String title, String description, UUID cohortId, String cohortName,
            int passingScorePercent, boolean requireDelayedRecall,
            int completedLessons, int totalLessons, boolean certificateEligible,
            List<PathLesson> lessons
    ) { }

    public record Certificate(
            UUID id, UUID courseId, UUID cohortId, UUID learnerId, String verificationCode,
            String learnerName, String courseTitle, String cohortName, String organizationName,
            Instant issuedAt, boolean revoked
    ) { }

    public record VerifiedCertificate(
            String verificationCode, String learnerName, String courseTitle,
            String cohortName, String organizationName, Instant issuedAt, boolean revoked
    ) { }

    private record PathRow(
            UUID courseId, String courseTitle, String description, UUID cohortId, String cohortName,
            UUID lessonId, String lessonTitle, UUID assignmentId, String status,
            Integer bestScore, int passingScore, boolean requireDelayed,
            boolean delayedCompleted, boolean unlocked
    ) { }

    private record CompletionStatus(
            boolean eligible, int passingScore, boolean requireDelayed,
            int completedLessons, int totalLessons, String courseTitle, String cohortName
    ) { }

    private static final class PathAccumulator {
        private final PathRow root;
        private final List<PathLesson> lessons = new ArrayList<>();
        private PathAccumulator(PathRow root) { this.root = root; }
        private LearningPath view() {
            int completed = (int) lessons.stream().filter(lesson ->
                    lesson.bestScorePercent() != null && lesson.bestScorePercent() >= root.passingScore()
                            && (!root.requireDelayed() || lesson.delayedRecallCompleted())
            ).count();
            return new LearningPath(
                    root.courseId(), root.courseTitle(), root.description(), root.cohortId(), root.cohortName(),
                    root.passingScore(), root.requireDelayed(), completed, lessons.size(),
                    completed == lessons.size() && !lessons.isEmpty(), List.copyOf(lessons)
            );
        }
    }
}
