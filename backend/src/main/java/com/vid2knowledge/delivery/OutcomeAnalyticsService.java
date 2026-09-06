package com.vid2knowledge.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class OutcomeAnalyticsService {

    private final JdbcTemplate jdbc;

    public OutcomeAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OutcomeSummary summary(UUID organizationId, UUID cohortId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) AS assigned,
                       count(*) FILTER (WHERE lp.status IN ('STARTED','COMPLETED')) AS started,
                       count(*) FILTER (WHERE lp.status = 'COMPLETED') AS completed,
                       ROUND(AVG(lp.best_score_percent) FILTER (WHERE lp.best_score_percent IS NOT NULL))::integer AS average_score
                FROM assignments a
                JOIN learner_progress lp ON lp.assignment_id = a.id AND lp.organization_id = a.organization_id
                WHERE a.organization_id = ? AND a.cohort_id = ? AND a.state IN ('PUBLISHED','CLOSED')
                """,
                (result, row) -> new OutcomeSummary(
                        result.getLong("assigned"), result.getLong("started"), result.getLong("completed"),
                        result.getObject("average_score", Integer.class)
                ),
                organizationId, cohortId
        );
    }

    public record OutcomeSummary(long assigned, long started, long completed, Integer averageScorePercent) {
        public double completionRate() {
            return assigned == 0 ? 0 : completed / (double) assigned;
        }
    }
}
