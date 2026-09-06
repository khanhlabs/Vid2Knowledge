package com.vid2knowledge.integration;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class WebhookEndpointService {
    public static final Set<String> ALLOWED_EVENTS = Set.of(
            "AnalysisCompleted", "AnalysisFailed", "PackagePublished", "AssignmentPublished",
            "AssessmentSubmitted", "DelayedRecallCompleted", "QualityIssueReported",
            "InvitationAccepted", "PaymentReceived", "SubscriptionCancellationScheduled"
    );
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ApiKeyService apiKeys;
    private final IntegrationSecretCipher cipher;
    private final WebhookUrlPolicy urlPolicy;
    private final Clock clock = Clock.systemUTC();

    public WebhookEndpointService(
            JdbcTemplate jdbc, ApiKeyService apiKeys, IntegrationSecretCipher cipher, WebhookUrlPolicy urlPolicy
    ) {
        this.jdbc = jdbc;
        this.apiKeys = apiKeys;
        this.cipher = cipher;
        this.urlPolicy = urlPolicy;
    }

    public List<EndpointView> list(UUID organizationId) {
        return jdbc.query(
                """
                SELECT id, name, url, event_types, state, current_secret_version, created_at, updated_at
                FROM webhook_endpoints WHERE organization_id = ? ORDER BY created_at DESC, id DESC
                """,
                (result, row) -> new EndpointView(
                        result.getObject("id", UUID.class), result.getString("name"), result.getString("url"),
                        stringSet(result.getArray("event_types")), result.getString("state"),
                        result.getInt("current_secret_version"), result.getTimestamp("created_at").toInstant(),
                        result.getTimestamp("updated_at").toInstant()
                ), organizationId
        );
    }

    @Transactional
    public EndpointCreated create(
            CurrentActor actor, String name, String url, Set<String> requestedEvents, String correlationId
    ) {
        apiKeys.requireBusinessPlan(actor.organizationId());
        Long activeEndpointCount = jdbc.queryForObject(
                "SELECT count(*) FROM webhook_endpoints WHERE organization_id = ? AND state = 'ACTIVE'",
                Long.class, actor.organizationId()
        );
        if (activeEndpointCount != null && activeEndpointCount >= 10) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "At most 10 active webhook endpoints are allowed");
        }
        String safeName = name(name);
        String safeUrl = urlPolicy.requirePublicHttps(url).toASCIIString();
        Set<String> events = events(requestedEvents);
        Instant now = clock.instant();
        UUID endpointId = UuidV7Generator.generate();
        String secret = secret();
        jdbc.update(
                """
                INSERT INTO webhook_endpoints(
                    id, organization_id, name, url, event_types, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?)
                """,
                endpointId, actor.organizationId(), safeName, safeUrl, pgArray(events), actor.userId(),
                Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                "INSERT INTO webhook_endpoint_secrets(endpoint_id, version, encrypted_secret, created_at) VALUES (?, 1, ?, ?)",
                endpointId, cipher.encrypt(secret, aad(endpointId, 1)), Timestamp.from(now)
        );
        audit(actor, "WEBHOOK_ENDPOINT_CREATED", endpointId, correlationId, now);
        return new EndpointCreated(endpointId, safeName, safeUrl, events, "ACTIVE", 1, secret, now);
    }

    @Transactional
    public SecretRotated rotate(CurrentActor actor, UUID endpointId, String correlationId) {
        apiKeys.requireBusinessPlan(actor.organizationId());
        Integer version = jdbc.query(
                """
                SELECT current_secret_version FROM webhook_endpoints
                WHERE organization_id = ? AND id = ? AND state = 'ACTIVE' FOR UPDATE
                """,
                (result, row) -> result.getInt(1), actor.organizationId(), endpointId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active webhook endpoint not found"));
        int next = version + 1;
        String secret = secret();
        Instant now = clock.instant();
        jdbc.update(
                "INSERT INTO webhook_endpoint_secrets(endpoint_id, version, encrypted_secret, created_at) VALUES (?, ?, ?, ?)",
                endpointId, next, cipher.encrypt(secret, aad(endpointId, next)), Timestamp.from(now)
        );
        jdbc.update("UPDATE webhook_endpoints SET current_secret_version = ?, updated_at = ? WHERE id = ?",
                next, Timestamp.from(now), endpointId);
        audit(actor, "WEBHOOK_SECRET_ROTATED", endpointId, correlationId, now);
        return new SecretRotated(endpointId, next, secret, now);
    }

    @Transactional
    public void disable(CurrentActor actor, UUID endpointId, String correlationId) {
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE webhook_endpoints SET state = 'DISABLED', updated_at = ?
                WHERE organization_id = ? AND id = ? AND state = 'ACTIVE'
                """,
                Timestamp.from(now), actor.organizationId(), endpointId
        );
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active webhook endpoint not found");
        jdbc.update(
                """
                UPDATE webhook_deliveries SET state = 'DEAD_LETTER', last_error = 'Endpoint disabled',
                    lease_owner = NULL, lease_expires_at = NULL
                WHERE organization_id = ? AND endpoint_id = ? AND state = 'PENDING'
                """,
                actor.organizationId(), endpointId
        );
        audit(actor, "WEBHOOK_ENDPOINT_DISABLED", endpointId, correlationId, now);
    }

    public List<DeliveryView> deliveries(UUID organizationId, UUID endpointId) {
        return jdbc.query(
                """
                SELECT d.id, d.event_type, d.event_version, d.state, d.attempt_count,
                       d.response_status, d.last_error, d.delivered_at, d.created_at
                FROM webhook_deliveries d JOIN webhook_endpoints e ON e.id = d.endpoint_id
                WHERE d.organization_id = ? AND d.endpoint_id = ? AND e.organization_id = ?
                ORDER BY d.created_at DESC, d.id DESC LIMIT 100
                """,
                (result, row) -> new DeliveryView(
                        result.getObject("id", UUID.class), result.getString("event_type"),
                        result.getInt("event_version"), result.getString("state"), result.getInt("attempt_count"),
                        (Integer) result.getObject("response_status"), result.getString("last_error"),
                        instant(result.getTimestamp("delivered_at")), result.getTimestamp("created_at").toInstant()
                ), organizationId, endpointId, organizationId
        );
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook name must contain 1-120 characters");
        }
        return value.trim();
    }

    private static Set<String> events(Set<String> values) {
        if (values == null || values.isEmpty() || !ALLOWED_EVENTS.containsAll(values)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or empty webhook event types");
        }
        return Set.copyOf(new LinkedHashSet<>(values));
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String aad(UUID endpointId, int version) {
        return endpointId + ":" + version;
    }

    private void audit(CurrentActor actor, String action, UUID id, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(id, organization_id, actor_user_id, action, resource_type,
                                       resource_id, correlation_id, created_at)
                VALUES (?, ?, ?, ?, 'WebhookEndpoint', ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action, id,
                correlationId, Timestamp.from(now)
        );
    }

    private static Set<String> stringSet(Array array) throws java.sql.SQLException {
        return Set.of((String[]) array.getArray());
    }

    private static String pgArray(Set<String> values) { return "{" + String.join(",", values) + "}"; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record EndpointView(
            UUID id, String name, String url, Set<String> eventTypes, String state,
            int secretVersion, Instant createdAt, Instant updatedAt
    ) {}
    public record EndpointCreated(
            UUID id, String name, String url, Set<String> eventTypes, String state,
            int secretVersion, String signingSecret, Instant createdAt
    ) {}
    public record SecretRotated(UUID endpointId, int secretVersion, String signingSecret, Instant rotatedAt) {}
    public record DeliveryView(
            UUID id, String eventType, int eventVersion, String state, int attemptCount,
            Integer responseStatus, String lastError, Instant deliveredAt, Instant createdAt
    ) {}
}
