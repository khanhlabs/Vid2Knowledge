package com.vid2knowledge.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "true")
public class ReminderSchedulingService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final NotificationQueue notifications;
    private final Clock clock;

    @Autowired
    public ReminderSchedulingService(JdbcTemplate jdbc, NotificationQueue notifications) {
        this(jdbc, notifications, Clock.systemUTC());
    }

    ReminderSchedulingService(JdbcTemplate jdbc, NotificationQueue notifications, Clock clock) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public ScheduleResult schedule() {
        Instant now = clock.instant();
        List<AssignmentRecipient> available = assignmentAvailabilityCandidates(now);
        available.forEach(candidate -> notifications.assignmentAvailable(
                candidate.organizationId(), candidate.userId(), candidate.email(), candidate.organizationName(),
                candidate.assignmentId(), candidate.assignmentTitle(), candidate.availableAt()
        ));

        List<AssignmentRecipient> deadlines = assignmentDeadlineCandidates(now);
        deadlines.forEach(candidate -> notifications.assignmentDue(
                candidate.organizationId(), candidate.userId(), candidate.email(), candidate.organizationName(),
                candidate.assignmentId(), candidate.assignmentTitle(), candidate.dueAt(),
                later(now, candidate.dueAt().minus(Duration.ofHours(24)))
        ));

        ReviewSchedule reviewSchedule = reviewSchedule(now);
        List<ReviewRecipient> reviews = reviewCandidates(now, reviewSchedule.date());
        reviews.forEach(candidate -> notifications.reviewDue(
                candidate.organizationId(), candidate.userId(), candidate.email(), candidate.organizationName(),
                candidate.dueCards(), reviewSchedule.date(), reviewSchedule.notifyAt()
        ));
        return new ScheduleResult(available.size(), deadlines.size(), reviews.size());
    }

    private List<AssignmentRecipient> assignmentAvailabilityCandidates(Instant now) {
        return jdbc.query(
                """
                SELECT a.organization_id, a.id AS assignment_id, a.title, a.available_at, a.due_at,
                       u.id AS user_id, u.email, o.name AS organization_name
                FROM assignments a
                JOIN assignment_recipients ar ON ar.assignment_id = a.id
                  AND ar.organization_id = a.organization_id
                JOIN learner_progress lp ON lp.assignment_id = ar.assignment_id AND lp.user_id = ar.user_id
                JOIN users u ON u.id = ar.user_id
                JOIN memberships m ON m.organization_id = a.organization_id AND m.user_id = u.id
                JOIN organizations o ON o.id = a.organization_id
                WHERE a.state = 'PUBLISHED' AND a.published_at >= ? AND a.available_at <= ?
                  AND lp.status <> 'COMPLETED' AND u.status = 'ACTIVE'
                  AND m.status = 'ACTIVE' AND m.role = 'LEARNER'
                  AND o.status = 'ACTIVE'
                  AND COALESCE((SELECT assignment_reminders_enabled FROM notification_preferences
                                WHERE user_id = u.id), TRUE)
                  AND NOT EXISTS (SELECT 1 FROM notification_jobs n
                      WHERE n.dedupe_key = 'assignment/available/' || CAST(a.id AS text) || '/' || CAST(u.id AS text))
                ORDER BY a.available_at, a.id, u.id LIMIT ?
                """,
                this::assignmentRecipient,
                Timestamp.from(now.minus(Duration.ofDays(30))), Timestamp.from(now.plus(Duration.ofDays(30))),
                BATCH_SIZE
        );
    }

    private List<AssignmentRecipient> assignmentDeadlineCandidates(Instant now) {
        return jdbc.query(
                """
                SELECT a.organization_id, a.id AS assignment_id, a.title, a.available_at, a.due_at,
                       u.id AS user_id, u.email, o.name AS organization_name
                FROM assignments a
                JOIN assignment_recipients ar ON ar.assignment_id = a.id
                  AND ar.organization_id = a.organization_id
                JOIN learner_progress lp ON lp.assignment_id = ar.assignment_id AND lp.user_id = ar.user_id
                JOIN users u ON u.id = ar.user_id
                JOIN memberships m ON m.organization_id = a.organization_id AND m.user_id = u.id
                JOIN organizations o ON o.id = a.organization_id
                WHERE a.state = 'PUBLISHED' AND a.due_at > ? AND a.due_at <= ?
                  AND lp.status <> 'COMPLETED' AND u.status = 'ACTIVE'
                  AND m.status = 'ACTIVE' AND m.role = 'LEARNER'
                  AND o.status = 'ACTIVE'
                  AND COALESCE((SELECT assignment_reminders_enabled FROM notification_preferences
                                WHERE user_id = u.id), TRUE)
                  AND NOT EXISTS (SELECT 1 FROM notification_jobs n
                      WHERE n.dedupe_key = 'assignment/due/' || CAST(a.id AS text) || '/' || CAST(u.id AS text))
                ORDER BY a.due_at, a.id, u.id LIMIT ?
                """,
                this::assignmentRecipient, Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(7))),
                BATCH_SIZE
        );
    }

    private List<ReviewRecipient> reviewCandidates(Instant now, LocalDate reminderDate) {
        return jdbc.query(
                """
                SELECT f.organization_id, f.user_id, u.email, o.name AS organization_name,
                       count(*) AS due_cards
                FROM flashcard_memory_states f
                JOIN assignments a ON a.id = f.assignment_id AND a.organization_id = f.organization_id
                JOIN learner_progress lp ON lp.assignment_id = f.assignment_id AND lp.user_id = f.user_id
                JOIN users u ON u.id = f.user_id
                JOIN memberships m ON m.organization_id = f.organization_id AND m.user_id = f.user_id
                JOIN organizations o ON o.id = f.organization_id
                WHERE f.due_at <= ? AND a.state = 'PUBLISHED' AND a.available_at <= ?
                  AND (a.due_at IS NULL OR a.due_at > ?) AND lp.status <> 'COMPLETED'
                  AND u.status = 'ACTIVE' AND m.status = 'ACTIVE' AND m.role = 'LEARNER'
                  AND o.status = 'ACTIVE'
                  AND COALESCE((SELECT assignment_reminders_enabled FROM notification_preferences
                                WHERE user_id = u.id), TRUE)
                  AND NOT EXISTS (SELECT 1 FROM notification_jobs n
                      WHERE n.dedupe_key = 'review/due/' || CAST(f.organization_id AS text) || '/'
                          || CAST(f.user_id AS text) || '/' || ?)
                GROUP BY f.organization_id, f.user_id, u.email, o.name
                ORDER BY min(f.due_at), f.organization_id, f.user_id LIMIT ?
                """,
                (result, row) -> new ReviewRecipient(
                        result.getObject("organization_id", UUID.class), result.getObject("user_id", UUID.class),
                        result.getString("email"), result.getString("organization_name"),
                        result.getInt("due_cards")
                ),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), reminderDate.toString(), BATCH_SIZE
        );
    }

    private AssignmentRecipient assignmentRecipient(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        Timestamp dueAt = result.getTimestamp("due_at");
        return new AssignmentRecipient(
                result.getObject("organization_id", UUID.class), result.getObject("assignment_id", UUID.class),
                result.getString("title"), result.getTimestamp("available_at").toInstant(),
                dueAt == null ? null : dueAt.toInstant(), result.getObject("user_id", UUID.class),
                result.getString("email"), result.getString("organization_name")
        );
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static ReviewSchedule reviewSchedule(Instant now) {
        ZonedDateTime localNow = now.atZone(BUSINESS_ZONE);
        ZonedDateTime todayAtEight = localNow.toLocalDate().atTime(8, 0).atZone(BUSINESS_ZONE);
        ZonedDateTime notifyAt = localNow.isBefore(todayAtEight) ? todayAtEight : todayAtEight.plusDays(1);
        return new ReviewSchedule(notifyAt.toLocalDate(), notifyAt.toInstant());
    }

    private record AssignmentRecipient(
            UUID organizationId, UUID assignmentId, String assignmentTitle, Instant availableAt,
            Instant dueAt, UUID userId, String email, String organizationName
    ) { }

    private record ReviewRecipient(
            UUID organizationId, UUID userId, String email, String organizationName, int dueCards
    ) { }

    private record ReviewSchedule(LocalDate date, Instant notifyAt) { }

    public record ScheduleResult(int assignmentAvailable, int assignmentDue, int reviewDue) { }
}
