package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
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
import java.util.List;

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

    public List<Course> courses(UUID organizationId) {
        return jdbc.query(
                """
                SELECT c.id, c.title, c.description, c.state, c.version,
                       count(DISTINCT m.id) AS module_count, count(DISTINCT l.id) AS lesson_count
                FROM courses c
                LEFT JOIN course_modules m ON m.course_id = c.id AND m.organization_id = c.organization_id
                LEFT JOIN lessons l ON l.module_id = m.id AND l.organization_id = c.organization_id
                WHERE c.organization_id = ?
                GROUP BY c.id ORDER BY c.updated_at DESC, c.id DESC LIMIT 500
                """,
                (result, row) -> new Course(
                        result.getObject("id", UUID.class), result.getString("title"),
                        result.getString("description"), result.getString("state"), result.getLong("version"),
                        result.getInt("module_count"), result.getInt("lesson_count")
                ), organizationId
        );
    }

    public CourseDetail course(UUID organizationId, UUID courseId) {
        Course course = courses(organizationId).stream().filter(item -> item.id().equals(courseId))
                .findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Course not found"));
        List<ModuleDetail> modules = jdbc.query(
                """
                SELECT m.id AS module_id, m.course_id, m.title AS module_title, m.position AS module_position,
                       l.id AS lesson_id, l.package_id, l.title AS lesson_title, l.position AS lesson_position
                FROM course_modules m
                LEFT JOIN lessons l ON l.module_id = m.id AND l.organization_id = m.organization_id
                WHERE m.organization_id = ? AND m.course_id = ?
                ORDER BY m.position, m.id, l.position, l.id
                """,
                result -> {
                    java.util.LinkedHashMap<UUID, ModuleAccumulator> grouped = new java.util.LinkedHashMap<>();
                    while (result.next()) {
                        UUID moduleId = result.getObject("module_id", UUID.class);
                        ModuleAccumulator module = grouped.computeIfAbsent(moduleId, ignored -> new ModuleAccumulator(
                                moduleId, courseId, resultString(result, "module_title"), resultInt(result, "module_position")
                        ));
                        UUID lessonId = result.getObject("lesson_id", UUID.class);
                        if (lessonId != null) {
                            module.lessons.add(new Lesson(
                                    lessonId, moduleId, result.getObject("package_id", UUID.class),
                                    result.getString("lesson_title"), result.getInt("lesson_position")
                            ));
                        }
                    }
                    return grouped.values().stream().map(ModuleAccumulator::view).toList();
                }, organizationId, courseId
        );
        return new CourseDetail(course, modules);
    }

    public List<Cohort> cohorts(UUID organizationId) {
        return jdbc.query(
                """
                SELECT c.id, c.name, c.status, c.starts_at, c.ends_at, count(cm.user_id) AS member_count
                FROM cohorts c LEFT JOIN cohort_members cm ON cm.cohort_id = c.id
                WHERE c.organization_id = ? GROUP BY c.id
                ORDER BY c.created_at DESC, c.id DESC LIMIT 500
                """,
                (result, row) -> new Cohort(
                        result.getObject("id", UUID.class), result.getString("name"), result.getString("status"),
                        instant(result.getTimestamp("starts_at")), instant(result.getTimestamp("ends_at")),
                        result.getInt("member_count")
                ), organizationId
        );
    }

    public List<Assignment> assignments(UUID organizationId) {
        return jdbc.query(
                """
                SELECT id, cohort_id, lesson_id, package_revision_id, title, state, available_at, due_at
                FROM assignments WHERE organization_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 1000
                """,
                (result, row) -> new Assignment(
                        result.getObject("id", UUID.class), result.getObject("cohort_id", UUID.class),
                        result.getObject("lesson_id", UUID.class), result.getObject("package_revision_id", UUID.class),
                        result.getString("title"), result.getString("state"),
                        result.getTimestamp("available_at").toInstant(), instant(result.getTimestamp("due_at"))
                ), organizationId
        );
    }

    @Transactional
    public ProgramLaunch launchProgram(
            CurrentActor actor,
            String title,
            UUID packageId,
            List<UUID> learnerIds,
            Instant availableAt,
            Instant dueAt,
            String idempotencyKey,
            String correlationId
    ) {
        String fingerprint = RequestFingerprint.sha256(
                title.trim() + "|" + packageId + "|" + learnerIds.stream().sorted().toList()
                        + "|" + availableAt + "|" + dueAt
        );
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                result -> null,
                actor.organizationId() + ":program-launch:" + idempotencyKey
        );
        List<StoredLaunch> existing = jdbc.query(
                """
                SELECT request_hash, course_id, cohort_id, assignment_id
                FROM program_launches WHERE organization_id = ? AND idempotency_key = ?
                """,
                (result, row) -> new StoredLaunch(
                        result.getString("request_hash"), result.getObject("course_id", UUID.class),
                        result.getObject("cohort_id", UUID.class), result.getObject("assignment_id", UUID.class)
                ), actor.organizationId(), idempotencyKey
        );
        if (!existing.isEmpty()) {
            StoredLaunch launch = existing.getFirst();
            if (!launch.requestHash.equals(fingerprint)) throw new IdempotencyConflictException();
            return new ProgramLaunch(launch.courseId, launch.cohortId, launch.assignmentId);
        }
        if (learnerIds.isEmpty() || learnerIds.size() > 2_000 || learnerIds.stream().distinct().count() != learnerIds.size()) {
            throw new IllegalArgumentException("Program launch requires 1 to 2000 distinct learners");
        }
        Course course = createCourse(actor, title, "Launched from an approved Vid2Knowledge package", correlationId);
        Module module = addModule(actor, course.id(), "Nội dung chính", 1, correlationId);
        Lesson lesson = addLesson(actor, module.id(), packageId, title, 1, correlationId);
        Cohort cohort = createCohort(actor, title, availableAt, dueAt, correlationId);
        learnerIds.forEach(learnerId -> addCohortMember(actor, cohort.id(), learnerId, correlationId));
        Assignment assignment = createAssignment(
                actor, cohort.id(), lesson.id(), title, availableAt, dueAt, correlationId
        );
        assignment = publishAssignment(actor, assignment.id(), correlationId);
        jdbc.update(
                """
                INSERT INTO program_launches(
                    id, organization_id, idempotency_key, request_hash, course_id,
                    cohort_id, assignment_id, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), idempotencyKey, fingerprint, course.id(),
                cohort.id(), assignment.id(), actor.userId(), Timestamp.from(clock.instant())
        );
        return new ProgramLaunch(course.id(), cohort.id(), assignment.id());
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
        return new Course(id, title.trim(), description == null ? "" : description.trim(), "DRAFT", 0, 0, 0);
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
        return new Cohort(id, name.trim(), "ACTIVE", startsAt, endsAt, 0);
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

    private static String resultString(java.sql.ResultSet result, String column) {
        try { return result.getString(column); } catch (java.sql.SQLException exception) { throw new IllegalStateException(exception); }
    }

    private static int resultInt(java.sql.ResultSet result, String column) {
        try { return result.getInt(column); } catch (java.sql.SQLException exception) { throw new IllegalStateException(exception); }
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record Course(
            UUID id, String title, String description, String state, long version, int moduleCount, int lessonCount
    ) {
    }

    public record Module(UUID id, UUID courseId, String title, int position) {
    }

    public record Lesson(UUID id, UUID moduleId, UUID packageId, String title, int position) {
    }

    public record CourseDetail(Course course, List<ModuleDetail> modules) {}

    public record ModuleDetail(UUID id, UUID courseId, String title, int position, List<Lesson> lessons) {}

    private static final class ModuleAccumulator {
        private final UUID id;
        private final UUID courseId;
        private final String title;
        private final int position;
        private final List<Lesson> lessons = new java.util.ArrayList<>();

        private ModuleAccumulator(UUID id, UUID courseId, String title, int position) {
            this.id = id;
            this.courseId = courseId;
            this.title = title;
            this.position = position;
        }

        private ModuleDetail view() { return new ModuleDetail(id, courseId, title, position, List.copyOf(lessons)); }
    }

    public record Cohort(
            UUID id, String name, String status, Instant startsAt, Instant endsAt, int memberCount
    ) {
    }

    public record Assignment(
            UUID id, UUID cohortId, UUID lessonId, UUID packageRevisionId,
            String title, String state, Instant availableAt, Instant dueAt
    ) {
    }

    public record ProgramLaunch(UUID courseId, UUID cohortId, UUID assignmentId) {}

    private record StoredLaunch(String requestHash, UUID courseId, UUID cohortId, UUID assignmentId) {}
}
