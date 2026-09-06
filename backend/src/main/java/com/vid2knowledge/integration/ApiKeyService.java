package com.vid2knowledge.integration;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class ApiKeyService {
    public static final Set<String> ALLOWED_SCOPES = Set.of("catalog:read", "analytics:read");
    private static final Duration MAX_LIFETIME = Duration.ofDays(365);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ApiKeyService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    ApiKeyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<ApiKeyView> list(UUID organizationId) {
        return jdbc.query(
                """
                SELECT id, name, token_prefix, scopes, expires_at, last_used_at, revoked_at, created_at
                FROM integration_api_keys WHERE organization_id = ? ORDER BY created_at DESC, id DESC
                """,
                (result, row) -> new ApiKeyView(
                        result.getObject("id", UUID.class), result.getString("name"),
                        result.getString("token_prefix"), stringSet(result.getArray("scopes")),
                        result.getTimestamp("expires_at").toInstant(),
                        instant(result.getTimestamp("last_used_at")),
                        instant(result.getTimestamp("revoked_at")),
                        result.getTimestamp("created_at").toInstant()
                ),
                organizationId
        );
    }

    @Transactional
    public ApiKeyCreated create(
            CurrentActor actor, String name, Set<String> requestedScopes, Instant expiresAt, String correlationId
    ) {
        requireBusinessPlan(actor.organizationId());
        Long activeKeyCount = jdbc.queryForObject(
                "SELECT count(*) FROM integration_api_keys WHERE organization_id = ? AND revoked_at IS NULL AND expires_at > ?",
                Long.class, actor.organizationId(), Timestamp.from(clock.instant())
        );
        if (activeKeyCount != null && activeKeyCount >= 10) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "At most 10 active API keys are allowed");
        }
        String safeName = requireName(name);
        Set<String> scopes = validateScopes(requestedScopes);
        Instant now = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_LIFETIME))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API key expiry must be within 365 days");
        }
        UUID id = UuidV7Generator.generate();
        String publicPart = id.toString().replace("-", "").substring(0, 16);
        byte[] secretBytes = new byte[32];
        RANDOM.nextBytes(secretBytes);
        String token = "v2k_live_" + publicPart + "_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String prefix = token.substring(0, Math.min(25, token.length()));
        jdbc.update(
                """
                INSERT INTO integration_api_keys(
                    id, organization_id, name, token_prefix, token_hash, scopes,
                    expires_at, created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS text[]), ?, ?, ?)
                """,
                id, actor.organizationId(), safeName, prefix, RequestFingerprint.sha256(token),
                pgArray(scopes), Timestamp.from(expiresAt), actor.userId(), Timestamp.from(now)
        );
        audit(actor, "INTEGRATION_API_KEY_CREATED", id, correlationId, now);
        return new ApiKeyCreated(id, safeName, prefix, scopes, expiresAt, token, now);
    }

    @Transactional
    public void revoke(CurrentActor actor, UUID keyId, String correlationId) {
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                UPDATE integration_api_keys SET revoked_at = ?
                WHERE organization_id = ? AND id = ? AND revoked_at IS NULL
                """,
                Timestamp.from(now), actor.organizationId(), keyId
        );
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active API key not found");
        audit(actor, "INTEGRATION_API_KEY_REVOKED", keyId, correlationId, now);
    }

    @Transactional
    public IntegrationPrincipal authenticate(String token) {
        if (token == null || !token.startsWith("v2k_live_") || token.length() > 160) return null;
        Instant now = clock.instant();
        List<IntegrationPrincipal> matches = jdbc.query(
                """
                SELECT k.id, k.organization_id, k.scopes
                FROM integration_api_keys k
                JOIN organizations o ON o.id = k.organization_id
                JOIN subscriptions s ON s.organization_id = k.organization_id
                JOIN pricing_plans p ON p.id = s.plan_id
                WHERE k.token_hash = ? AND k.revoked_at IS NULL AND k.expires_at > ?
                  AND o.status = 'ACTIVE' AND s.status = 'ACTIVE' AND s.current_period_end > ?
                  AND p.code LIKE 'BUSINESS\\_%' ESCAPE '\\'
                ORDER BY s.current_period_end DESC LIMIT 1
                """,
                (result, row) -> new IntegrationPrincipal(
                        result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                        stringSet(result.getArray("scopes"))
                ),
                RequestFingerprint.sha256(token), Timestamp.from(now), Timestamp.from(now)
        );
        if (matches.isEmpty()) return null;
        IntegrationPrincipal principal = matches.getFirst();
        jdbc.update(
                "UPDATE integration_api_keys SET last_used_at = ? WHERE id = ? AND (last_used_at IS NULL OR last_used_at < ?)",
                Timestamp.from(now), principal.apiKeyId(), Timestamp.from(now.minus(Duration.ofHours(1)))
        );
        return principal;
    }

    public void requireBusinessPlan(UUID organizationId) {
        Boolean eligible = jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM subscriptions s JOIN pricing_plans p ON p.id = s.plan_id
                    WHERE s.organization_id = ? AND s.status = 'ACTIVE' AND s.current_period_end > ?
                      AND p.code LIKE 'BUSINESS\\_%' ESCAPE '\\'
                )
                """,
                Boolean.class, organizationId, Timestamp.from(clock.instant())
        );
        if (!Boolean.TRUE.equals(eligible)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Business plan is required for integrations");
        }
    }

    private Set<String> validateScopes(Set<String> requested) {
        if (requested == null || requested.isEmpty() || !ALLOWED_SCOPES.containsAll(requested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported or empty API key scopes");
        }
        return Set.copyOf(new LinkedHashSet<>(requested));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API key name must contain 1-120 characters");
        }
        return name.trim();
    }

    private void audit(CurrentActor actor, String action, UUID id, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(id, organization_id, actor_user_id, action, resource_type,
                                       resource_id, correlation_id, created_at)
                VALUES (?, ?, ?, ?, 'IntegrationApiKey', ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action, id,
                correlationId, Timestamp.from(now)
        );
    }

    private static Set<String> stringSet(Array array) throws java.sql.SQLException {
        return Set.of((String[]) array.getArray());
    }

    private static String pgArray(Set<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ApiKeyView(
            UUID id, String name, String tokenPrefix, Set<String> scopes, Instant expiresAt,
            Instant lastUsedAt, Instant revokedAt, Instant createdAt
    ) {}

    public record ApiKeyCreated(
            UUID id, String name, String tokenPrefix, Set<String> scopes, Instant expiresAt,
            String token, Instant createdAt
    ) {}
}
