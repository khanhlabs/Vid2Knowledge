package com.vid2knowledge.analytics;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class ProfitabilityService {
    private static final long DEFAULT_USD_VND = 26_000;
    private final JdbcTemplate jdbc;

    public ProfitabilityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EconomicProfile profile(UUID organizationId) {
        return jdbc.query(
                """
                SELECT usd_vnd_rate, payment_fee_bps, payment_fixed_fee_vnd,
                       monthly_infrastructure_vnd, monthly_support_minutes, support_hourly_vnd,
                       tax_reserve_bps, acquisition_cost_vnd, monthly_logo_churn_bps,
                       assumptions_confirmed, updated_at
                FROM organization_economic_profiles WHERE organization_id = ?
                """,
                (result, row) -> new EconomicProfile(
                        result.getLong("usd_vnd_rate"), result.getInt("payment_fee_bps"),
                        result.getLong("payment_fixed_fee_vnd"),
                        result.getLong("monthly_infrastructure_vnd"),
                        result.getInt("monthly_support_minutes"), result.getLong("support_hourly_vnd"),
                        result.getInt("tax_reserve_bps"), result.getLong("acquisition_cost_vnd"),
                        result.getInt("monthly_logo_churn_bps"),
                        result.getBoolean("assumptions_confirmed"),
                        result.getTimestamp("updated_at").toInstant()
                ), organizationId
        ).stream().findFirst().orElse(defaultProfile());
    }

    public EconomicProfile updateProfile(CurrentActor actor, EconomicProfile input) {
        validate(input);
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO organization_economic_profiles(
                    organization_id, usd_vnd_rate, payment_fee_bps, payment_fixed_fee_vnd,
                    monthly_infrastructure_vnd, monthly_support_minutes, support_hourly_vnd,
                    tax_reserve_bps, acquisition_cost_vnd, monthly_logo_churn_bps,
                    assumptions_confirmed, updated_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id) DO UPDATE SET
                    usd_vnd_rate = EXCLUDED.usd_vnd_rate,
                    payment_fee_bps = EXCLUDED.payment_fee_bps,
                    payment_fixed_fee_vnd = EXCLUDED.payment_fixed_fee_vnd,
                    monthly_infrastructure_vnd = EXCLUDED.monthly_infrastructure_vnd,
                    monthly_support_minutes = EXCLUDED.monthly_support_minutes,
                    support_hourly_vnd = EXCLUDED.support_hourly_vnd,
                    tax_reserve_bps = EXCLUDED.tax_reserve_bps,
                    acquisition_cost_vnd = EXCLUDED.acquisition_cost_vnd,
                    monthly_logo_churn_bps = EXCLUDED.monthly_logo_churn_bps,
                    assumptions_confirmed = EXCLUDED.assumptions_confirmed,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """,
                actor.organizationId(), input.usdVndRate(), input.paymentFeeBps(), input.paymentFixedFeeVnd(),
                input.monthlyInfrastructureVnd(), input.monthlySupportMinutes(), input.supportHourlyVnd(),
                input.taxReserveBps(), input.acquisitionCostVnd(), input.monthlyLogoChurnBps(),
                input.assumptionsConfirmed(), actor.userId(), Timestamp.from(now)
        );
        audit(actor, "ECONOMIC_ASSUMPTIONS_UPDATED", actor.organizationId(), now);
        return profile(actor.organizationId());
    }

    public CostEntry addCost(CurrentActor actor, String category, long amountVnd, Instant incurredAt, String note) {
        String normalizedCategory = category == null ? "" : category.trim().toUpperCase();
        String normalizedNote = note == null ? "" : note.trim();
        if (!List.of("ONBOARDING", "SUPPORT", "INFRASTRUCTURE", "STORAGE", "EMAIL", "SALES", "OTHER")
                .contains(normalizedCategory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported cost category");
        }
        if (amountVnd <= 0 || incurredAt == null || normalizedNote.length() < 3 || normalizedNote.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid amount, date and note are required");
        }
        UUID id = UuidV7Generator.generate();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO account_cost_entries(
                    id, organization_id, category, amount_vnd, incurred_at, note, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), normalizedCategory, amountVnd, Timestamp.from(incurredAt),
                normalizedNote, actor.userId(), Timestamp.from(now)
        );
        audit(actor, "ACCOUNT_COST_RECORDED", id, now);
        return new CostEntry(id, normalizedCategory, amountVnd, incurredAt, normalizedNote);
    }

    public ProfitabilityReport report(UUID organizationId, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).toDays() > 366) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profitability range must be 1-366 days");
        }
        EconomicProfile profile = profile(organizationId);
        Revenue revenue = jdbc.queryForObject(
                """
                SELECT
                  COALESCE((SELECT sum(p.amount_vnd) FROM payments p
                    WHERE p.organization_id = ? AND p.received_at >= ? AND p.received_at < ?), 0) AS gross_cash,
                  COALESCE((SELECT sum(r.amount_vnd) FROM refund_requests r
                    WHERE r.organization_id = ? AND r.state = 'SUCCEEDED'
                      AND r.resolved_at >= ? AND r.resolved_at < ?), 0) AS refunds,
                  COALESCE((SELECT sum(CASE WHEN i.invoice_type = 'TOP_UP' THEN i.amount_due_vnd ELSE
                    round(i.amount_due_vnd * EXTRACT(EPOCH FROM (
                      LEAST(bp.period_end, ?) - GREATEST(bp.period_start, ?)
                    )) / NULLIF(EXTRACT(EPOCH FROM (bp.period_end - bp.period_start)), 0)) END)
                    FROM invoices i LEFT JOIN subscription_billing_periods bp ON bp.billing_order_id = i.billing_order_id
                    WHERE i.organization_id = ? AND i.paid_at IS NOT NULL AND (
                      (i.invoice_type = 'TOP_UP' AND i.paid_at >= ? AND i.paid_at < ?)
                      OR (i.invoice_type = 'SUBSCRIPTION' AND bp.period_end > ? AND bp.period_start < ?)
                    )), 0) AS recognized,
                  COALESCE((SELECT count(*) FROM payments p
                    WHERE p.organization_id = ? AND p.received_at >= ? AND p.received_at < ?), 0) AS payment_count
                """,
                (result, row) -> new Revenue(
                        result.getLong("gross_cash"), result.getLong("refunds"),
                        result.getLong("recognized"), result.getLong("payment_count")
                ),
                organizationId, Timestamp.from(from), Timestamp.from(to),
                organizationId, Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(to), Timestamp.from(from), organizationId,
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                organizationId, Timestamp.from(from), Timestamp.from(to)
        );
        CostTotals costs = jdbc.queryForObject(
                """
                SELECT
                  COALESCE((SELECT sum(actual_cost_microusd) FROM cost_ledger
                    WHERE organization_id = ? AND occurred_at >= ? AND occurred_at < ?), 0) AS actual_ai,
                  COALESCE((SELECT sum(shadow_cost_microusd) FROM cost_ledger
                    WHERE organization_id = ? AND occurred_at >= ? AND occurred_at < ?), 0) AS shadow_ai,
                  COALESCE((SELECT sum(amount_vnd) FROM account_cost_entries
                    WHERE organization_id = ? AND incurred_at >= ? AND incurred_at < ? AND voided_at IS NULL), 0) AS manual,
                  (SELECT count(*) FROM qa_provider_calls
                    WHERE organization_id = ? AND started_at >= ? AND started_at < ?
                      AND state IN ('STARTED', 'UNKNOWN')) AS unresolved_ai_calls
                """,
                (result, row) -> new CostTotals(
                        result.getLong("actual_ai"), result.getLong("shadow_ai"), result.getLong("manual"),
                        result.getLong("unresolved_ai_calls")
                ),
                organizationId, Timestamp.from(from), Timestamp.from(to),
                organizationId, Timestamp.from(from), Timestamp.from(to),
                organizationId, Timestamp.from(from), Timestamp.from(to),
                organizationId, Timestamp.from(from), Timestamp.from(to)
        );
        double months = Math.max(1.0 / 30.4375, Duration.between(from, to).toSeconds() / 2_629_800.0);
        long recognizedNet = revenue.recognizedVnd() - revenue.refundsVnd();
        long actualAiVnd = microUsdToVnd(costs.actualAiMicroUsd(), profile.usdVndRate());
        long shadowAiVnd = microUsdToVnd(costs.shadowAiMicroUsd(), profile.usdVndRate());
        long paymentFees = basisPoints(revenue.grossCashVnd(), profile.paymentFeeBps())
                + revenue.paymentCount() * profile.paymentFixedFeeVnd();
        long allocatedInfrastructure = Math.round(profile.monthlyInfrastructureVnd() * months);
        long modeledSupport = Math.round(
                profile.monthlySupportMinutes() * months * profile.supportHourlyVnd() / 60.0
        );
        long grossProfit = recognizedNet - paymentFees - shadowAiVnd - allocatedInfrastructure;
        long contributionProfit = grossProfit - modeledSupport - costs.manualVnd();
        long taxReserve = basisPoints(Math.max(0, recognizedNet), profile.taxReserveBps());
        Double grossMargin = ratio(grossProfit, recognizedNet);
        Double contributionMargin = ratio(contributionProfit, recognizedNet);
        Double aiShare = ratio(shadowAiVnd, recognizedNet);
        String status = costs.unresolvedAiCalls() > 0 ? "COST_UNVERIFIED"
                : status(profile, recognizedNet, grossMargin, contributionMargin, aiShare);
        Long cacPaybackMonths = contributionProfit > 0 && profile.acquisitionCostVnd() > 0
                ? (long) Math.ceil((double) profile.acquisitionCostVnd() / contributionProfit * months) : null;
        long monthlyContributionVnd = Math.round(contributionProfit / months);
        Long contributionLtvVnd = monthlyContributionVnd > 0 && profile.monthlyLogoChurnBps() > 0
                ? Math.round(monthlyContributionVnd / (profile.monthlyLogoChurnBps() / 10_000.0)) : null;
        Double ltvCacRatio = contributionLtvVnd != null && profile.acquisitionCostVnd() > 0
                ? (double) contributionLtvVnd / profile.acquisitionCostVnd() : null;
        return new ProfitabilityReport(
                from, to, profile.assumptionsConfirmed(), status,
                revenue.grossCashVnd(), revenue.grossCashVnd() - revenue.refundsVnd(),
                revenue.refundsVnd(), recognizedNet,
                actualAiVnd, shadowAiVnd, paymentFees, allocatedInfrastructure,
                modeledSupport, costs.manualVnd(), taxReserve, grossProfit, contributionProfit,
                grossMargin, contributionMargin, aiShare, cacPaybackMonths,
                monthlyContributionVnd, contributionLtvVnd, ltvCacRatio, costs.unresolvedAiCalls()
        );
    }

    private static String status(
            EconomicProfile profile, long revenue, Double grossMargin, Double contributionMargin, Double aiShare
    ) {
        if (!profile.assumptionsConfirmed()) return "UNCONFIGURED";
        if (revenue == 0) return "NO_REVENUE";
        if (revenue < 0) return "NEGATIVE";
        if (contributionMargin == null || contributionMargin < 0) return "NEGATIVE";
        if (aiShare != null && aiShare > 0.15) return "AI_COST_CRITICAL";
        if (grossMargin < 0.60 || contributionMargin < 0.60) return "BELOW_FLOOR";
        if (grossMargin >= 0.75) return "HEALTHY";
        return "WATCH";
    }

    private static long microUsdToVnd(long microUsd, long rate) {
        return BigDecimal.valueOf(microUsd).multiply(BigDecimal.valueOf(rate))
                .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long basisPoints(long value, int bps) {
        return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(bps))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator <= 0 ? null : (double) numerator / denominator;
    }

    private static void validate(EconomicProfile profile) {
        if (profile == null || profile.usdVndRate() < 1 || profile.usdVndRate() > 100_000
                || profile.paymentFeeBps() < 0 || profile.paymentFeeBps() > 10_000
                || profile.paymentFixedFeeVnd() < 0 || profile.monthlyInfrastructureVnd() < 0
                || profile.monthlySupportMinutes() < 0 || profile.supportHourlyVnd() < 0
                || profile.taxReserveBps() < 0 || profile.taxReserveBps() > 10_000
                || profile.acquisitionCostVnd() < 0 || profile.monthlyLogoChurnBps() < 0
                || profile.monthlyLogoChurnBps() > 10_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Economic assumptions are out of range");
        }
    }

    private void audit(CurrentActor actor, String action, UUID resourceId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(id, organization_id, actor_user_id, action, resource_type,
                                       resource_id, correlation_id, created_at)
                VALUES (?, ?, ?, ?, 'Profitability', ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action, resourceId,
                "profitability-" + UuidV7Generator.generate(), Timestamp.from(now)
        );
    }

    private static EconomicProfile defaultProfile() {
        return new EconomicProfile(
                DEFAULT_USD_VND, 150, 0, 100_000, 120, 200_000,
                1_000, 0, 300, false, null
        );
    }

    public record EconomicProfile(
            long usdVndRate, int paymentFeeBps, long paymentFixedFeeVnd,
            long monthlyInfrastructureVnd, int monthlySupportMinutes, long supportHourlyVnd,
            int taxReserveBps, long acquisitionCostVnd, int monthlyLogoChurnBps,
            boolean assumptionsConfirmed, Instant updatedAt
    ) { }

    public record CostEntry(UUID id, String category, long amountVnd, Instant incurredAt, String note) { }

    public record ProfitabilityReport(
            Instant from, Instant to, boolean assumptionsConfirmed, String status,
            long grossCashVnd, long netCashVnd, long refundsVnd, long recognizedRevenueVnd,
            long actualAiCostVnd, long shadowAiCostVnd, long paymentFeesVnd,
            long allocatedInfrastructureVnd, long modeledSupportVnd, long manualDirectCostsVnd,
            long taxReserveVnd, long grossProfitVnd, long contributionProfitVnd,
            Double grossMargin, Double contributionMargin, Double shadowAiRevenueShare,
            Long cacPaybackMonths, long monthlyContributionVnd, Long contributionLtvVnd,
            Double ltvCacRatio, long unresolvedAiCalls
    ) { }

    private record Revenue(long grossCashVnd, long refundsVnd, long recognizedVnd, long paymentCount) { }
    private record CostTotals(long actualAiMicroUsd, long shadowAiMicroUsd, long manualVnd, long unresolvedAiCalls) { }
}
