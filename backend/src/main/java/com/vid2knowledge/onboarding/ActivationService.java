package com.vid2knowledge.onboarding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class ActivationService {
    private static final int TOTAL_STEPS = 6;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ActivationService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    ActivationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ActivationStatus status(UUID organizationId) {
        Instant now = clock.instant();
        Facts facts = jdbc.queryForObject(
                """
                SELECT
                    EXISTS(SELECT 1 FROM sources WHERE organization_id = ?) AS source_added,
                    EXISTS(SELECT 1 FROM learning_packages
                           WHERE organization_id = ? AND current_revision_id IS NOT NULL) AS package_ready,
                    EXISTS(SELECT 1 FROM courses WHERE organization_id = ?) AS course_created,
                    (EXISTS(SELECT 1 FROM invitations
                            WHERE organization_id = ? AND role = 'LEARNER' AND revoked_at IS NULL)
                     OR EXISTS(SELECT 1 FROM memberships
                               WHERE organization_id = ? AND role = 'LEARNER' AND status = 'ACTIVE')) AS learner_invited,
                    EXISTS(SELECT 1 FROM program_launches WHERE organization_id = ?) AS program_launched,
                    EXISTS(SELECT 1 FROM learner_progress
                           WHERE organization_id = ? AND status = 'COMPLETED') AS learner_completed,
                    EXISTS(SELECT 1 FROM subscriptions
                           WHERE organization_id = ? AND status IN ('ACTIVE', 'PAST_DUE')
                             AND current_period_end > ?) AS paid,
                    (SELECT max(period_end) FROM entitlements
                     WHERE organization_id = ? AND period_end > ?) AS entitlement_end
                """,
                (result, row) -> new Facts(
                        result.getBoolean("source_added"), result.getBoolean("package_ready"),
                        result.getBoolean("course_created"), result.getBoolean("learner_invited"),
                        result.getBoolean("program_launched"), result.getBoolean("learner_completed"),
                        result.getBoolean("paid"), instant(result.getTimestamp("entitlement_end"))
                ),
                organizationId, organizationId, organizationId, organizationId, organizationId,
                organizationId, organizationId, organizationId, Timestamp.from(now),
                organizationId, Timestamp.from(now)
        );
        if (facts == null) throw new IllegalStateException("Could not calculate activation status");
        List<Step> steps = List.of(
                new Step("ADD_SOURCE", "Thêm video có quyền sử dụng", facts.sourceAdded()),
                new Step("REVIEW_PACKAGE", "Kiểm duyệt học liệu AI", facts.packageReady()),
                new Step("CREATE_COURSE", "Tạo chương trình học", facts.courseCreated()),
                new Step("INVITE_LEARNER", "Mời học viên", facts.learnerInvited()),
                new Step("LAUNCH_PROGRAM", "Giao bài cho cohort", facts.programLaunched()),
                new Step("PROVE_OUTCOME", "Có học viên hoàn thành", facts.learnerCompleted())
        );
        int completed = (int) steps.stream().filter(Step::complete).count();
        String nextAction = steps.stream().filter(step -> !step.complete()).map(Step::code)
                .findFirst().orElse("REVIEW_OUTCOMES");
        return new ActivationStatus(completed, TOTAL_STEPS, completed == TOTAL_STEPS, facts.paid(),
                facts.paid() ? null : facts.entitlementEnd(), nextAction, steps);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Facts(
            boolean sourceAdded, boolean packageReady, boolean courseCreated, boolean learnerInvited,
            boolean programLaunched, boolean learnerCompleted, boolean paid, Instant entitlementEnd
    ) { }

    public record Step(String code, String title, boolean complete) { }

    public record ActivationStatus(
            int completedSteps, int totalSteps, boolean activated, boolean paid, Instant trialEndsAt,
            String nextAction, List<Step> steps
    ) { }
}
