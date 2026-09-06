package com.vid2knowledge.billing;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.PayOsProperties;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import com.vid2knowledge.usage.domain.UsageMetric;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class BillingService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PaymentGateway gateway;
    private final PayOsProperties payOs;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BillingService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            PaymentGateway gateway,
            PayOsProperties payOs,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.gateway = gateway;
        this.payOs = payOs;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    public List<Plan> plans() {
        return jdbc.query(
                """
                SELECT id, code, version, name, billing_interval, amount_vnd,
                       processed_video_seconds, instructor_seats, active_learners
                FROM pricing_plans
                WHERE active = TRUE AND effective_from <= ?
                  AND (effective_until IS NULL OR effective_until > ?)
                ORDER BY amount_vnd, code
                """,
                (result, row) -> new Plan(
                        result.getObject("id", UUID.class), result.getString("code"), result.getInt("version"),
                        result.getString("name"), result.getString("billing_interval"),
                        result.getLong("amount_vnd"), result.getLong("processed_video_seconds"),
                        result.getInt("instructor_seats"), result.getInt("active_learners")
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
                        result.getTimestamp("period_start").toInstant(), result.getTimestamp("period_end").toInstant()
                ),
                Timestamp.from(clock.instant()), organizationId, organizationId, organizationId,
                UsageMetric.PROCESSED_VIDEO_SECOND.name(), Timestamp.from(clock.instant()), Timestamp.from(clock.instant())
        );
        return views.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "No active entitlement")
        );
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

    public void cancelAtPeriodEnd(CurrentActor actor, UUID subscriptionId) {
        int updated = jdbc.update(
                """
                UPDATE subscriptions SET cancel_at_period_end = TRUE, cancelled_at = ?, updated_at = ?
                WHERE id = ? AND organization_id = ? AND status = 'ACTIVE'
                """,
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), subscriptionId, actor.organizationId()
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active subscription not found");
        }
    }

    public Checkout checkout(CurrentActor actor, UUID planId, String idempotencyKey) {
        PendingOrder pending = transactions.execute(status -> createOrLoadPending(actor, planId, idempotencyKey));
        if (pending == null) {
            throw new IllegalStateException("Could not create billing order");
        }
        if (pending.checkoutUrl() != null) {
            return pending.toCheckout();
        }
        PaymentGateway.CheckoutLink link = gateway.createCheckout(
                pending.orderCode(), pending.amountVnd(), "V2K " + pending.orderCode()
        );
        return transactions.execute(status -> {
            jdbc.update(
                    """
                    UPDATE billing_orders SET provider_payment_link_id = ?, checkout_url = ?, updated_at = ?
                    WHERE id = ? AND state = 'PENDING' AND checkout_url IS NULL
                    """,
                    link.providerId(), link.checkoutUrl().toString(), Timestamp.from(clock.instant()), pending.id()
            );
            return new Checkout(
                    pending.id(), pending.orderCode(), pending.amountVnd(), link.checkoutUrl().toString(), pending.expiresAt()
            );
        });
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
                SELECT id, order_code, amount_vnd, checkout_url, expires_at, request_fingerprint
                FROM billing_orders WHERE organization_id = ? AND idempotency_key = ?
                """,
                BillingService::mapPending,
                actor.organizationId(), idempotencyKey
        );
        if (!existing.isEmpty()) {
            if (!fingerprint.equals(existing.getFirst().fingerprint())) {
                throw new IdempotencyConflictException();
            }
            return existing.getFirst();
        }
        Long orderCode = jdbc.queryForObject("SELECT nextval('billing_order_code_seq')", Long.class);
        UUID orderId = UuidV7Generator.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(payOs.checkoutTtl());
        jdbc.update(
                """
                INSERT INTO billing_orders(
                    id, organization_id, plan_id, order_code, amount_vnd, idempotency_key,
                    request_fingerprint, expires_at, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                orderId, actor.organizationId(), plan.id(), orderCode, plan.amountVnd(), idempotencyKey,
                fingerprint, Timestamp.from(expiresAt), actor.userId(), Timestamp.from(now), Timestamp.from(now)
        );
        return new PendingOrder(orderId, orderCode, plan.amountVnd(), null, expiresAt, fingerprint);
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
        if (!envelope.path("success").asBoolean(false) || !"00".equals(data.path("code").asText())) {
            return;
        }
        long orderCode = data.path("orderCode").asLong(-1);
        long amount = data.path("amount").asLong(-1);
        String currency = data.path("currency").asText();
        String reference = data.path("reference").asText();
        String paymentLinkId = data.path("paymentLinkId").asText();
        if (orderCode <= 0 || amount <= 0 || reference.isBlank() || !"VND".equals(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment data");
        }
        transactions.executeWithoutResult(status -> applyPayment(
                envelope, signature, orderCode, amount, reference, paymentLinkId
        ));
    }

    private void applyPayment(
            JsonNode envelope,
            String signature,
            long orderCode,
            long amount,
            String reference,
            String paymentLinkId
    ) {
        String eventKey = reference + "|" + orderCode;
        Instant now = clock.instant();
        int inserted = jdbc.update(
                """
                INSERT INTO payment_webhook_inbox(
                    id, provider, event_key, signature, payload_json, received_at
                ) VALUES (?, 'PAYOS', ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (provider, event_key) DO NOTHING
                """,
                UuidV7Generator.generate(), eventKey, signature, envelope.toString(), Timestamp.from(now)
        );
        if (inserted == 0) {
            return;
        }
        List<OrderForPayment> orders = jdbc.query(
                """
                SELECT o.id, o.organization_id, o.plan_id, o.amount_vnd, o.state,
                       o.provider_payment_link_id, p.billing_interval, p.processed_video_seconds
                FROM billing_orders o JOIN pricing_plans p ON p.id = o.plan_id
                WHERE o.order_code = ? FOR UPDATE OF o
                """,
                (result, row) -> new OrderForPayment(
                        result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                        result.getObject("plan_id", UUID.class), result.getLong("amount_vnd"),
                        result.getString("state"), result.getString("provider_payment_link_id"),
                        result.getString("billing_interval"), result.getLong("processed_video_seconds")
                ),
                orderCode
        );
        OrderForPayment order = orders.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown billing order")
        );
        if (order.amountVnd() != amount
                || (order.paymentLinkId() != null && !order.paymentLinkId().equals(paymentLinkId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment does not match billing order");
        }
        if ("PAID".equals(order.state())) {
            markWebhookProcessed(eventKey, now);
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
        jdbc.update(
                "UPDATE billing_orders SET state = 'PAID', paid_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(now), Timestamp.from(now), order.id()
        );
        Instant periodEnd = switch (order.interval()) {
            case "YEAR" -> now.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
            case "MONTH" -> now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
            default -> now.plus(Duration.ofDays(30));
        };
        jdbc.update(
                """
                INSERT INTO subscriptions(
                    id, organization_id, plan_id, billing_order_id, status,
                    current_period_start, current_period_end, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), order.planId(), order.id(),
                Timestamp.from(now), Timestamp.from(periodEnd), Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO entitlements(
                    id, organization_id, metric, allowance, period_start, period_end, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), UsageMetric.PROCESSED_VIDEO_SECOND.name(),
                order.processedSeconds(), Timestamp.from(now), Timestamp.from(periodEnd),
                Timestamp.from(now), Timestamp.from(now)
        );
        markWebhookProcessed(eventKey, now);
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'PaymentReceived', 1, 'BillingOrder', ?, ?,
                          jsonb_build_object('billingOrderId', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), order.organizationId(), order.id(),
                "payos-" + RequestFingerprint.sha256(eventKey).substring(0, 24),
                order.id(), Timestamp.from(now), Timestamp.from(now)
        );
    }

    private void markWebhookProcessed(String eventKey, Instant now) {
        jdbc.update(
                "UPDATE payment_webhook_inbox SET processed_at = ? WHERE provider = 'PAYOS' AND event_key = ?",
                Timestamp.from(now), eventKey
        );
    }

    private static PendingOrder mapPending(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new PendingOrder(
                result.getObject("id", UUID.class), result.getLong("order_code"), result.getLong("amount_vnd"),
                result.getString("checkout_url"), result.getTimestamp("expires_at").toInstant(),
                result.getString("request_fingerprint")
        );
    }

    public record Plan(
            UUID id, String code, int version, String name, String interval,
            long amountVnd, long processedVideoSeconds, int instructorSeats, int activeLearners
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
            Instant periodEnd
    ) {
        public long availableSeconds() {
            return Math.max(0, allowanceSeconds - committedSeconds - reservedSeconds);
        }
    }

    public record SubscriptionView(
            UUID id, String planCode, String planName, String status,
            Instant periodStart, Instant periodEnd, boolean cancelAtPeriodEnd
    ) {
    }

    private record PendingOrder(
            UUID id, long orderCode, long amountVnd, String checkoutUrl, Instant expiresAt, String fingerprint
    ) {
        Checkout toCheckout() {
            return new Checkout(id, orderCode, amountVnd, checkoutUrl, expiresAt);
        }
    }

    private record OrderForPayment(
            UUID id, UUID organizationId, UUID planId, long amountVnd, String state,
            String paymentLinkId, String interval, long processedSeconds
    ) {
    }
}
