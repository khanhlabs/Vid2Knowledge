package com.vid2knowledge.billing;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.PayOsProperties;
import com.vid2knowledge.notification.NotificationQueue;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import com.vid2knowledge.usage.domain.UsageMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PaymentGateway gateway;
    private final PayOsProperties payOs;
    private final NotificationQueue notifications;
    private final Clock clock;

    @Value("${billing.require-profile-before-checkout:false}")
    private boolean requireProfileBeforeCheckout;

    public BillingService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            PaymentGateway gateway,
            PayOsProperties payOs,
            NotificationQueue notifications
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.gateway = gateway;
        this.payOs = payOs;
        this.notifications = notifications;
        this.clock = Clock.systemUTC();
    }

    public List<Plan> plans() {
        return jdbc.query(
                """
                SELECT id, code, version, name, billing_interval, product_type, amount_vnd,
                       processed_video_seconds, instructor_seats, active_learners, qa_queries
                FROM pricing_plans
                WHERE active = TRUE AND effective_from <= ?
                  AND (effective_until IS NULL OR effective_until > ?)
                ORDER BY amount_vnd, code
                """,
                (result, row) -> new Plan(
                        result.getObject("id", UUID.class), result.getString("code"), result.getInt("version"),
                        result.getString("name"), result.getString("billing_interval"),
                        result.getString("product_type"),
                        result.getLong("amount_vnd"), result.getLong("processed_video_seconds"),
                        result.getInt("instructor_seats"), result.getInt("active_learners"),
                        result.getLong("qa_queries")
                ),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant())
        );
    }

    public UsageView usage(UUID organizationId) {
        List<UsageView> views = jdbc.query(
                """
                SELECT e.allowance,
                       COALESCE(sum(r.committed_units) FILTER (WHERE r.status = 'COMMITTED'), 0) AS committed,
                       COALESCE(sum(r.reserved_units) FILTER (WHERE r.status = 'RESERVED' AND r.expires_at > ?), 0) AS reserved,
                       COALESCE((SELECT sum(c.actual_cost_microusd) FROM cost_ledger c WHERE c.organization_id = ?), 0) AS actual_cost,
                       COALESCE((SELECT sum(c.shadow_cost_microusd) FROM cost_ledger c WHERE c.organization_id = ?), 0) AS shadow_cost,
                       e.period_start, e.period_end
                FROM entitlements e
                LEFT JOIN usage_reservations r ON r.entitlement_id = e.id
                WHERE e.organization_id = ? AND e.metric = ? AND e.period_start <= ? AND e.period_end > ?
                GROUP BY e.id
                ORDER BY e.period_start DESC LIMIT 1
                """,
                (result, row) -> new UsageView(
                        result.getLong("allowance"), result.getLong("committed"), result.getLong("reserved"),
                        result.getLong("actual_cost"), result.getLong("shadow_cost"),
                        result.getTimestamp("period_start").toInstant(), result.getTimestamp("period_end").toInstant(),
                        0, 0, 0
                ),
                Timestamp.from(clock.instant()), organizationId, organizationId, organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND.name(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant())
        );
        UsageView base = views.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "No active entitlement")
        );
        MetricBalance qa = metricBalance(organizationId, UsageMetric.QA_QUERY);
        return new UsageView(
                base.allowanceSeconds(), base.committedSeconds(), base.reservedSeconds(),
                base.actualAiCostMicrousd(), base.shadowAiCostMicrousd(), base.periodStart(), base.periodEnd(),
                qa.allowance(), qa.committed(), qa.reserved()
        );
    }

    private MetricBalance metricBalance(UUID organizationId, UsageMetric metric) {
        return jdbc.query(
                """
                SELECT e.allowance,
                       COALESCE(sum(r.committed_units) FILTER (WHERE r.status = 'COMMITTED'), 0) AS committed,
                       COALESCE(sum(r.reserved_units) FILTER (
                           WHERE r.status = 'RESERVED' AND r.expires_at > ?
                       ), 0) AS reserved
                FROM entitlements e LEFT JOIN usage_reservations r ON r.entitlement_id = e.id
                WHERE e.organization_id = ? AND e.metric = ? AND e.period_start <= ? AND e.period_end > ?
                GROUP BY e.id ORDER BY e.period_start DESC LIMIT 1
                """,
                (result, row) -> new MetricBalance(
                        result.getLong("allowance"), result.getLong("committed"), result.getLong("reserved")
                ),
                Timestamp.from(clock.instant()), organizationId, metric.name(),
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant())
        ).stream().findFirst().orElse(new MetricBalance(0, 0, 0));
    }

    public Optional<SubscriptionView> subscription(UUID organizationId) {
        return jdbc.query(
                """
                SELECT s.id, p.code, p.name, s.status, s.current_period_start,
                       s.current_period_end, s.cancel_at_period_end,
                       COALESCE(sp.code, np.code) AS next_plan_code,
                       COALESCE(sp.name, np.name) AS next_plan_name,
                       sp.id IS NOT NULL AS next_plan_paid
                FROM subscriptions s JOIN pricing_plans p ON p.id = s.plan_id
                LEFT JOIN pricing_plans np ON np.id = s.next_plan_id
                LEFT JOIN LATERAL (
                    SELECT scheduled.id, scheduled_plan.code, scheduled_plan.name
                    FROM subscriptions scheduled
                    JOIN pricing_plans scheduled_plan ON scheduled_plan.id = scheduled.plan_id
                    WHERE scheduled.organization_id = s.organization_id
                      AND scheduled.status = 'SCHEDULED'
                    ORDER BY scheduled.current_period_start, scheduled.id LIMIT 1
                ) sp ON TRUE
                WHERE s.organization_id = ? AND s.status IN ('ACTIVE','PAST_DUE')
                ORDER BY s.current_period_start DESC LIMIT 1
                """,
                (result, row) -> new SubscriptionView(
                        result.getObject("id", UUID.class), result.getString("code"), result.getString("name"),
                        result.getString("status"), result.getTimestamp("current_period_start").toInstant(),
                        result.getTimestamp("current_period_end").toInstant(), result.getBoolean("cancel_at_period_end"),
                        result.getString("next_plan_code"), result.getString("next_plan_name"),
                        result.getBoolean("next_plan_paid")
                ),
                organizationId
        ).stream().findFirst();
    }

    public List<InvoiceView> invoices(UUID organizationId) {
        return jdbc.query(
                """
                SELECT i.id, i.invoice_number, i.state, i.currency, i.invoice_type, i.amount_due_vnd,
                       i.amount_paid_vnd, COALESCE(i.list_price_vnd, i.amount_due_vnd) AS list_price_vnd,
                       i.discount_vnd, c.code AS promotion_code,
                       i.buyer_type_snapshot, i.buyer_legal_name_snapshot,
                       i.buyer_tax_identifier_snapshot, i.buyer_address_snapshot,
                       i.buyer_email_snapshot, i.buyer_country_code_snapshot,
                       i.billing_profile_version, i.tax_document_requested,
                       i.due_at, i.paid_at, i.created_at
                FROM invoices i LEFT JOIN promotion_campaigns c ON c.id = i.promotion_campaign_id
                WHERE i.organization_id = ?
                ORDER BY i.created_at DESC, i.id DESC LIMIT 100
                """,
                (result, row) -> new InvoiceView(
                        result.getObject("id", UUID.class), result.getString("invoice_number"),
                        result.getString("state"), result.getString("currency"), result.getString("invoice_type"),
                        result.getLong("list_price_vnd"), result.getLong("discount_vnd"),
                        result.getLong("amount_due_vnd"), result.getLong("amount_paid_vnd"),
                        result.getString("promotion_code"),
                        result.getString("buyer_type_snapshot"), result.getString("buyer_legal_name_snapshot"),
                        result.getString("buyer_tax_identifier_snapshot"), result.getString("buyer_address_snapshot"),
                        result.getString("buyer_email_snapshot"), result.getString("buyer_country_code_snapshot"),
                        result.getObject("billing_profile_version", Long.class),
                        result.getBoolean("tax_document_requested"),
                        result.getTimestamp("due_at").toInstant(),
                        result.getTimestamp("paid_at") == null ? null : result.getTimestamp("paid_at").toInstant(),
                        result.getTimestamp("created_at").toInstant()
                ),
                organizationId
        );
    }

    public void cancelAtPeriodEnd(CurrentActor actor, UUID subscriptionId, String correlationId) {
        transactions.executeWithoutResult(status -> scheduleCancellation(actor, subscriptionId, correlationId));
    }

    private void scheduleCancellation(CurrentActor actor, UUID subscriptionId, String correlationId) {
        Instant now = clock.instant();
        List<Instant> periodEnds = jdbc.query(
                """
                SELECT current_period_end FROM subscriptions
                WHERE id = ? AND organization_id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """,
                (result, row) -> result.getTimestamp("current_period_end").toInstant(),
                subscriptionId, actor.organizationId()
        );
        if (periodEnds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active subscription not found");
        }
        int updated = jdbc.update(
                """
                UPDATE subscriptions SET cancel_at_period_end = TRUE, cancelled_at = ?,
                    next_plan_id = NULL, plan_change_scheduled_at = NULL,
                    plan_change_scheduled_by = NULL, updated_at = ?
                WHERE id = ? AND organization_id = ? AND status = 'ACTIVE'
                """,
                Timestamp.from(now), Timestamp.from(now), subscriptionId, actor.organizationId()
        );
        if (updated != 1) {
            throw new IllegalStateException("Subscription changed while cancellation was being scheduled");
        }
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, created_at
                ) VALUES (?, ?, ?, 'SUBSCRIPTION_CANCEL_AT_PERIOD_END', 'Subscription', ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), subscriptionId,
                correlationId, Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'SubscriptionCancellationScheduled', 1, 'Subscription', ?, ?,
                          jsonb_build_object('subscriptionId', CAST(? AS text), 'periodEnd', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), subscriptionId, correlationId,
                subscriptionId, periodEnds.getFirst().toString(),
                Timestamp.from(now), Timestamp.from(now)
        );
        notifications.cancellationScheduled(actor.organizationId(), subscriptionId, periodEnds.getFirst());
    }

    public SubscriptionView schedulePlanChange(
            CurrentActor actor, UUID subscriptionId, UUID targetPlanId, String correlationId
    ) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            List<PlanChangeSource> sources = jdbc.query(
                    """
                    SELECT s.plan_id, s.current_period_end
                    FROM subscriptions s
                    WHERE s.id = ? AND s.organization_id = ? AND s.status = 'ACTIVE'
                    FOR UPDATE
                    """,
                    (result, row) -> new PlanChangeSource(
                            result.getObject("plan_id", UUID.class),
                            result.getTimestamp("current_period_end").toInstant()
                    ), subscriptionId, actor.organizationId()
            );
            if (sources.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active subscription not found");
            }
            PlanChangeSource source = sources.getFirst();
            if (source.planId().equals(targetPlanId)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Target plan is already active");
            }
            Plan target = plans().stream()
                    .filter(candidate -> candidate.id().equals(targetPlanId)
                            && "SUBSCRIPTION".equals(candidate.productType()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription plan not found"));
            ensureRenewalNotStarted(subscriptionId, source.periodEnd());
            jdbc.update(
                    """
                    UPDATE subscriptions SET next_plan_id = ?, plan_change_scheduled_at = ?,
                        plan_change_scheduled_by = ?, cancel_at_period_end = FALSE,
                        cancelled_at = NULL, updated_at = ? WHERE id = ?
                    """,
                    target.id(), Timestamp.from(now), actor.userId(), Timestamp.from(now), subscriptionId
            );
            appendPlanChangeAuditAndEvent(
                    actor, subscriptionId, source.planId(), target.id(), source.periodEnd(), correlationId, now,
                    "SUBSCRIPTION_PLAN_CHANGE_SCHEDULED", "SubscriptionPlanChangeScheduled"
            );
        });
        return subscription(actor.organizationId()).orElseThrow();
    }

    public SubscriptionView cancelPlanChange(
            CurrentActor actor, UUID subscriptionId, String correlationId
    ) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            List<PlanChangeCancellation> changes = jdbc.query(
                    """
                    SELECT plan_id, next_plan_id, current_period_end FROM subscriptions
                    WHERE id = ? AND organization_id = ? AND status = 'ACTIVE' AND next_plan_id IS NOT NULL
                    FOR UPDATE
                    """,
                    (result, row) -> new PlanChangeCancellation(
                            result.getObject("plan_id", UUID.class), result.getObject("next_plan_id", UUID.class),
                            result.getTimestamp("current_period_end").toInstant()
                    ), subscriptionId, actor.organizationId()
            );
            if (changes.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheduled plan change not found");
            }
            PlanChangeCancellation change = changes.getFirst();
            ensureRenewalNotStarted(subscriptionId, change.periodEnd());
            jdbc.update(
                    """
                    UPDATE subscriptions SET next_plan_id = NULL, plan_change_scheduled_at = NULL,
                        plan_change_scheduled_by = NULL, updated_at = ? WHERE id = ?
                    """,
                    Timestamp.from(now), subscriptionId
            );
            appendPlanChangeAuditAndEvent(
                    actor, subscriptionId, change.nextPlanId(), change.planId(), change.periodEnd(), correlationId, now,
                    "SUBSCRIPTION_PLAN_CHANGE_CANCELLED", "SubscriptionPlanChangeCancelled"
            );
        });
        return subscription(actor.organizationId()).orElseThrow();
    }

    private void ensureRenewalNotStarted(UUID subscriptionId, Instant periodEnd) {
        Long renewal = jdbc.queryForObject(
                """
                SELECT count(*) FROM renewal_attempts
                WHERE subscription_id = ? AND period_end = ?
                """,
                Long.class, subscriptionId, Timestamp.from(periodEnd)
        );
        if (renewal != null && renewal > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A renewal checkout already exists for this period"
            );
        }
    }

    private void appendPlanChangeAuditAndEvent(
            CurrentActor actor, UUID subscriptionId, UUID fromPlanId, UUID toPlanId,
            Instant effectiveAt, String correlationId, Instant now, String action, String eventType
    ) {
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, created_at
                ) VALUES (?, ?, ?, ?, 'Subscription', ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action,
                subscriptionId, correlationId, Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, ?, 1, 'Subscription', ?, ?,
                          jsonb_build_object('subscriptionId', CAST(? AS text),
                                             'fromPlanId', CAST(? AS text),
                                             'toPlanId', CAST(? AS text),
                                             'effectiveAt', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), eventType, subscriptionId, correlationId,
                subscriptionId, fromPlanId, toPlanId, effectiveAt.toString(),
                Timestamp.from(now), Timestamp.from(now)
        );
    }

    public Checkout checkout(CurrentActor actor, UUID planId, String idempotencyKey) {
        return checkout(actor, planId, null, idempotencyKey, false);
    }

    public Checkout checkout(CurrentActor actor, UUID planId, String promotionCode, String idempotencyKey) {
        return checkout(actor, planId, promotionCode, idempotencyKey, true);
    }

    private Checkout checkout(
            CurrentActor actor, UUID planId, String promotionCode, String idempotencyKey, boolean customerInitiated
    ) {
        PendingOrder pending = transactions.execute(status ->
                createOrLoadPending(actor, planId, promotionCode, idempotencyKey, customerInitiated)
        );
        if (pending == null) {
            throw new IllegalStateException("Could not create billing order");
        }
        if (pending.checkoutUrl() != null) {
            return pending.toCheckout();
        }
        if (pending.claimToken() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout is being prepared; retry shortly");
        }
        try {
            PaymentGateway.CheckoutLink link = gateway.createCheckout(
                    pending.orderCode(), pending.amountVnd(), "V2K " + pending.orderCode()
            );
            return transactions.execute(status -> persistCheckoutLink(pending, link));
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> jdbc.update(
                    """
                    UPDATE billing_orders SET checkout_claim_token = NULL, checkout_claim_expires_at = NULL,
                        updated_at = ? WHERE id = ? AND checkout_claim_token = ? AND checkout_url IS NULL
                    """,
                    Timestamp.from(clock.instant()), pending.id(), pending.claimToken()
            ));
            throw failure;
        }
    }

    private Checkout persistCheckoutLink(PendingOrder pending, PaymentGateway.CheckoutLink link) {
        int updated = jdbc.update(
                """
                UPDATE billing_orders
                SET provider_payment_link_id = ?, checkout_url = ?, checkout_claim_token = NULL,
                    checkout_claim_expires_at = NULL, updated_at = ?
                WHERE id = ? AND state = 'PENDING' AND checkout_url IS NULL AND checkout_claim_token = ?
                """,
                link.providerId(), link.checkoutUrl().toString(), Timestamp.from(clock.instant()),
                pending.id(), pending.claimToken()
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout claim was lost; retry shortly");
        }
        return new Checkout(
                pending.id(), pending.orderCode(), pending.amountVnd(), link.checkoutUrl().toString(), pending.expiresAt()
        );
    }

    private PendingOrder createOrLoadPending(
            CurrentActor actor, UUID planId, String promotionCode, String idempotencyKey, boolean customerInitiated
    ) {
        jdbc.queryForObject(
                "SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                String.class, actor.organizationId() + "|checkout|" + idempotencyKey
        );
        Plan plan = plans().stream().filter(candidate -> candidate.id().equals(planId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        if ("TOP_UP".equals(plan.productType()) && !hasTopUpEligibleSubscription(actor.organizationId())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Top-up requires a subscription active beyond the checkout window"
            );
        }
        if (customerInitiated && "SUBSCRIPTION".equals(plan.productType())
                && hasDifferentCurrentSubscription(actor.organizationId(), plan.id())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Use the end-of-period plan change action for an active subscription"
            );
        }
        String normalizedPromotion = promotionCode == null || promotionCode.isBlank()
                ? null : normalizePromotionCode(promotionCode);
        if (normalizedPromotion != null && "TOP_UP".equals(plan.productType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Promotions do not apply to top-ups");
        }
        String fingerprintSource = actor.organizationId() + "|" + plan.id()
                + (normalizedPromotion == null ? "" : "|promotion:" + normalizedPromotion);
        String fingerprint = RequestFingerprint.sha256(fingerprintSource);
        List<PendingOrder> existing = jdbc.query(
                """
                SELECT id, order_code, amount_vnd, checkout_url, expires_at, request_fingerprint,
                       checkout_claim_token
                FROM billing_orders WHERE organization_id = ? AND idempotency_key = ?
                """,
                BillingService::mapPending,
                actor.organizationId(), idempotencyKey
        );
        if (!existing.isEmpty()) {
            if (!fingerprint.equals(existing.getFirst().fingerprint())) {
                throw new IdempotencyConflictException();
            }
            PendingOrder found = existing.getFirst();
            if (found.checkoutUrl() != null) {
                return found;
            }
            UUID claimToken = UuidV7Generator.generate();
            int claimed = jdbc.update(
                    """
                    UPDATE billing_orders SET checkout_claim_token = ?, checkout_claim_expires_at = ?, updated_at = ?
                    WHERE id = ? AND checkout_url IS NULL
                      AND (checkout_claim_token IS NULL OR checkout_claim_expires_at <= ?)
                    """,
                    claimToken, Timestamp.from(clock.instant().plus(Duration.ofMinutes(2))),
                    Timestamp.from(clock.instant()), found.id(), Timestamp.from(clock.instant())
            );
            return claimed == 1 ? found.withClaim(claimToken) : found;
        }
        if (customerInitiated && requireProfileBeforeCheckout
                && billingProfileSnapshot(actor.organizationId()) == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Complete billing information before checkout"
            );
        }
        Promotion promotion = normalizedPromotion == null ? null : lockPromotion(
                actor.organizationId(), plan, normalizedPromotion
        );
        long listPrice = plan.amountVnd();
        long discount = promotion == null ? 0 : Math.multiplyExact(listPrice, promotion.discountBps()) / 10_000;
        if (promotion != null && discount < 1) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Promotion discount is too small");
        }
        long amount = listPrice - discount;
        Long orderCode = jdbc.queryForObject("SELECT nextval('billing_order_code_seq')", Long.class);
        UUID orderId = UuidV7Generator.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(payOs.checkoutTtl());
        jdbc.update(
                """
                INSERT INTO billing_orders(
                    id, organization_id, plan_id, order_code, amount_vnd, idempotency_key,
                    request_fingerprint, expires_at, created_by, checkout_claim_token,
                    checkout_claim_expires_at, created_at, updated_at, list_price_vnd,
                    discount_vnd, promotion_campaign_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                orderId, actor.organizationId(), plan.id(), orderCode, amount, idempotencyKey,
                fingerprint, Timestamp.from(expiresAt), actor.userId(), orderId,
                Timestamp.from(now.plus(Duration.ofMinutes(2))), Timestamp.from(now), Timestamp.from(now),
                listPrice, discount, promotion == null ? null : promotion.id()
        );
        if (promotion != null) {
            reservePromotion(promotion, actor.organizationId(), orderId, listPrice, discount, amount, now, expiresAt);
        }
        return new PendingOrder(orderId, orderCode, amount, null, expiresAt, fingerprint, orderId);
    }

    public Quote quote(UUID organizationId, UUID planId, String promotionCode) {
        Plan plan = plans().stream().filter(candidate -> candidate.id().equals(planId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        if (promotionCode == null || promotionCode.isBlank()) {
            return new Quote(plan.amountVnd(), 0, plan.amountVnd(), null, null);
        }
        if ("TOP_UP".equals(plan.productType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Promotions do not apply to top-ups");
        }
        Promotion promotion = findPromotion(normalizePromotionCode(promotionCode), plan, false);
        ensurePromotionAvailable(organizationId, promotion, clock.instant(), false);
        long discount = Math.multiplyExact(plan.amountVnd(), promotion.discountBps()) / 10_000;
        return new Quote(plan.amountVnd(), discount, plan.amountVnd() - discount,
                promotion.code(), promotion.attributionChannel());
    }

    private Promotion lockPromotion(UUID organizationId, Plan plan, String code) {
        Promotion promotion = findPromotion(code, plan, true);
        ensurePromotionAvailable(organizationId, promotion, clock.instant(), true);
        return promotion;
    }

    private String normalizePromotionCode(String promotionCode) {
        try {
            return PromotionService.normalizeCode(promotionCode);
        } catch (IllegalArgumentException invalidCode) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Promotion is invalid or unavailable", invalidCode
            );
        }
    }

    private Promotion findPromotion(String code, Plan plan, boolean lock) {
        List<Promotion> matches = jdbc.query(
                """
                SELECT id, code, discount_bps, attribution_channel, max_redemptions
                FROM promotion_campaigns
                WHERE code = ? AND active = TRUE AND starts_at <= ? AND ends_at > ?
                  AND (plan_code_prefix IS NULL OR left(?, length(plan_code_prefix)) = plan_code_prefix)
                """ + (lock ? " FOR UPDATE" : ""),
                (result, row) -> new Promotion(
                        result.getObject("id", UUID.class), result.getString("code"),
                        result.getInt("discount_bps"), result.getString("attribution_channel"),
                        result.getInt("max_redemptions")
                ),
                code, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), plan.code()
        );
        return matches.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Promotion is invalid or unavailable")
        );
    }

    private void ensurePromotionAvailable(
            UUID organizationId, Promotion promotion, Instant now, boolean releaseExpired
    ) {
        if (releaseExpired) {
            jdbc.update(
                    """
                    UPDATE promotion_redemptions SET state = 'RELEASED', released_at = ?
                    WHERE promotion_campaign_id = ? AND state = 'RESERVED' AND expires_at <= ?
                    """,
                    Timestamp.from(now), promotion.id(), Timestamp.from(now)
            );
        }
        List<String> organizationStates = jdbc.queryForList(
                """
                SELECT state FROM promotion_redemptions
                WHERE promotion_campaign_id = ? AND organization_id = ?
                  AND (state = 'REDEEMED' OR (state = 'RESERVED' AND expires_at > ?))
                """,
                String.class, promotion.id(), organizationId, Timestamp.from(now)
        );
        if (!organizationStates.isEmpty() && !"RELEASED".equals(organizationStates.getFirst())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Promotion can only be redeemed once per organization"
            );
        }
        Long occupied = jdbc.queryForObject(
                """
                SELECT count(*) FROM promotion_redemptions
                WHERE promotion_campaign_id = ?
                  AND (state = 'REDEEMED' OR (state = 'RESERVED' AND expires_at > ?))
                """,
                Long.class, promotion.id(), Timestamp.from(now)
        );
        if (occupied != null && occupied >= promotion.maxRedemptions()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Promotion capacity is exhausted");
        }
    }

    private void reservePromotion(
            Promotion promotion, UUID organizationId, UUID orderId,
            long listPrice, long discount, long amount, Instant now, Instant expiresAt
    ) {
        int reused = jdbc.update(
                """
                UPDATE promotion_redemptions SET billing_order_id = ?, list_price_vnd = ?, discount_vnd = ?,
                    amount_vnd = ?, state = 'RESERVED', reserved_at = ?, expires_at = ?, released_at = NULL
                WHERE promotion_campaign_id = ? AND organization_id = ? AND state = 'RELEASED'
                """,
                orderId, listPrice, discount, amount, Timestamp.from(now), Timestamp.from(expiresAt),
                promotion.id(), organizationId
        );
        if (reused == 0) {
            jdbc.update(
                    """
                    INSERT INTO promotion_redemptions(
                        id, promotion_campaign_id, organization_id, billing_order_id,
                        list_price_vnd, discount_vnd, amount_vnd, reserved_at, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UuidV7Generator.generate(), promotion.id(), organizationId, orderId,
                    listPrice, discount, amount, Timestamp.from(now), Timestamp.from(expiresAt)
            );
        }
    }

    private boolean hasTopUpEligibleSubscription(UUID organizationId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*) FROM subscriptions
                WHERE organization_id = ? AND status = 'ACTIVE' AND cancel_at_period_end = FALSE
                  AND current_period_end > ?
                """,
                Long.class, organizationId, Timestamp.from(clock.instant().plus(payOs.checkoutTtl()))
        );
        return count != null && count > 0;
    }

    private boolean hasDifferentCurrentSubscription(UUID organizationId, UUID planId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*) FROM subscriptions
                WHERE organization_id = ? AND status IN ('ACTIVE', 'PAST_DUE') AND plan_id <> ?
                """,
                Long.class, organizationId, planId
        );
        return count != null && count > 0;
    }

    public void processWebhook(JsonNode envelope) {
        if (!payOs.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment webhook is not enabled");
        }
        JsonNode data = envelope.path("data");
        String signature = envelope.path("signature").asText();
        if (!PayOsSignature.verifyWebhook(data, signature, payOs.checksumKey())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook signature");
        }
        long orderCode = data.path("orderCode").asLong(-1);
        long amount = data.path("amount").asLong(-1);
        String currency = data.path("currency").asText();
        String reference = data.path("reference").asText();
        String paymentLinkId = data.path("paymentLinkId").asText();
        if (orderCode <= 0 || amount <= 0 || reference.isBlank() || !"VND".equals(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment data");
        }
        String eventKey = reference + "|" + orderCode;
        boolean alreadyProcessed = Boolean.TRUE.equals(transactions.execute(status ->
                saveInbox(envelope, signature, eventKey)
        ));
        if (alreadyProcessed) {
            return;
        }
        if (!envelope.path("success").asBoolean(false) || !"00".equals(data.path("code").asText())) {
            transactions.executeWithoutResult(status -> markWebhookProcessed(eventKey, clock.instant(), "IGNORED"));
            return;
        }
        try {
            transactions.executeWithoutResult(status -> applyPayment(
                    eventKey, orderCode, amount, reference, paymentLinkId
            ));
        } catch (ResponseStatusException mismatch) {
            String errorCode = "REJECTED_" + mismatch.getStatusCode().value();
            transactions.executeWithoutResult(status ->
                    markWebhookProcessed(eventKey, clock.instant(), errorCode)
            );
            log.warn("Signed payOS webhook was retained but not applied. eventKey={}, reason={}",
                    eventKey, mismatch.getReason());
        }
    }

    private boolean saveInbox(JsonNode envelope, String signature, String eventKey) {
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO payment_webhook_inbox(
                    id, provider, event_key, signature, payload_json, received_at
                ) VALUES (?, 'PAYOS', ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (provider, event_key) DO NOTHING
                """,
                UuidV7Generator.generate(), eventKey, signature, envelope.toString(), Timestamp.from(now)
        );
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT processed_at IS NOT NULL FROM payment_webhook_inbox
                WHERE provider = 'PAYOS' AND event_key = ?
                """,
                Boolean.class, eventKey
        ));
    }

    private void applyPayment(
            String eventKey,
            long orderCode,
            long amount,
            String reference,
            String paymentLinkId
    ) {
        Instant now = clock.instant();
        OrderForPayment order = lockOrder(orderCode);
        if (order.amountVnd() != amount
                || (order.paymentLinkId() != null && !order.paymentLinkId().equals(paymentLinkId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment does not match billing order");
        }
        if ("PAID".equals(order.state())) {
            markWebhookProcessed(eventKey, now, null);
            return;
        }
        if (!"PENDING".equals(order.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Billing order is not payable");
        }
        jdbc.update(
                """
                INSERT INTO payments(
                    id, organization_id, billing_order_id, provider, provider_reference,
                    amount_vnd, currency, state, received_at
                ) VALUES (?, ?, ?, 'PAYOS', ?, ?, 'VND', 'PAID', ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), order.id(), reference, amount, Timestamp.from(now)
        );
        settleOrder(order, now, eventKey);
        markWebhookProcessed(eventKey, now, null);
    }

    private OrderForPayment lockOrder(long orderCode) {
        return jdbc.query(
                """
                SELECT o.id, o.organization_id, o.plan_id,
                       COALESCE(o.list_price_vnd, o.amount_vnd) AS list_price_vnd, o.discount_vnd,
                       o.amount_vnd, o.promotion_campaign_id, o.state,
                       o.provider_payment_link_id, p.billing_interval, p.product_type,
                       p.processed_video_seconds, p.qa_queries
                FROM billing_orders o JOIN pricing_plans p ON p.id = o.plan_id
                WHERE o.order_code = ? FOR UPDATE OF o
                """,
                (result, row) -> new OrderForPayment(
                        result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                        result.getObject("plan_id", UUID.class), result.getLong("list_price_vnd"),
                        result.getLong("discount_vnd"), result.getLong("amount_vnd"),
                        result.getObject("promotion_campaign_id", UUID.class),
                        result.getString("state"), result.getString("provider_payment_link_id"),
                        result.getString("billing_interval"), result.getString("product_type"),
                        result.getLong("processed_video_seconds"),
                        result.getLong("qa_queries")
                ),
                orderCode
        ).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown billing order")
        );
    }

    private void settleOrder(OrderForPayment order, Instant now, String settlementKey) {
        jdbc.update(
                "UPDATE billing_orders SET state = 'PAID', paid_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), order.id()
        );
        jdbc.update(
                """
                UPDATE promotion_redemptions SET state = 'REDEEMED', redeemed_at = ?
                WHERE billing_order_id = ? AND state = 'RESERVED'
                """,
                Timestamp.from(now), order.id()
        );
        expireElapsedSubscriptions(now);
        if ("TOP_UP".equals(order.productType())) {
            settleTopUp(order, now, settlementKey);
            return;
        }
        SubscriptionPeriod subscription = allocateSubscription(order, now);
        jdbc.update(
                """
                INSERT INTO entitlements(
                    id, organization_id, subscription_id, metric, allowance,
                    period_start, period_end, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), subscription.subscriptionId(),
                UsageMetric.PROCESSED_VIDEO_SECOND.name(), order.processedSeconds(),
                Timestamp.from(subscription.periodStart()), Timestamp.from(subscription.periodEnd()),
                Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO entitlements(
                    id, organization_id, subscription_id, metric, allowance,
                    period_start, period_end, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), subscription.subscriptionId(),
                UsageMetric.QA_QUERY.name(), order.qaQueries(),
                Timestamp.from(subscription.periodStart()), Timestamp.from(subscription.periodEnd()),
                Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO subscription_billing_periods(
                    id, organization_id, subscription_id, billing_order_id,
                    period_start, period_end, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), subscription.subscriptionId(), order.id(),
                Timestamp.from(subscription.periodStart()), Timestamp.from(subscription.periodEnd()), Timestamp.from(now)
        );
        completeInvoiceAndNotify(order, subscription.subscriptionId(), "SUBSCRIPTION", now);
        emitPaymentReceived(order, now, settlementKey);
    }

    private void settleTopUp(OrderForPayment order, Instant now, String settlementKey) {
        UUID videoEntitlement = grantCredit(
                order, UsageMetric.PROCESSED_VIDEO_SECOND, order.processedSeconds(), now, settlementKey
        );
        UUID qaEntitlement = grantCredit(order, UsageMetric.QA_QUERY, order.qaQueries(), now, settlementKey);
        if ((order.processedSeconds() > 0 && videoEntitlement == null)
                || (order.qaQueries() > 0 && qaEntitlement == null)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Top-up requires an active subscription");
        }
        completeInvoiceAndNotify(order, null, "TOP_UP", now);
        emitPaymentReceived(order, now, settlementKey);
    }

    private UUID grantCredit(
            OrderForPayment order, UsageMetric metric, long units, Instant now, String settlementKey
    ) {
        if (units <= 0) {
            return null;
        }
        List<UUID> entitlementIds = jdbc.query(
                """
                SELECT id FROM entitlements
                WHERE organization_id = ? AND metric = ? AND period_start <= ? AND period_end > ?
                ORDER BY period_start DESC LIMIT 1 FOR UPDATE
                """,
                (result, row) -> result.getObject("id", UUID.class), order.organizationId(), metric.name(),
                Timestamp.from(now), Timestamp.from(now)
        );
        if (entitlementIds.isEmpty()) {
            return null;
        }
        UUID entitlementId = entitlementIds.getFirst();
        jdbc.update("UPDATE entitlements SET allowance = allowance + ?, updated_at = ? WHERE id = ?",
                units, Timestamp.from(now), entitlementId);
        jdbc.update(
                """
                INSERT INTO credit_grants(
                    id, organization_id, billing_order_id, entitlement_id, metric,
                    granted_units, granted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), order.id(), entitlementId,
                metric.name(), units, Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO usage_ledger(
                    id, organization_id, entitlement_id, event_type, units, correlation_id, occurred_at
                ) VALUES (?, ?, ?, 'ADJUSTED', ?, ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), entitlementId, units,
                "topup-" + RequestFingerprint.sha256(settlementKey).substring(0, 24), Timestamp.from(now)
        );
        return entitlementId;
    }

    private void completeInvoiceAndNotify(
            OrderForPayment order, UUID subscriptionId, String invoiceType, Instant now
    ) {
        List<InvoiceIdentity> existing = jdbc.query(
                "SELECT id, invoice_number FROM invoices WHERE billing_order_id = ? FOR UPDATE",
                (result, row) -> new InvoiceIdentity(
                        result.getObject("id", UUID.class), result.getString("invoice_number")
                ), order.id()
        );
        UUID invoiceId;
        String invoiceNumber;
        if (existing.isEmpty()) {
            Long invoiceSequence = jdbc.queryForObject("SELECT nextval('invoice_number_seq')", Long.class);
            invoiceId = UuidV7Generator.generate();
            invoiceNumber = "V2K-" + invoiceSequence;
            BillingProfileSnapshot buyer = billingProfileSnapshot(order.organizationId());
            jdbc.update(
                    """
                    INSERT INTO invoices(
                        id, organization_id, subscription_id, billing_order_id, invoice_number,
                        state, amount_due_vnd, amount_paid_vnd, due_at, paid_at, created_at, updated_at,
                        invoice_type, list_price_vnd, discount_vnd, promotion_campaign_id,
                        buyer_type_snapshot, buyer_legal_name_snapshot, buyer_tax_identifier_snapshot,
                        buyer_address_snapshot, buyer_email_snapshot, buyer_country_code_snapshot,
                        billing_profile_version, tax_document_requested
                    ) VALUES (?, ?, ?, ?, ?, 'PAID', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    invoiceId, order.organizationId(), subscriptionId, order.id(),
                    invoiceNumber, order.amountVnd(), order.amountVnd(), Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), invoiceType,
                    order.listPriceVnd(), order.discountVnd(), order.promotionCampaignId(),
                    buyer == null ? null : buyer.buyerType(), buyer == null ? null : buyer.legalName(),
                    buyer == null ? null : buyer.taxIdentifier(), buyer == null ? null : buyer.address(),
                    buyer == null ? null : buyer.email(), buyer == null ? null : buyer.countryCode(),
                    buyer == null ? null : buyer.version(), buyer != null && buyer.invoiceRequested()
            );
        } else {
            invoiceId = existing.getFirst().id();
            invoiceNumber = existing.getFirst().number();
            jdbc.update(
                    """
                    UPDATE invoices SET state = 'PAID', amount_paid_vnd = amount_due_vnd,
                        paid_at = ?, updated_at = ? WHERE id = ?
                    """,
                    Timestamp.from(now), Timestamp.from(now), invoiceId
            );
            jdbc.update(
                    "UPDATE renewal_attempts SET state = 'PAID', updated_at = ? WHERE billing_order_id = ?",
                    Timestamp.from(now), order.id()
            );
        }
        notifications.paymentReceipt(order.organizationId(), invoiceId, invoiceNumber, order.amountVnd(), now);
    }

    private void emitPaymentReceived(OrderForPayment order, Instant now, String settlementKey) {
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'PaymentReceived', 1, 'BillingOrder', ?, ?,
                          jsonb_build_object('billingOrderId', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), order.id(),
                "payos-" + RequestFingerprint.sha256(settlementKey).substring(0, 24),
                order.id(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private SubscriptionPeriod allocateSubscription(OrderForPayment order, Instant now) {
        List<SubscriptionCandidate> candidates = jdbc.query(
                """
                SELECT id, plan_id, status, current_period_end
                FROM subscriptions
                WHERE organization_id = ? AND status IN ('ACTIVE', 'PAST_DUE', 'SCHEDULED')
                ORDER BY current_period_end DESC LIMIT 1 FOR UPDATE
                """,
                (result, row) -> new SubscriptionCandidate(
                        result.getObject("id", UUID.class), result.getObject("plan_id", UUID.class),
                        result.getString("status"), result.getTimestamp("current_period_end").toInstant()
                ),
                order.organizationId()
        );
        SubscriptionCandidate current = candidates.stream().findFirst().orElse(null);
        Instant start = current == null || current.periodEnd().isBefore(now) ? now : current.periodEnd();
        Instant end = addInterval(start, order.interval());
        if (current != null && current.planId().equals(order.planId())) {
            String status = start.isAfter(now) && "SCHEDULED".equals(current.status()) ? "SCHEDULED" : "ACTIVE";
            jdbc.update(
                    """
                    UPDATE subscriptions SET status = ?, current_period_end = ?,
                        cancel_at_period_end = FALSE, cancelled_at = NULL, updated_at = ?
                    WHERE id = ?
                    """,
                    status, Timestamp.from(end), Timestamp.from(now), current.id()
            );
            return new SubscriptionPeriod(current.id(), start, end);
        }
        if (current != null && !current.periodEnd().isBefore(now)) {
            jdbc.update(
                    """
                    UPDATE subscriptions SET cancel_at_period_end = TRUE, cancelled_at = ?,
                        next_plan_id = NULL, plan_change_scheduled_at = NULL,
                        plan_change_scheduled_by = NULL, updated_at = ? WHERE id = ?
                    """,
                    Timestamp.from(now), Timestamp.from(now), current.id()
            );
        }
        String status = start.isAfter(now) ? "SCHEDULED" : "ACTIVE";
        UUID subscriptionId = UuidV7Generator.generate();
        jdbc.update(
                """
                INSERT INTO subscriptions(
                    id, organization_id, plan_id, billing_order_id, status,
                    current_period_start, current_period_end, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                subscriptionId, order.organizationId(), order.planId(), order.id(), status,
                Timestamp.from(start), Timestamp.from(end), Timestamp.from(now), Timestamp.from(now)
        );
        return new SubscriptionPeriod(subscriptionId, start, end);
    }

    private static Instant addInterval(Instant start, String interval) {
        return switch (interval) {
            case "YEAR" -> start.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
            case "MONTH" -> start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
            default -> start.plus(Duration.ofDays(30));
        };
    }

    private int expireElapsedSubscriptions(Instant now) {
        int changed = jdbc.update(
                """
                UPDATE subscriptions SET status = 'EXPIRED', updated_at = ?
                WHERE ((status = 'ACTIVE' AND cancel_at_period_end = TRUE)
                    OR status = 'SCHEDULED') AND current_period_end <= ?
                """,
                Timestamp.from(now), Timestamp.from(now)
        );
        changed += jdbc.update(
                """
                UPDATE subscriptions SET status = 'PAST_DUE', updated_at = ?
                WHERE status = 'ACTIVE' AND cancel_at_period_end = FALSE AND current_period_end <= ?
                """,
                Timestamp.from(now), Timestamp.from(now)
        );
        changed += jdbc.update(
                """
                UPDATE subscriptions SET status = 'EXPIRED', updated_at = ?
                WHERE status = 'PAST_DUE' AND current_period_end + INTERVAL '7 days' <= ?
                """,
                Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                """
                UPDATE renewal_attempts ra SET state = 'EXPIRED', updated_at = ?
                WHERE ra.state = 'AWAITING_PAYMENT' AND ra.period_end + INTERVAL '7 days' <= ?
                """,
                Timestamp.from(now), Timestamp.from(now)
        );
        return changed;
    }

    private void markWebhookProcessed(String eventKey, Instant now, String errorCode) {
        jdbc.update(
                """
                UPDATE payment_webhook_inbox SET processed_at = ?, error_code = ?
                WHERE provider = 'PAYOS' AND event_key = ?
                """,
                Timestamp.from(now), errorCode, eventKey
        );
    }

    public ReconciliationResult reconcilePendingPayments() {
        if (!payOs.enabled()) {
            return new ReconciliationResult(0, 0, 0, 0, 0, 0);
        }
        List<ReconciliationCandidate> candidates = jdbc.query(
                """
                SELECT order_code, amount_vnd, provider_payment_link_id
                FROM billing_orders
                WHERE state = 'PENDING' AND provider_payment_link_id IS NOT NULL
                ORDER BY updated_at LIMIT 50
                """,
                (result, row) -> new ReconciliationCandidate(
                        result.getLong("order_code"), result.getLong("amount_vnd"),
                        result.getString("provider_payment_link_id")
                )
        );
        int paid = 0;
        int terminal = 0;
        int pending = 0;
        int failed = 0;
        for (ReconciliationCandidate candidate : candidates) {
            try {
                Optional<PaymentGateway.PaymentStatus> found = gateway.getPayment(candidate.orderCode());
                if (found.isEmpty()) {
                    pending++;
                    continue;
                }
                PaymentGateway.PaymentStatus payment = found.get();
                if (payment.orderCode() != candidate.orderCode()
                        || payment.amountVnd() != candidate.amountVnd()
                        || !payment.providerId().equals(candidate.paymentLinkId())) {
                    failed++;
                    log.error("PAYOS_RECONCILIATION_MISMATCH provider=payos orderCode={}", candidate.orderCode());
                    continue;
                }
                if ("PAID".equals(payment.status()) && payment.amountPaidVnd() == candidate.amountVnd()) {
                    transactions.executeWithoutResult(status -> settleReconciled(candidate, payment));
                    paid++;
                } else if ("CANCELLED".equals(payment.status()) || "EXPIRED".equals(payment.status())) {
                    jdbc.update(
                            "UPDATE billing_orders SET state = ?, updated_at = ? WHERE order_code = ? AND state = 'PENDING'",
                            payment.status(), Timestamp.from(clock.instant()), candidate.orderCode()
                    );
                    jdbc.update(
                            """
                            UPDATE promotion_redemptions SET state = 'RELEASED', released_at = ?
                            WHERE billing_order_id = (SELECT id FROM billing_orders WHERE order_code = ?)
                              AND state = 'RESERVED'
                            """,
                            Timestamp.from(clock.instant()), candidate.orderCode()
                    );
                    terminal++;
                } else {
                    pending++;
                }
            } catch (RuntimeException providerFailure) {
                failed++;
                log.warn("Could not reconcile payOS order. orderCode={}, errorType={}",
                        candidate.orderCode(), providerFailure.getClass().getSimpleName());
            }
        }
        int advanced = transactions.execute(status -> advanceSubscriptions(clock.instant()));
        int renewalsPrepared = prepareRenewals();
        return new ReconciliationResult(candidates.size(), paid, terminal, failed, advanced, renewalsPrepared);
    }

    private int prepareRenewals() {
        Instant now = clock.instant();
        List<RenewalCandidate> candidates = jdbc.query(
                """
                SELECT s.id, s.organization_id, COALESCE(s.next_plan_id, s.plan_id) AS renewal_plan_id,
                       s.current_period_end, m.user_id
                FROM subscriptions s
                JOIN LATERAL (
                    SELECT user_id FROM memberships
                    WHERE organization_id = s.organization_id AND role = 'OWNER' AND status = 'ACTIVE'
                    ORDER BY joined_at LIMIT 1
                ) m ON TRUE
                WHERE s.status = 'ACTIVE' AND s.cancel_at_period_end = FALSE
                  AND s.current_period_end > ? AND s.current_period_end <= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM renewal_attempts r
                      WHERE r.subscription_id = s.id AND r.period_end = s.current_period_end
                  )
                ORDER BY s.current_period_end LIMIT 25
                """,
                (result, row) -> new RenewalCandidate(
                        result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                        result.getObject("renewal_plan_id", UUID.class), result.getObject("user_id", UUID.class),
                        result.getTimestamp("current_period_end").toInstant()
                ), Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(7)))
        );
        int prepared = 0;
        for (RenewalCandidate candidate : candidates) {
            try {
                CurrentActor owner = new CurrentActor(
                        candidate.ownerId(), candidate.organizationId(), CurrentActor.Role.OWNER
                );
                String key = "renewal|" + candidate.subscriptionId() + "|" + candidate.periodEnd();
                Checkout checkout = checkout(owner, candidate.planId(), key);
                boolean inserted = Boolean.TRUE.equals(transactions.execute(status ->
                        persistRenewalAttempt(candidate, checkout, clock.instant())
                ));
                if (inserted) {
                    prepared++;
                }
            } catch (RuntimeException failure) {
                log.warn("Could not prepare subscription renewal. subscriptionId={}, errorType={}",
                        candidate.subscriptionId(), failure.getClass().getSimpleName());
            }
        }
        return prepared;
    }

    private boolean persistRenewalAttempt(RenewalCandidate candidate, Checkout checkout, Instant now) {
        Long existing = jdbc.queryForObject(
                "SELECT count(*) FROM renewal_attempts WHERE subscription_id = ? AND period_end = ?",
                Long.class, candidate.subscriptionId(), Timestamp.from(candidate.periodEnd())
        );
        if (existing != null && existing > 0) {
            return false;
        }
        Long invoiceSequence = jdbc.queryForObject("SELECT nextval('invoice_number_seq')", Long.class);
        UUID invoiceId = UuidV7Generator.generate();
        String invoiceNumber = "V2K-" + invoiceSequence;
        BillingProfileSnapshot buyer = billingProfileSnapshot(candidate.organizationId());
        jdbc.update(
                """
                INSERT INTO invoices(
                    id, organization_id, subscription_id, billing_order_id, invoice_number,
                    state, amount_due_vnd, amount_paid_vnd, due_at, created_at, updated_at,
                    invoice_type, list_price_vnd, discount_vnd,
                    buyer_type_snapshot, buyer_legal_name_snapshot, buyer_tax_identifier_snapshot,
                    buyer_address_snapshot, buyer_email_snapshot, buyer_country_code_snapshot,
                    billing_profile_version, tax_document_requested
                ) VALUES (?, ?, ?, ?, ?, 'OPEN', ?, 0, ?, ?, ?, 'SUBSCRIPTION', ?, 0,
                    ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                invoiceId, candidate.organizationId(), candidate.subscriptionId(), checkout.orderId(),
                invoiceNumber, checkout.amountVnd(), Timestamp.from(candidate.periodEnd()),
                Timestamp.from(now), Timestamp.from(now), checkout.amountVnd(),
                buyer == null ? null : buyer.buyerType(), buyer == null ? null : buyer.legalName(),
                buyer == null ? null : buyer.taxIdentifier(), buyer == null ? null : buyer.address(),
                buyer == null ? null : buyer.email(), buyer == null ? null : buyer.countryCode(),
                buyer == null ? null : buyer.version(), buyer != null && buyer.invoiceRequested()
        );
        jdbc.update(
                """
                INSERT INTO renewal_attempts(
                    id, organization_id, subscription_id, billing_order_id, invoice_id,
                    period_end, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), candidate.organizationId(), candidate.subscriptionId(),
                checkout.orderId(), invoiceId, Timestamp.from(candidate.periodEnd()),
                Timestamp.from(now), Timestamp.from(now)
        );
        notifications.renewalPaymentRequired(
                candidate.organizationId(), invoiceId, invoiceNumber, checkout.amountVnd(),
                checkout.checkoutUrl(), candidate.periodEnd()
        );
        return true;
    }

    private void settleReconciled(
            ReconciliationCandidate candidate,
            PaymentGateway.PaymentStatus payment
    ) {
        OrderForPayment order = lockOrder(candidate.orderCode());
        if ("PAID".equals(order.state())) {
            return;
        }
        if (!"PENDING".equals(order.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Billing order is not payable");
        }
        Instant now = clock.instant();
        int inserted = jdbc.update(
                """
                INSERT INTO payments(
                    id, organization_id, billing_order_id, provider, provider_reference,
                    amount_vnd, currency, state, received_at
                ) VALUES (?, ?, ?, 'PAYOS', ?, ?, 'VND', 'PAID', ?)
                ON CONFLICT (provider, provider_reference) DO NOTHING
                """,
                UuidV7Generator.generate(), order.organizationId(), order.id(),
                "RECONCILED:" + payment.providerId(), candidate.amountVnd(), Timestamp.from(now)
        );
        if (inserted != 1) {
            throw new IllegalStateException("Reconciled payment reference was already used");
        }
        settleOrder(order, now, "reconcile|" + candidate.orderCode());
    }

    private int advanceSubscriptions(Instant now) {
        int changed = expireElapsedSubscriptions(now);
        changed += jdbc.update(
                """
                UPDATE subscriptions candidate
                SET status = 'ACTIVE', updated_at = ?
                WHERE candidate.id IN (
                    SELECT DISTINCT ON (scheduled.organization_id) scheduled.id
                    FROM subscriptions scheduled
                    WHERE scheduled.status = 'SCHEDULED'
                      AND scheduled.current_period_start <= ?
                      AND scheduled.current_period_end > ?
                      AND NOT EXISTS (
                          SELECT 1 FROM subscriptions active
                          WHERE active.organization_id = scheduled.organization_id
                            AND active.status IN ('ACTIVE', 'PAST_DUE')
                      )
                    ORDER BY scheduled.organization_id, scheduled.current_period_start
                )
                """,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
        return changed;
    }

    public List<RefundView> refunds(UUID organizationId) {
        return jdbc.query(
                """
                SELECT r.id, r.invoice_id, r.amount_vnd, r.reason, r.state,
                       r.provider_reference, r.requested_at, r.resolved_at
                FROM refund_requests r
                WHERE r.organization_id = ?
                ORDER BY r.requested_at DESC, r.id DESC
                """,
                (result, row) -> new RefundView(
                        result.getObject("id", UUID.class), result.getObject("invoice_id", UUID.class),
                        result.getLong("amount_vnd"), result.getString("reason"), result.getString("state"),
                        result.getString("provider_reference"), result.getTimestamp("requested_at").toInstant(),
                        result.getTimestamp("resolved_at") == null
                                ? null : result.getTimestamp("resolved_at").toInstant()
                ),
                organizationId
        );
    }

    public RefundView requestRefund(CurrentActor actor, UUID invoiceId, String reason, String correlationId) {
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < 10 || normalizedReason.length() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund reason must be 10-1000 characters");
        }
        return transactions.execute(status -> {
            List<RefundSource> sources = jdbc.query(
                    """
                    SELECT i.id, i.billing_order_id, i.amount_paid_vnd, i.state, i.invoice_type, p.id AS payment_id
                    FROM invoices i JOIN payments p ON p.billing_order_id = i.billing_order_id
                    WHERE i.id = ? AND i.organization_id = ?
                    FOR UPDATE OF i, p
                    """,
                    (result, row) -> new RefundSource(
                            result.getObject("id", UUID.class), result.getObject("billing_order_id", UUID.class),
                            result.getObject("payment_id", UUID.class), result.getLong("amount_paid_vnd"),
                            result.getString("state"), result.getString("invoice_type")
                    ),
                    invoiceId, actor.organizationId()
            );
            if (sources.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Paid invoice not found");
            }
            RefundSource source = sources.getFirst();
            if (!"TOP_UP".equals(source.invoiceType()) || !"PAID".equals(source.state())) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Self-service refund is limited to unused top-up purchases"
                );
            }
            List<RefundView> existing = refundByInvoice(invoiceId);
            if (!existing.isEmpty()) {
                return existing.getFirst();
            }
            assertCreditsCanBeRevoked(source.orderId());
            Instant now = clock.instant();
            UUID refundId = UuidV7Generator.generate();
            jdbc.update(
                    """
                    INSERT INTO refund_requests(
                        id, organization_id, payment_id, invoice_id, amount_vnd, reason,
                        requested_by, requested_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    refundId, actor.organizationId(), source.paymentId(), invoiceId, source.amountVnd(),
                    normalizedReason, actor.userId(), Timestamp.from(now)
            );
            appendBillingAudit(actor.organizationId(), actor.userId(), "REFUND_REQUESTED", refundId,
                    correlationId, now);
            return refundByInvoice(invoiceId).getFirst();
        });
    }

    public RefundView resolveRefund(
            UUID refundId, boolean approved, String providerReference, String resolutionReason
    ) {
        String reference = providerReference == null ? "" : providerReference.trim();
        String rejection = resolutionReason == null ? "" : resolutionReason.trim();
        if (approved && (reference.length() < 3 || reference.length() > 200)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provider refund reference is required");
        }
        if (!approved && (rejection.length() < 3 || rejection.length() > 1000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rejection reason is required");
        }
        return transactions.execute(status -> {
            RefundResolution source = jdbc.query(
                    """
                    SELECT r.id, r.organization_id, r.invoice_id, r.payment_id, r.state,
                           r.provider_reference, i.billing_order_id
                    FROM refund_requests r JOIN invoices i ON i.id = r.invoice_id
                    WHERE r.id = ? FOR UPDATE OF r, i
                    """,
                    (result, row) -> new RefundResolution(
                            result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                            result.getObject("invoice_id", UUID.class), result.getObject("payment_id", UUID.class),
                            result.getObject("billing_order_id", UUID.class), result.getString("state"),
                            result.getString("provider_reference")
                    ), refundId
            ).stream().findFirst().orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund request not found")
            );
            if (!"REQUESTED".equals(source.state())) {
                if (approved && "SUCCEEDED".equals(source.state()) && reference.equals(source.providerReference())) {
                    return refundByInvoice(source.invoiceId()).getFirst();
                }
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund request is already resolved");
            }
            Instant now = clock.instant();
            if (!approved) {
                jdbc.update(
                        "UPDATE refund_requests SET state = 'REJECTED', resolution_reason = ?, resolved_at = ? WHERE id = ?",
                        rejection, Timestamp.from(now), refundId
                );
                appendBillingAudit(source.organizationId(), null, "REFUND_REJECTED", refundId,
                        "refund-rejected-" + refundId, now);
                return refundByInvoice(source.invoiceId()).getFirst();
            }
            revokeCredits(source, now);
            jdbc.update(
                    "UPDATE refund_requests SET state = 'SUCCEEDED', provider_reference = ?, resolved_at = ? WHERE id = ?",
                    reference, Timestamp.from(now), refundId
            );
            jdbc.update("UPDATE invoices SET state = 'REFUNDED', amount_paid_vnd = 0, updated_at = ? WHERE id = ?",
                    Timestamp.from(now), source.invoiceId());
            jdbc.update("UPDATE payments SET state = 'REFUNDED' WHERE id = ?", source.paymentId());
            jdbc.update("UPDATE billing_orders SET state = 'REFUNDED', updated_at = ? WHERE id = ?",
                    Timestamp.from(now), source.orderId());
            appendBillingAudit(source.organizationId(), null, "REFUND_CONFIRMED", refundId,
                    "refund-confirmed-" + RequestFingerprint.sha256(reference).substring(0, 24), now);
            return refundByInvoice(source.invoiceId()).getFirst();
        });
    }

    private void revokeCredits(RefundResolution refund, Instant now) {
        List<CreditGrant> grants = jdbc.query(
                """
                SELECT id, entitlement_id, metric, granted_units
                FROM credit_grants WHERE billing_order_id = ? AND state = 'ACTIVE'
                ORDER BY metric FOR UPDATE
                """,
                (result, row) -> new CreditGrant(
                        result.getObject("id", UUID.class), result.getObject("entitlement_id", UUID.class),
                        result.getString("metric"), result.getLong("granted_units")
                ), refund.orderId()
        );
        if (grants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Top-up credits are no longer refundable");
        }
        for (CreditGrant grant : grants) {
            Long available = jdbc.queryForObject(
                    """
                    SELECT e.allowance
                           - COALESCE((SELECT sum(r.committed_units) FROM usage_reservations r
                               WHERE r.entitlement_id = e.id AND r.status = 'COMMITTED'), 0)
                           - COALESCE((SELECT sum(r.reserved_units) FROM usage_reservations r
                               WHERE r.entitlement_id = e.id AND r.status = 'RESERVED' AND r.expires_at > ?), 0)
                    FROM entitlements e WHERE e.id = ? FOR UPDATE
                    """,
                    Long.class, Timestamp.from(now), grant.entitlementId()
            );
            if (available == null || available < grant.units()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Top-up credits have been used and cannot be refunded"
                );
            }
            jdbc.update("UPDATE entitlements SET allowance = allowance - ?, updated_at = ? WHERE id = ?",
                    grant.units(), Timestamp.from(now), grant.entitlementId());
            jdbc.update("UPDATE credit_grants SET state = 'REVOKED', revoked_at = ? WHERE id = ?",
                    Timestamp.from(now), grant.id());
            jdbc.update(
                    """
                    INSERT INTO billing_adjustments(
                        id, organization_id, refund_request_id, entitlement_id, metric,
                        units_delta, reason, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'UNUSED_TOP_UP_REFUND', ?)
                    """,
                    UuidV7Generator.generate(), refund.organizationId(), refund.id(), grant.entitlementId(),
                    grant.metric(), -grant.units(), Timestamp.from(now)
            );
            jdbc.update(
                    """
                    INSERT INTO usage_ledger(
                        id, organization_id, entitlement_id, event_type, units, correlation_id, occurred_at
                    ) VALUES (?, ?, ?, 'ADJUSTED', ?, ?, ?)
                    """,
                    UuidV7Generator.generate(), refund.organizationId(), grant.entitlementId(), grant.units(),
                    "refund-" + refund.id(), Timestamp.from(now)
            );
        }
    }

    private void assertCreditsCanBeRevoked(UUID orderId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM credit_grants WHERE billing_order_id = ? AND state = 'ACTIVE'",
                Long.class, orderId
        );
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Top-up credits are no longer refundable");
        }
    }

    private List<RefundView> refundByInvoice(UUID invoiceId) {
        return jdbc.query(
                """
                SELECT id, invoice_id, amount_vnd, reason, state, provider_reference, requested_at, resolved_at
                FROM refund_requests WHERE invoice_id = ?
                """,
                (result, row) -> new RefundView(
                        result.getObject("id", UUID.class), result.getObject("invoice_id", UUID.class),
                        result.getLong("amount_vnd"), result.getString("reason"), result.getString("state"),
                        result.getString("provider_reference"), result.getTimestamp("requested_at").toInstant(),
                        result.getTimestamp("resolved_at") == null
                                ? null : result.getTimestamp("resolved_at").toInstant()
                ), invoiceId
        );
    }

    private void appendBillingAudit(
            UUID organizationId, UUID actorId, String action, UUID resourceId, String correlationId, Instant now
    ) {
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, created_at
                ) VALUES (?, ?, ?, ?, 'RefundRequest', ?, ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, actorId, action, resourceId, correlationId,
                Timestamp.from(now)
        );
    }

    private static PendingOrder mapPending(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new PendingOrder(
                result.getObject("id", UUID.class), result.getLong("order_code"), result.getLong("amount_vnd"),
                result.getString("checkout_url"), result.getTimestamp("expires_at").toInstant(),
                result.getString("request_fingerprint"),
                result.getObject("checkout_claim_token", UUID.class)
        );
    }

    public record Plan(
            UUID id, String code, int version, String name, String interval, String productType,
            long amountVnd, long processedVideoSeconds, int instructorSeats, int activeLearners,
            long qaQueries
    ) {
    }

    public record Checkout(UUID orderId, long orderCode, long amountVnd, String checkoutUrl, Instant expiresAt) {
    }

    public record UsageView(
            long allowanceSeconds,
            long committedSeconds,
            long reservedSeconds,
            long actualAiCostMicrousd,
            long shadowAiCostMicrousd,
            Instant periodStart,
            Instant periodEnd,
            long qaQueryAllowance,
            long qaQueryCommitted,
            long qaQueryReserved
    ) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public long availableSeconds() {
            return Math.max(0, allowanceSeconds - committedSeconds - reservedSeconds);
        }

        @com.fasterxml.jackson.annotation.JsonProperty
        public long availableQaQueries() {
            return Math.max(0, qaQueryAllowance - qaQueryCommitted - qaQueryReserved);
        }
    }

    private record MetricBalance(long allowance, long committed, long reserved) { }

    public record SubscriptionView(
            UUID id, String planCode, String planName, String status,
            Instant periodStart, Instant periodEnd, boolean cancelAtPeriodEnd,
            String nextPlanCode, String nextPlanName, boolean nextPlanPaid
    ) {
    }

    public record InvoiceView(
            UUID id, String invoiceNumber, String state, String currency, String invoiceType,
            long listPriceVnd, long discountVnd, long amountDueVnd, long amountPaidVnd,
            String promotionCode, String buyerType, String buyerLegalName, String buyerTaxIdentifier,
            String buyerAddress, String buyerEmail, String buyerCountryCode, Long billingProfileVersion,
            boolean taxDocumentRequested, Instant dueAt, Instant paidAt, Instant createdAt
    ) {
    }

    public record RefundView(
            UUID id, UUID invoiceId, long amountVnd, String reason, String state,
            String providerReference, Instant requestedAt, Instant resolvedAt
    ) {
    }

    private record PendingOrder(
            UUID id, long orderCode, long amountVnd, String checkoutUrl, Instant expiresAt,
            String fingerprint, UUID claimToken
    ) {
        Checkout toCheckout() {
            return new Checkout(id, orderCode, amountVnd, checkoutUrl, expiresAt);
        }

        PendingOrder withClaim(UUID token) {
            return new PendingOrder(id, orderCode, amountVnd, checkoutUrl, expiresAt, fingerprint, token);
        }
    }

    private record OrderForPayment(
            UUID id, UUID organizationId, UUID planId, long listPriceVnd, long discountVnd,
            long amountVnd, UUID promotionCampaignId, String state,
            String paymentLinkId, String interval, String productType, long processedSeconds, long qaQueries
    ) {
    }

    public record Quote(
            long listPriceVnd, long discountVnd, long amountVnd,
            String promotionCode, String attributionChannel
    ) { }

    private record Promotion(
            UUID id, String code, int discountBps, String attributionChannel, int maxRedemptions
    ) { }

    private BillingProfileSnapshot billingProfileSnapshot(UUID organizationId) {
        return jdbc.query(
                """
                SELECT buyer_type, legal_name, tax_identifier, billing_address, billing_email,
                       country_code, invoice_requested, version
                FROM organization_billing_profiles WHERE organization_id = ?
                """,
                (result, row) -> new BillingProfileSnapshot(
                        result.getString("buyer_type"), result.getString("legal_name"),
                        result.getString("tax_identifier"), result.getString("billing_address"),
                        result.getString("billing_email"), result.getString("country_code"),
                        result.getBoolean("invoice_requested"), result.getLong("version")
                ), organizationId
        ).stream().findFirst().orElse(null);
    }

    private record BillingProfileSnapshot(
            String buyerType, String legalName, String taxIdentifier, String address,
            String email, String countryCode, boolean invoiceRequested, long version
    ) { }

    private record SubscriptionCandidate(UUID id, UUID planId, String status, Instant periodEnd) {
    }

    private record PlanChangeSource(UUID planId, Instant periodEnd) { }

    private record PlanChangeCancellation(UUID planId, UUID nextPlanId, Instant periodEnd) { }

    private record SubscriptionPeriod(UUID subscriptionId, Instant periodStart, Instant periodEnd) {
    }

    private record ReconciliationCandidate(long orderCode, long amountVnd, String paymentLinkId) {
    }

    private record RefundSource(
            UUID invoiceId, UUID orderId, UUID paymentId, long amountVnd, String state, String invoiceType
    ) {
    }

    private record RefundResolution(
            UUID id, UUID organizationId, UUID invoiceId, UUID paymentId, UUID orderId,
            String state, String providerReference
    ) {
    }

    private record CreditGrant(UUID id, UUID entitlementId, String metric, long units) {
    }

    private record InvoiceIdentity(UUID id, String number) {
    }

    private record RenewalCandidate(
            UUID subscriptionId, UUID organizationId, UUID planId, UUID ownerId, Instant periodEnd
    ) {
    }

    public record ReconciliationResult(
            int checked, int paid, int terminal, int failed, int subscriptionsAdvanced, int renewalsPrepared
    ) {
    }
}
