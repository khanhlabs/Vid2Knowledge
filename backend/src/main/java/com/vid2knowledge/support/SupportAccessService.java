package com.vid2knowledge.support;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class SupportAccessService {
    private static final int MAX_ACTIVE_GRANTS = 3;
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    public SupportAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Grant> grants(UUID organizationId) {
        Instant now = clock.instant();
        return jdbc.query(
                """
                SELECT id, scope, reason, ticket_reference, created_at, expires_at, revoked_at,
                       CASE WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                            WHEN expires_at <= ? THEN 'EXPIRED' ELSE 'ACTIVE' END AS state
                FROM support_access_grants WHERE organization_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 100
                """,
                (result, row) -> new Grant(
                        result.getObject("id", UUID.class), result.getString("scope"),
                        result.getString("reason"), result.getString("ticket_reference"),
                        result.getString("state"), result.getTimestamp("created_at").toInstant(),
                        result.getTimestamp("expires_at").toInstant(),
                        result.getTimestamp("revoked_at") == null
                                ? null : result.getTimestamp("revoked_at").toInstant()
                ), Timestamp.from(now), organizationId
        );
    }

    @Transactional
    public Grant create(CurrentActor actor, CreateGrant request) {
        String reason = text(request.reason(), "Support reason", 500);
        String ticket = optionalText(request.ticketReference(), 120);
        if (request.durationMinutes() < 15 || request.durationMinutes() > 1_440) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Support access must last 15-1440 minutes");
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(request.durationMinutes()));
        lockOrganization(actor.organizationId());
        if (count(
                "SELECT count(*) FROM support_access_grants WHERE organization_id = ? AND revoked_at IS NULL AND expires_at > ?",
                actor.organizationId(), Timestamp.from(now)
        ) >= MAX_ACTIVE_GRANTS) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "At most " + MAX_ACTIVE_GRANTS + " active support grants are allowed"
            );
        }
        UUID id = UuidV7Generator.generate();
        jdbc.update(
                """
                INSERT INTO support_access_grants(
                    id, organization_id, scope, reason, ticket_reference,
                    created_by, created_at, expires_at
                ) VALUES (?, ?, 'DIAGNOSTICS', ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), reason, ticket, actor.userId(),
                Timestamp.from(now), Timestamp.from(expiresAt)
        );
        ownerEvent(id, actor, "CREATED", now);
        return grants(actor.organizationId()).stream().filter(grant -> grant.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void revoke(CurrentActor actor, UUID grantId) {
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE support_access_grants SET revoked_at = ?, revoked_by = ?
                WHERE id = ? AND organization_id = ? AND revoked_at IS NULL AND expires_at > ?
                """,
                Timestamp.from(now), actor.userId(), grantId, actor.organizationId(), Timestamp.from(now)
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active support grant not found");
        }
        ownerEvent(grantId, actor, "REVOKED", now);
    }

    @Transactional
    public Diagnostics diagnostics(UUID organizationId, UUID grantId, String supportSubject) {
        Instant now = clock.instant();
        List<UUID> allowed = jdbc.query(
                """
                SELECT id FROM support_access_grants
                WHERE id = ? AND organization_id = ? AND scope = 'DIAGNOSTICS'
                  AND revoked_at IS NULL AND expires_at > ?
                FOR SHARE
                """,
                (result, row) -> result.getObject("id", UUID.class),
                grantId, organizationId, Timestamp.from(now)
        );
        if (allowed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A current explicit support grant is required");
        }
        jdbc.update(
                """
                INSERT INTO support_access_events(
                    id, support_access_grant_id, organization_id, action,
                    support_subject_hash, occurred_at
                ) VALUES (?, ?, ?, 'ACCESSED', ?, ?)
                """,
                UuidV7Generator.generate(), grantId, organizationId,
                RequestFingerprint.sha256(supportSubject == null ? "internal" : supportSubject), Timestamp.from(now)
        );
        Organization organization = jdbc.query(
                "SELECT name, status, created_at FROM organizations WHERE id = ?",
                (result, row) -> new Organization(
                        result.getString("name"), result.getString("status"),
                        result.getTimestamp("created_at").toInstant()
                ), organizationId
        ).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found")
        );
        long activeMembers = count(
                "SELECT count(*) FROM memberships WHERE organization_id = ? AND status = 'ACTIVE'", organizationId
        );
        Subscription subscription = jdbc.query(
                """
                SELECT p.code, s.status, s.current_period_end
                FROM subscriptions s JOIN pricing_plans p ON p.id = s.plan_id
                WHERE s.organization_id = ? AND s.status IN ('ACTIVE', 'PAST_DUE', 'SCHEDULED')
                ORDER BY s.current_period_end DESC LIMIT 1
                """,
                (result, row) -> new Subscription(
                        result.getString("code"), result.getString("status"),
                        result.getTimestamp("current_period_end").toInstant()
                ), organizationId
        ).stream().findFirst().orElse(null);
        Map<String, Long> jobs = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT state, count(*) AS total FROM analysis_jobs
                WHERE organization_id = ? AND queued_at >= ? GROUP BY state ORDER BY state
                """,
                (result, row) -> Map.entry(result.getString("state"), result.getLong("total")),
                organizationId, Timestamp.from(now.minus(Duration.ofDays(7)))
        ).forEach(entry -> jobs.put(entry.getKey(), entry.getValue()));
        return new Diagnostics(
                organization.name(), organization.status(), organization.createdAt(), activeMembers, subscription,
                jobs, count("SELECT count(*) FROM billing_orders WHERE organization_id = ? AND state = 'PENDING'", organizationId),
                count("SELECT count(*) FROM notification_jobs WHERE organization_id = ? AND state = 'DEAD'", organizationId),
                count("SELECT count(*) FROM webhook_deliveries WHERE organization_id = ? AND state = 'DEAD_LETTER'", organizationId),
                jdbc.query("SELECT max(received_at) FROM payments WHERE organization_id = ?",
                        (result, row) -> result.getTimestamp(1) == null ? null : result.getTimestamp(1).toInstant(),
                        organizationId).getFirst(),
                now
        );
    }

    private long count(String sql, Object... parameters) {
        Long value = jdbc.queryForObject(sql, Long.class, parameters);
        return value == null ? 0 : value;
    }

    private void lockOrganization(UUID organizationId) {
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(?)",
                Object.class,
                organizationId.getMostSignificantBits() ^ organizationId.getLeastSignificantBits()
        );
    }

    private void ownerEvent(UUID grantId, CurrentActor actor, String action, Instant now) {
        jdbc.update(
                """
                INSERT INTO support_access_events(
                    id, support_access_grant_id, organization_id, action, actor_user_id, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UuidV7Generator.generate(), grantId, actor.organizationId(), action,
                actor.userId(), Timestamp.from(now)
        );
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required and too long");
        }
        return value.trim();
    }

    private static String optionalText(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return text(value, "Ticket reference", max);
    }

    public record CreateGrant(String reason, String ticketReference, int durationMinutes) { }

    public record Grant(
            UUID id, String scope, String reason, String ticketReference, String state,
            Instant createdAt, Instant expiresAt, Instant revokedAt
    ) { }

    public record Diagnostics(
            String organizationName, String organizationStatus, Instant organizationCreatedAt,
            long activeMembers, Subscription subscription, Map<String, Long> analysisJobsLast7Days,
            long pendingBillingOrders, long deadNotifications, long deadWebhookDeliveries,
            Instant lastPaymentAt, Instant generatedAt
    ) { }

    public record Subscription(String planCode, String status, Instant periodEnd) { }
    private record Organization(String name, String status, Instant createdAt) { }
}
