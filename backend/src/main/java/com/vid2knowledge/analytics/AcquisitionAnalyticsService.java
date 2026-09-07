package com.vid2knowledge.analytics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class AcquisitionAnalyticsService {
    private final JdbcTemplate jdbc;

    public AcquisitionAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AcquisitionResult> report() {
        return jdbc.query(
                """
                SELECT a.source,
                       count(*) AS organizations,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM sources s WHERE s.organization_id = a.organization_id
                       )) AS source_started,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM learning_packages p
                           WHERE p.organization_id = a.organization_id AND p.current_revision_id IS NOT NULL
                       )) AS package_ready,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM learner_progress lp
                           WHERE lp.organization_id = a.organization_id AND lp.status = 'COMPLETED'
                       )) AS value_realized,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM payments pay WHERE pay.organization_id = a.organization_id
                       )) AS paid_organizations,
                       sum(
                           COALESCE((SELECT sum(pay.amount_vnd) FROM payments pay
                                     WHERE pay.organization_id = a.organization_id), 0)
                           - COALESCE((SELECT sum(ref.amount_vnd) FROM refund_requests ref
                                       WHERE ref.organization_id = a.organization_id
                                         AND ref.state = 'SUCCEEDED'), 0)
                       ) AS net_revenue_vnd
                FROM organization_acquisition_attributions a
                GROUP BY a.source
                ORDER BY a.source
                """,
                (result, row) -> new AcquisitionResult(
                        result.getString("source"), result.getLong("organizations"),
                        result.getLong("source_started"), result.getLong("package_ready"),
                        result.getLong("value_realized"), result.getLong("paid_organizations"),
                        result.getLong("net_revenue_vnd")
                )
        );
    }

    public record AcquisitionResult(
            String source, long organizations, long sourceStarted, long packageReady,
            long valueRealized, long paidOrganizations, long netRevenueVnd
    ) { }
}
