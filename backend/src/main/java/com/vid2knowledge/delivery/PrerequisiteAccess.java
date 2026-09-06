package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

final class PrerequisiteAccess {

    private PrerequisiteAccess() { }

    static boolean isUnlocked(JdbcTemplate jdbc, CurrentActor learner, UUID assignmentId) {
        Boolean unlocked = jdbc.queryForObject(
                """
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM assignments current_assignment
                    JOIN lessons current_lesson ON current_lesson.id = current_assignment.lesson_id
                    JOIN course_modules current_module ON current_module.id = current_lesson.module_id
                    LEFT JOIN course_completion_rules rule ON rule.course_id = current_module.course_id
                    JOIN lesson_prerequisites prerequisite ON prerequisite.lesson_id = current_lesson.id
                    WHERE current_assignment.organization_id = ? AND current_assignment.id = ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM assignments prerequisite_assignment
                          JOIN learner_progress prerequisite_progress
                            ON prerequisite_progress.assignment_id = prerequisite_assignment.id
                           AND prerequisite_progress.user_id = ?
                          WHERE prerequisite_assignment.organization_id = current_assignment.organization_id
                            AND prerequisite_assignment.cohort_id = current_assignment.cohort_id
                            AND prerequisite_assignment.lesson_id = prerequisite.prerequisite_lesson_id
                            AND prerequisite_assignment.state = 'PUBLISHED'
                            AND prerequisite_progress.status = 'COMPLETED'
                            AND prerequisite_progress.best_score_percent >= COALESCE(rule.passing_score_percent, 70)
                      )
                )
                """,
                Boolean.class, learner.organizationId(), assignmentId, learner.userId()
        );
        return Boolean.TRUE.equals(unlocked);
    }

    static void requireUnlocked(JdbcTemplate jdbc, CurrentActor learner, UUID assignmentId) {
        if (!isUnlocked(jdbc, learner, assignmentId)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Complete prerequisite lessons first");
        }
    }
}
