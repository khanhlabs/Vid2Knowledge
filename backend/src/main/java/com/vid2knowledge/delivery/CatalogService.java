package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public CatalogService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    CatalogService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Course createCourse(CurrentActor actor, String title, String description, String correlationId) {
        UUID id = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO courses(id, organization_id, title, description, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), title.trim(), description == null ? "" : description.trim(),
                actor.userId(), Timestamp.from(now), Timestamp.from(now)
        );
        audit(actor, "COURSE_CREATED", "Course", id, correlationId, now);
        return new Course(id, title.trim(), description == null ? "" : description.trim(), "DRAFT", 0);
    }

    @Transactional
    public Module addModule(CurrentActor actor, UUID courseId, String title, int position, String correlationId) {
        requireExists("courses", actor.organizationId(), courseId);
        UUID id = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO course_modules(id, organization_id, course_id, title, position, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), courseId, title.trim(), position,
                Timestamp.from(now), Timestamp.from(now)
        );
        audit(actor, "COURSE_MODULE_CREATED", "CourseModule", id, correlationId, now);
        return new Module(id, courseId, title.trim(), position);
    }

    @Transactional
    public Lesson addLesson(
            CurrentActor actor,
            UUID moduleId,
            UUID packageId,
            String title,
            int position,
            String correlationId
    ) {
        requireExists("course_modules", actor.organizationId(), moduleId);
        if (packageId != null) {
            requireExists("learning_packages", actor.organizationId(), packageId);
        }
        UUID id = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO lessons(id, organization_id, module_id, package_id, title, position, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), moduleId, packageId, title.trim(), position,
                Timestamp.from(now), Timestamp.from(now)
        );
        audit(actor, "LESSON_CREATED", "Lesson", id, correlationId, now);
        return new Lesson(id, moduleId, packageId, title.trim(), position);
    }

    @Transactional
    public Cohort createCohort(
            CurrentActor actor,
            String name,
            Instant startsAt,
            Instant endsAt,
            String correlationId
    ) {
        UUID id = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO cohorts(
                    id, organization_id, name, status, starts_at, ends_at, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), name.trim(), timestamp(startsAt), timestamp(endsAt), actor.userId(),
                Timestamp.from(now), Timestamp.from(now)
        );
        audit(actor, "COHORT_CREATED", "Cohort", id, correlationId, now);
        return new Cohort(id, name.trim(), "ACTIVE", startsAt, endsAt);
    }

    @Transactional
    public void addCohortMember(CurrentActor actor, UUID cohortId, UUID learnerId, String correlationId) {
        requireExists("cohorts", actor.organizationId(), cohortId);
        Integer eligible = jdbc.queryForObject(
                """
                SELECT count(*) FROM memberships
                WHERE organization_id = ? AND user_id = ? AND role = 'LEARNER' AND status = 'ACTIVE'
                """,
                Integer.class,
                actor.organizationId(), learnerId
        );
        if (eligible == null || eligible != 1) {
            throw new ResponseStatusException(NOT_FOUND, "Learner not found");
        }
        jdbc.update(
                """
                INSERT INTO cohort_members(organization_id, cohort_id, user_id)
                VALUES (?, ?, ?) ON CONFLICT (cohort_id, user_id) DO NOTHING
                """,
                actor.organizationId(), cohortId, learnerId
        );
        audit(actor, "COHORT_MEMBER_ADDED", "Cohort", cohortId, correlationId, clock.instant());
    }

    @Transactional
    public Assignment createAssignment(
            CurrentActor actor,
            UUID cohortId,
            UUID lessonId,
            String title,
            Instant availableAt,
            Instant dueAt,
            String correlationId
    ) {
        requireExists("cohorts", actor.organizationId(), cohortId);
        var revisions = jdbc.query(
                """
                SELECT p.current_revision_id
                FROM lessons l
                JOIN learning_packages p ON p.id = l.package_id AND p.organization_id = l.organization_id
                WHERE l.id = ? AND l.organization_id = ? AND p.publication_state = 'PUBLISHED'
                  AND p.current_revision_id IS NOT NULL
                """,
                (result, row) -> result.getObject("current_revision_id", UUID.class),
                lessonId, actor.organizationId()
        );
        UUID revisionId = revisions.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(CONFLICT, "Lesson requires a published learning package")
        );
        UUID id = UuidV7Generator.generate();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO assignments(
                    id, organization_id, cohort_id, lesson_id, package_revision_id, title,
                    available_at, due_at, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), cohortId, lessonId, revisionId, title.trim(),
                Timestamp.from(availableAt), timestamp(dueAt), actor.userId(), Timestamp.from(now), Timestamp.from(now)
        );
        audit(actor, "ASSIGNMENT_CREATED", "Assignment", id, correlationId, now);
        return new Assignment(id, cohortId, lessonId, revisionId, title.trim(), "DRAFT", availableAt, dueAt);
    }

    @Transactional
    public Assignment publishAssignment(CurrentActor actor, UUID assignmentId, String correlationId) {
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE assignments SET state = 'PUBLISHED', published_at = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND organization_id = ? AND state = 'DRAFT'
                """,
                Timestamp.from(now), Timestamp.from(now), assignmentId, actor.organizationId()
        );
        if (updated != 1) {
            throw new ResponseStatusException(CONFLICT, "Assignment cannot be published from its current state");
        }
        jdbc.update(
                """
                INSERT INTO assignment_recipients(organization_id, assignment_id, user_id, assigned_at)
                SELECT a.organization_id, a.id, cm.user_id, ?
                FROM assignments a
                JOIN cohort_members cm ON cm.cohort_id = a.cohort_id AND cm.organization_id = a.organization_id
                WHERE a.id = ? AND a.organization_id = ?
                ON CONFLICT (assignment_id, user_id) DO NOTHING
                """,
                Timestamp.from(now), assignmentId, actor.organizationId()
        );
        jdbc.update(
                """
                INSERT INTO learner_progress(organization_id, assignment_id, user_id, updated_at)
                SELECT organization_id, assignment_id, user_id, ? FROM assignment_recipients
                WHERE assignment_id = ? AND organization_id = ?
                ON CONFLICT (assignment_id, user_id) DO NOTHING
                """,
                Timestamp.from(now), assignmentId, actor.organizationId()
        );
        audit(actor, "ASSIGNMENT_PUBLISHED", "Assignment", assignmentId, correlationId, now);
        outbox(actor, "AssignmentPublished", "Assignment", assignmentId, correlationId, now);
        return findAssignment(actor.organizationId(), assignmentId);
    }

    private Assignment findAssignment(UUID organizationId, UUID assignmentId) {
        return jdbc.queryForObject(
                """
                SELECT id, cohort_id, lesson_id, package_revision_id, title, state, available_at, due_at
                FROM assignments WHERE organization_id = ? AND id = ?
                """,
                (result, row) -> new Assignment(
                        result.getObject("id", UUID.class), result.getObject("cohort_id", UUID.class),
                        result.getObject("lesson_id", UUID.class), result.getObject("package_revision_id", UUID.class),
                        result.getString("title"), result.getString("state"),
                        result.getTimestamp("available_at").toInstant(),
                        result.getTimestamp("due_at") == null ? null : result.getTimestamp("due_at").toInstant()
                ),
                organizationId, assignmentId
        );
    }

    private void requireExists(String table, UUID organizationId, UUID id) {
        if (!java.util.Set.of("courses", "course_modules", "learning_packages", "cohorts").contains(table)) {
            throw new IllegalArgumentException("Unsupported resource type");
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id = ? AND id = ?",
                Integer.class, organizationId, id
        );
        if (count == null || count != 1) {
            throw new ResponseStatusException(NOT_FOUND, "Resource not found");
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

    private void outbox(
            CurrentActor actor, String event, String type, UUID id, String correlationId, Instant now
    ) {
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

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record Course(UUID id, String title, String description, String state, long version) {
    }

    public record Module(UUID id, UUID courseId, String title, int position) {
    }

    public record Lesson(UUID id, UUID moduleId, UUID packageId, String title, int position) {
    }

    public record Cohort(UUID id, String name, String status, Instant startsAt, Instant endsAt) {
    }

    public record Assignment(
            UUID id, UUID cohortId, UUID lessonId, UUID packageRevisionId,
            String title, String state, Instant availableAt, Instant dueAt
    ) {
    }
}
