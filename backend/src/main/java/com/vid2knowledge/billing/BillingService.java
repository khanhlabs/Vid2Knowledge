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
                SELECT id, code, version, name, billing_interval, amount_vnd,
                       processed_video_seconds, instructor_seats, active_learners, qa_queries
                FROM pricing_plans
                WHERE active = TRUE AND effective_from <= ?
                  AND (effective_until IS NULL OR effective_until > ?)
                ORDER BY amount_vnd, code
                """,
                (result, row) -> new Plan(
                        result.getObject("id", UUID.class), result.getString("code"), result.getInt("version"),
                        result.getString("name"), result.getString("billing_interval"),
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
                       s.current_period_end, s.cancel_at_period_end
                FROM subscriptions s JOIN pricing_plans p ON p.id = s.plan_id
                WHERE s.organization_id = ? AND s.status IN ('ACTIVE','PAST_DUE')
                ORDER BY s.current_period_start DESC LIMIT 1
                """,
                (result, row) -> new SubscriptionView(
                        result.getObject("id", UUID.class), result.getString("code"), result.getString("name"),
                        result.getString("status"), result.getTimestamp("current_period_start").toInstant(),
                        result.getTimestamp("current_period_end").toInstant(), result.getBoolean("cancel_at_period_end")
                ),
                organizationId
        ).stream().findFirst();
    }

    public List<InvoiceView> invoices(UUID organizationId) {
        return jdbc.query(
                """
                SELECT id, invoice_number, state, currency, amount_due_vnd,
                       amount_paid_vnd, due_at, paid_at, created_at
                FROM invoices
                WHERE organization_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 100
                """,
                (result, row) -> new InvoiceView(
                        result.getObject("id", UUID.class), result.getString("invoice_number"),
                        result.getString("state"), result.getString("currency"),
                        result.getLong("amount_due_vnd"), result.getLong("amount_paid_vnd"),
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
                UPDATE subscriptions SET cancel_at_period_end = TRUE, cancelled_at = ?, updated_at = ?
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

    public Checkout checkout(CurrentActor actor, UUID planId, String idempotencyKey) {
        PendingOrder pending = transactions.execute(status -> createOrLoadPending(actor, planId, idempotencyKey));
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

    private PendingOrder createOrLoadPending(CurrentActor actor, UUID planId, String idempotencyKey) {
        jdbc.queryForObject(
                "SELECT CAST(pg_advisory_xact_lock(hashtextextended(?, 0)) AS text)",
                String.class, actor.organizationId() + "|checkout|" + idempotencyKey
        );
        Plan plan = plans().stream().filter(candidate -> candidate.id().equals(planId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found"));
        String fingerprint = RequestFingerprint.sha256(actor.organizationId() + "|" + plan.id());
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
        Long orderCode = jdbc.queryForObject("SELECT nextval('billing_order_code_seq')", Long.class);
        UUID orderId = UuidV7Generator.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(payOs.checkoutTtl());
        jdbc.update(
                """
                INSERT INTO billing_orders(
                    id, organization_id, plan_id, order_code, amount_vnd, idempotency_key,
                    request_fingerprint, expires_at, created_by, checkout_claim_token,
                    checkout_claim_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                orderId, actor.organizationId(), plan.id(), orderCode, plan.amountVnd(), idempotencyKey,
                fingerprint, Timestamp.from(expiresAt), actor.userId(), orderId,
                Timestamp.from(now.plus(Duration.ofMinutes(2))), Timestamp.from(now), Timestamp.from(now)
        );
        return new PendingOrder(orderId, orderCode, plan.amountVnd(), null, expiresAt, fingerprint, orderId);
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
                SELECT o.id, o.organization_id, o.plan_id, o.amount_vnd, o.state,
                       o.provider_payment_link_id, p.billing_interval, p.processed_video_seconds, p.qa_queries
                FROM billing_orders o JOIN pricing_plans p ON p.id = o.plan_id
                WHERE o.order_code = ? FOR UPDATE OF o
                """,
                (result, row) -> new OrderForPayment(
                        result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                        result.getObject("plan_id", UUID.class), result.getLong("amount_vnd"),
                        result.getString("state"), result.getString("provider_payment_link_id"),
                        result.getString("billing_interval"), result.getLong("processed_video_seconds"),
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
        expireElapsedSubscriptions(now);
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
        Long invoiceSequence = jdbc.queryForObject("SELECT nextval('invoice_number_seq')", Long.class);
        UUID invoiceId = UuidV7Generator.generate();
        String invoiceNumber = "V2K-" + invoiceSequence;
        jdbc.update(
                """
                INSERT INTO invoices(
                    id, organization_id, subscription_id, billing_order_id, invoice_number,
                    state, amount_due_vnd, amount_paid_vnd, due_at, paid_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'PAID', ?, ?, ?, ?, ?, ?)
                """,
                invoiceId, order.organizationId(), subscription.subscriptionId(), order.id(),
                invoiceNumber, order.amountVnd(), order.amountVnd(), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
        );
        notifications.paymentReceipt(order.organizationId(), invoiceId, invoiceNumber, order.amountVnd(), now);
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
        return jdbc.update(
                """
                UPDATE subscriptions SET status = 'EXPIRED', updated_at = ?
                WHERE status IN ('ACTIVE', 'PAST_DUE', 'SCHEDULED') AND current_period_end <= ?
                """,
                Timestamp.from(now), Timestamp.from(now)
        );
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
            return new ReconciliationResult(0, 0, 0, 0, 0);
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
                    log.error("payOS reconciliation mismatch. orderCode={}", candidate.orderCode());
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
        return new ReconciliationResult(candidates.size(), paid, terminal, failed, advanced);
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

    private static PendingOrder mapPending(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new PendingOrder(
                result.getObject("id", UUID.class), result.getLong("order_code"), result.getLong("amount_vnd"),
                result.getString("checkout_url"), result.getTimestamp("expires_at").toInstant(),
                result.getString("request_fingerprint"),
                result.getObject("checkout_claim_token", UUID.class)
        );
    }

    public record Plan(
            UUID id, String code, int version, String name, String interval,
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
        public long availableSeconds() {
            return Math.max(0, allowanceSeconds - committedSeconds - reservedSeconds);
        }

        public long availableQaQueries() {
            return Math.max(0, qaQueryAllowance - qaQueryCommitted - qaQueryReserved);
        }
    }

    private record MetricBalance(long allowance, long committed, long reserved) { }

    public record SubscriptionView(
            UUID id, String planCode, String planName, String status,
            Instant periodStart, Instant periodEnd, boolean cancelAtPeriodEnd
    ) {
    }

    public record InvoiceView(
            UUID id, String invoiceNumber, String state, String currency,
            long amountDueVnd, long amountPaidVnd, Instant dueAt, Instant paidAt, Instant createdAt
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
            UUID id, UUID organizationId, UUID planId, long amountVnd, String state,
            String paymentLinkId, String interval, long processedSeconds, long qaQueries
    ) {
    }

    private record SubscriptionCandidate(UUID id, UUID planId, String status, Instant periodEnd) {
    }

    private record SubscriptionPeriod(UUID subscriptionId, Instant periodStart, Instant periodEnd) {
    }

    private record ReconciliationCandidate(long orderCode, long amountVnd, String paymentLinkId) {
    }

    public record ReconciliationResult(int checked, int paid, int terminal, int failed, int subscriptionsAdvanced) {
    }
}
