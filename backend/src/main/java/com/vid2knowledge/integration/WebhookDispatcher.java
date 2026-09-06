package com.vid2knowledge.integration;

import com.vid2knowledge.config.IntegrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IntegrationSecretCipher cipher;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookHttpSender sender;
    private final IntegrationProperties properties;
    private final Clock clock;

    @Autowired
    public WebhookDispatcher(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            IntegrationSecretCipher cipher,
            WebhookUrlPolicy urlPolicy,
            WebhookHttpSender sender,
            IntegrationProperties properties
    ) {
        this(jdbc, transactions, cipher, urlPolicy, sender, properties, Clock.systemUTC());
    }

    WebhookDispatcher(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            IntegrationSecretCipher cipher,
            WebhookUrlPolicy urlPolicy,
            WebhookHttpSender sender,
            IntegrationProperties properties,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.cipher = cipher;
        this.urlPolicy = urlPolicy;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
    }

    public DispatchResult dispatch(String leaseOwner) {
        if (leaseOwner == null || leaseOwner.isBlank() || leaseOwner.length() > 160) {
            throw new IllegalArgumentException("A valid webhook lease owner is required");
        }
        transactions.executeWithoutResult(status -> expireIneligible());
        List<ClaimedDelivery> claimed = transactions.execute(status -> claim(leaseOwner));
        int delivered = 0;
        int retried = 0;
        int dead = 0;
        for (ClaimedDelivery delivery : claimed == null ? List.<ClaimedDelivery>of() : claimed) {
            try {
                URI target = urlPolicy.requirePublicHttps(delivery.url());
                Instant timestamp = clock.instant();
                String secret = cipher.decrypt(
                        delivery.encryptedSecret(),
                        WebhookEndpointService.aad(delivery.endpointId(), delivery.secretVersion())
                );
                String signature = signature(secret, timestamp, delivery.payload());
                int status = sender.send(
                        target, delivery.id(), delivery.eventType(), timestamp, signature, delivery.payload()
                );
                if (status >= 200 && status < 300) {
                    markDelivered(delivery, status);
                    delivered++;
                } else {
                    boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
                    boolean deadLetter = !retryable || delivery.attemptCount() >= properties.maxAttempts();
                    markFailed(delivery, "Webhook returned HTTP " + status, status, deadLetter);
                    if (deadLetter) dead++; else retried++;
                }
            } catch (RuntimeException failure) {
                boolean nonRetryable = failure instanceof org.springframework.web.server.ResponseStatusException;
                boolean deadLetter = nonRetryable || delivery.attemptCount() >= properties.maxAttempts();
                markFailed(delivery, safeMessage(failure), null, deadLetter);
                if (deadLetter) dead++; else retried++;
            }
        }
        return new DispatchResult(claimed == null ? 0 : claimed.size(), delivered, retried, dead);
    }

    private void expireIneligible() {
        jdbc.update(
                """
                UPDATE webhook_deliveries d SET state = 'DEAD_LETTER',
                    last_error = 'Business entitlement or endpoint is inactive',
                    lease_owner = NULL, lease_expires_at = NULL
                WHERE d.state = 'PENDING' AND (
                    NOT EXISTS (
                      SELECT 1 FROM webhook_endpoints e
                      WHERE e.id = d.endpoint_id AND e.state = 'ACTIVE'
                    ) OR NOT EXISTS (
                      SELECT 1 FROM subscriptions sub JOIN pricing_plans p ON p.id = sub.plan_id
                      WHERE sub.organization_id = d.organization_id AND sub.status = 'ACTIVE'
                        AND sub.current_period_end > ? AND p.code LIKE 'BUSINESS\\_%' ESCAPE '\\'
                    )
                )
                """,
                Timestamp.from(clock.instant())
        );
    }

    private List<ClaimedDelivery> claim(String owner) {
        Instant now = clock.instant();
        return jdbc.query(
                """
                WITH candidates AS (
                    SELECT d0.id FROM webhook_deliveries d0
                    JOIN webhook_endpoints e0 ON e0.id = d0.endpoint_id
                    WHERE d0.state = 'PENDING' AND d0.available_at <= ?
                      AND (d0.lease_expires_at IS NULL OR d0.lease_expires_at <= ?)
                      AND e0.state = 'ACTIVE'
                      AND EXISTS (
                        SELECT 1 FROM subscriptions sub JOIN pricing_plans p ON p.id = sub.plan_id
                        WHERE sub.organization_id = d0.organization_id AND sub.status = 'ACTIVE'
                          AND sub.current_period_end > ? AND p.code LIKE 'BUSINESS\\_%' ESCAPE '\\'
                      )
                    ORDER BY d0.available_at, d0.created_at, d0.id
                    FOR UPDATE OF d0 SKIP LOCKED LIMIT ?
                )
                UPDATE webhook_deliveries d
                SET attempt_count = attempt_count + 1, lease_owner = ?, lease_expires_at = ?
                FROM candidates c, webhook_endpoints e, webhook_endpoint_secrets s
                WHERE d.id = c.id AND e.id = d.endpoint_id
                  AND e.state = 'ACTIVE' AND s.endpoint_id = d.endpoint_id AND s.version = d.secret_version
                RETURNING d.id, d.endpoint_id, d.event_type, d.secret_version, d.payload_json::text,
                          d.attempt_count, e.url, s.encrypted_secret
                """,
                (result, row) -> new ClaimedDelivery(
                        result.getObject("id", UUID.class), result.getObject("endpoint_id", UUID.class),
                        result.getString("event_type"), result.getInt("secret_version"),
                        result.getString("payload_json"), result.getInt("attempt_count"),
                        result.getString("url"), result.getString("encrypted_secret"), owner
                ),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), properties.dispatchBatchSize(),
                owner, Timestamp.from(now.plus(properties.leaseDuration()))
        );
    }

    private void markDelivered(ClaimedDelivery delivery, int responseStatus) {
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE webhook_deliveries SET state = 'DELIVERED', response_status = ?, delivered_at = ?,
                    lease_owner = NULL, lease_expires_at = NULL, last_error = NULL
                WHERE id = ? AND state = 'PENDING' AND lease_owner = ?
                """,
                responseStatus, Timestamp.from(now), delivery.id(), delivery.owner()
        );
        if (changed != 1) throw new IllegalStateException("Webhook delivery lease was lost");
    }

    private void markFailed(ClaimedDelivery delivery, String error, Integer responseStatus, boolean deadLetter) {
        Instant now = clock.instant();
        Duration backoff = Duration.ofSeconds(Math.min(3600, 30L << Math.min(delivery.attemptCount() - 1, 7)));
        jdbc.update(
                """
                UPDATE webhook_deliveries SET state = ?, response_status = ?, last_error = ?, available_at = ?,
                    lease_owner = NULL, lease_expires_at = NULL
                WHERE id = ? AND state = 'PENDING' AND lease_owner = ?
                """,
                deadLetter ? "DEAD_LETTER" : "PENDING", responseStatus, truncate(error),
                Timestamp.from(now.plus(backoff)), delivery.id(), delivery.owner()
        );
        log.warn("WEBHOOK_DELIVERY_FAILED deliveryId={}, eventType={}, attempt={}, deadLetter={}, responseStatus={}",
                delivery.id(), delivery.eventType(), delivery.attemptCount(), deadLetter, responseStatus);
    }

    static String signature(String secret, Instant timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp.getEpochSecond() + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Could not sign webhook payload", failure);
        }
    }

    private static String safeMessage(RuntimeException failure) {
        return truncate(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }

    private static String truncate(String value) {
        return value.substring(0, Math.min(value.length(), 1000));
    }

    private record ClaimedDelivery(
            UUID id, UUID endpointId, String eventType, int secretVersion, String payload,
            int attemptCount, String url, String encryptedSecret, String owner
    ) {}

    public record DispatchResult(int claimed, int delivered, int retried, int deadLettered) {}
}
