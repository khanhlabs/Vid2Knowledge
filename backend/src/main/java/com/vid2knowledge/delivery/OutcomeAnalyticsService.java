package com.vid2knowledge.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class OutcomeAnalyticsService {

    private static final String TIMEZONE = "Asia/Ho_Chi_Minh";
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public OutcomeAnalyticsService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    OutcomeAnalyticsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public OrganizationOutcome overview(UUID organizationId) {
        Overview totals = jdbc.queryForObject(
                """
                SELECT (SELECT count(*) FROM cohorts WHERE organization_id = ? AND status = 'ACTIVE') AS active_cohorts,
                       (SELECT count(DISTINCT user_id) FROM cohort_members WHERE organization_id = ?) AS learners,
                       count(*) AS assigned,
                       count(*) FILTER (WHERE lp.status IN ('STARTED','COMPLETED')) AS started,
                       count(*) FILTER (WHERE lp.status = 'COMPLETED') AS completed,
                       ROUND(AVG(lp.best_score_percent) FILTER (WHERE lp.best_score_percent IS NOT NULL))::integer
                           AS average_score
                FROM assignments a
                JOIN learner_progress lp ON lp.assignment_id = a.id AND lp.organization_id = a.organization_id
                WHERE a.organization_id = ? AND a.state IN ('PUBLISHED','CLOSED')
                """,
                (result, row) -> new Overview(
                        result.getLong("active_cohorts"), result.getLong("learners"),
                        result.getLong("assigned"), result.getLong("started"), result.getLong("completed"),
                        result.getObject("average_score", Integer.class)
                ), organizationId, organizationId, organizationId
        );
        ScoreBreakdown scores = scores(organizationId, null);
        FeedbackBreakdown feedback = feedback(organizationId, null);
        return new OrganizationOutcome(
                totals.activeCohorts(), totals.learners(), totals.assigned(), totals.started(), totals.completed(),
                totals.averageScorePercent(), scores.practiceAverage(), scores.delayedRecallAverage(),
                feedback.responses(), feedback.helpful(), feedback.reportedErrors(), feedback.openErrors(),
                TIMEZONE, clock.instant()
        );
    }

    public List<CohortComparison> cohorts(UUID organizationId) {
        return jdbc.query(
                """
                WITH progress AS (
                    SELECT a.cohort_id, count(*) AS assigned,
                           count(*) FILTER (WHERE lp.status IN ('STARTED','COMPLETED')) AS started,
                           count(*) FILTER (WHERE lp.status = 'COMPLETED') AS completed,
                           ROUND(AVG(lp.best_score_percent) FILTER (WHERE lp.best_score_percent IS NOT NULL))::integer
                               AS average_score
                    FROM assignments a
                    JOIN learner_progress lp ON lp.assignment_id = a.id AND lp.organization_id = a.organization_id
                    WHERE a.organization_id = ? AND a.state IN ('PUBLISHED','CLOSED')
                    GROUP BY a.cohort_id
                ), members AS (
                    SELECT cohort_id, count(*) AS learner_count FROM cohort_members
                    WHERE organization_id = ? GROUP BY cohort_id
                )
                SELECT c.id, c.name, c.status, c.starts_at, c.ends_at,
                       COALESCE(m.learner_count, 0) AS learner_count,
                       COALESCE(p.assigned, 0) AS assigned, COALESCE(p.started, 0) AS started,
                       COALESCE(p.completed, 0) AS completed, p.average_score
                FROM cohorts c
                LEFT JOIN progress p ON p.cohort_id = c.id
                LEFT JOIN members m ON m.cohort_id = c.id
                WHERE c.organization_id = ?
                ORDER BY c.created_at DESC, c.id DESC LIMIT 1000
                """,
                (result, row) -> new CohortComparison(
                        result.getObject("id", UUID.class), result.getString("name"), result.getString("status"),
                        result.getTimestamp("starts_at") == null ? null : result.getTimestamp("starts_at").toInstant(),
                        result.getTimestamp("ends_at") == null ? null : result.getTimestamp("ends_at").toInstant(),
                        result.getLong("learner_count"), result.getLong("assigned"), result.getLong("started"),
                        result.getLong("completed"), result.getObject("average_score", Integer.class)
                ), organizationId, organizationId, organizationId
        );
    }

    public CohortOutcome cohort(UUID organizationId, UUID cohortId) {
        requireCohort(organizationId, cohortId);
        OutcomeSummary progress = summaryUnchecked(organizationId, cohortId);
        ScoreBreakdown scores = scores(organizationId, cohortId);
        FeedbackBreakdown feedback = feedback(organizationId, cohortId);
        return new CohortOutcome(
                progress, scores.practiceAverage(), scores.delayedRecallAverage(), feedback.responses(),
                feedback.helpful(), feedback.reportedErrors(), feedback.openErrors(),
                weakTopics(organizationId, cohortId), TIMEZONE, clock.instant()
        );
    }

    public OutcomeSummary summary(UUID organizationId, UUID cohortId) {
        requireCohort(organizationId, cohortId);
        return summaryUnchecked(organizationId, cohortId);
    }

    private OutcomeSummary summaryUnchecked(UUID organizationId, UUID cohortId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) AS assigned,
                       count(*) FILTER (WHERE lp.status IN ('STARTED','COMPLETED')) AS started,
                       count(*) FILTER (WHERE lp.status = 'COMPLETED') AS completed,
                       ROUND(AVG(lp.best_score_percent) FILTER (WHERE lp.best_score_percent IS NOT NULL))::integer
                           AS average_score
                FROM assignments a
                JOIN learner_progress lp ON lp.assignment_id = a.id AND lp.organization_id = a.organization_id
                WHERE a.organization_id = ? AND a.cohort_id = ? AND a.state IN ('PUBLISHED','CLOSED')
                """,
                (result, row) -> new OutcomeSummary(
                        result.getLong("assigned"), result.getLong("started"), result.getLong("completed"),
                        result.getObject("average_score", Integer.class)
                ), organizationId, cohortId
        );
    }

    private ScoreBreakdown scores(UUID organizationId, UUID cohortId) {
        String cohortFilter = cohortId == null ? "" : " AND a.cohort_id = ?";
        Object[] arguments = cohortId == null ? new Object[]{organizationId} : new Object[]{organizationId, cohortId};
        return jdbc.queryForObject(
                """
                SELECT ROUND(AVG(aa.score_percent) FILTER (WHERE s.mode = 'PRACTICE'))::integer AS practice,
                       ROUND(AVG(aa.score_percent) FILTER (WHERE s.mode = 'DELAYED_RECALL'))::integer AS delayed
                FROM assessment_attempts aa
                JOIN assessment_snapshots s ON s.id = aa.snapshot_id AND s.organization_id = aa.organization_id
                JOIN assignments a ON a.id = aa.assignment_id AND a.organization_id = aa.organization_id
                WHERE aa.organization_id = ?
                """ + cohortFilter,
                (result, row) -> new ScoreBreakdown(
                        result.getObject("practice", Integer.class), result.getObject("delayed", Integer.class)
                ), arguments
        );
    }

    private FeedbackBreakdown feedback(UUID organizationId, UUID cohortId) {
        String cohortFilter = cohortId == null ? "" : " AND a.cohort_id = ?";
        Object[] arguments = cohortId == null ? new Object[]{organizationId} : new Object[]{organizationId, cohortId};
        return jdbc.queryForObject(
                """
                SELECT count(*) AS responses,
                       count(*) FILTER (WHERE q.kind = 'HELPFUL') AS helpful,
                       count(*) FILTER (WHERE q.kind = 'REPORT_ERROR') AS reported_errors,
                       count(*) FILTER (WHERE q.kind = 'REPORT_ERROR' AND q.status = 'OPEN') AS open_errors
                FROM quality_feedback q
                WHERE q.organization_id = ? AND EXISTS (
                    SELECT 1 FROM assignments a
                    WHERE a.organization_id = q.organization_id
                      AND a.package_revision_id = q.package_revision_id
                """ + cohortFilter + ")",
                (result, row) -> new FeedbackBreakdown(
                        result.getLong("responses"), result.getLong("helpful"),
                        result.getLong("reported_errors"), result.getLong("open_errors")
                ), arguments
        );
    }

    private List<WeakTopic> weakTopics(UUID organizationId, UUID cohortId) {
        return jdbc.query(
                """
                SELECT answers.question_id, max(answers.question_text) AS question_text,
                       min(answers.source_timestamp_seconds) AS source_timestamp_seconds,
                       count(*) AS answer_count,
                       count(*) FILTER (WHERE NOT answers.is_correct) AS wrong_count
                FROM assessment_attempt_answers answers
                JOIN assessment_attempts attempt ON attempt.id = answers.attempt_id
                JOIN assignments a ON a.id = attempt.assignment_id AND a.organization_id = attempt.organization_id
                WHERE attempt.organization_id = ? AND a.cohort_id = ?
                GROUP BY answers.question_id
                HAVING count(*) FILTER (WHERE NOT answers.is_correct) > 0
                ORDER BY (count(*) FILTER (WHERE NOT answers.is_correct))::numeric / count(*) DESC,
                         count(*) FILTER (WHERE NOT answers.is_correct) DESC,
                         answers.question_id
                LIMIT 10
                """,
                (result, row) -> new WeakTopic(
                        result.getString("question_id"), result.getString("question_text"),
                        result.getLong("source_timestamp_seconds"), result.getLong("answer_count"),
                        result.getLong("wrong_count")
                ), organizationId, cohortId
        );
    }

    private void requireCohort(UUID organizationId, UUID cohortId) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM cohorts WHERE organization_id = ? AND id = ?)",
                Boolean.class, organizationId, cohortId
        ));
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cohort not found");
        }
    }

    public record OrganizationOutcome(
            long activeCohorts, long learners, long assigned, long started, long completed,
            Integer averageScorePercent, Integer practiceAverageScorePercent,
            Integer delayedRecallAverageScorePercent, long feedbackResponses, long helpfulResponses,
            long reportedErrors, long openErrors, String timezone, Instant generatedAt
    ) {
        public double activationRate() { return assigned == 0 ? 0 : started / (double) assigned; }
        public double completionRate() { return assigned == 0 ? 0 : completed / (double) assigned; }
    }

    public record CohortComparison(
            UUID id, String name, String status, Instant startsAt, Instant endsAt,
            long learners, long assigned, long started, long completed, Integer averageScorePercent
    ) {
        public double activationRate() { return assigned == 0 ? 0 : started / (double) assigned; }
        public double completionRate() { return assigned == 0 ? 0 : completed / (double) assigned; }
    }

    public record CohortOutcome(
            OutcomeSummary progress, Integer practiceAverageScorePercent,
            Integer delayedRecallAverageScorePercent, long feedbackResponses, long helpfulResponses,
            long reportedErrors, long openErrors, List<WeakTopic> weakTopics,
            String timezone, Instant generatedAt
    ) {}

    public record WeakTopic(
            String questionId, String question, long sourceTimestampSeconds, long answerCount, long wrongCount
    ) {
        public double errorRate() { return answerCount == 0 ? 0 : wrongCount / (double) answerCount; }
    }

    public record OutcomeSummary(long assigned, long started, long completed, Integer averageScorePercent) {
        public double activationRate() { return assigned == 0 ? 0 : started / (double) assigned; }
        public double completionRate() { return assigned == 0 ? 0 : completed / (double) assigned; }
    }

    private record Overview(
            long activeCohorts, long learners, long assigned, long started, long completed,
            Integer averageScorePercent
    ) {}
    private record ScoreBreakdown(Integer practiceAverage, Integer delayedRecallAverage) {}
    private record FeedbackBreakdown(long responses, long helpful, long reportedErrors, long openErrors) {}
}
