package com.vid2knowledge.auth;

import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.config.CommercialProperties;
import com.vid2knowledge.notification.NotificationQueue;
import com.vid2knowledge.usage.domain.UsageMetric;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class IdentityService {

    private final JdbcTemplate jdbc;
    private final CommercialProperties commercial;
    private final NotificationQueue notifications;
    private final Clock clock;

    public IdentityService(JdbcTemplate jdbc, CommercialProperties commercial) {
        this(jdbc, commercial, null, Clock.systemUTC());
    }

    public IdentityService(JdbcTemplate jdbc, CommercialProperties commercial, Clock clock) {
        this(jdbc, commercial, null, clock);
    }

    @Autowired
    public IdentityService(JdbcTemplate jdbc, CommercialProperties commercial, NotificationQueue notifications) {
        this(jdbc, commercial, notifications, Clock.systemUTC());
    }

    private IdentityService(
            JdbcTemplate jdbc, CommercialProperties commercial, NotificationQueue notifications, Clock clock
    ) {
        this.jdbc = jdbc;
        this.commercial = commercial;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public Me provision(String subject, String email, String displayName) {
        requireClaim(subject, "JWT subject");
        requireClaim(email, "verified JWT email");
        Long blocked = jdbc.queryForObject(
                "SELECT count(*) FROM deleted_identity_blocks WHERE subject_hash = ?",
                Long.class, RequestFingerprint.sha256(subject)
        );
        if (blocked != null && blocked > 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "This identity was deleted and cannot be reprovisioned"
            );
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String safeDisplayName = displayName == null || displayName.isBlank()
                ? normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
                : displayName.trim();
        if (safeDisplayName.length() > 160) {
            safeDisplayName = safeDisplayName.substring(0, 160);
        }
        Instant now = clock.instant();
        try {
            jdbc.update(
                    """
                    INSERT INTO users(
                        id, auth_subject, email, normalized_email, display_name, last_login_at,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (auth_subject) DO UPDATE
                    SET email = EXCLUDED.email, normalized_email = EXCLUDED.normalized_email,
                        display_name = EXCLUDED.display_name, last_login_at = EXCLUDED.last_login_at,
                        updated_at = EXCLUDED.updated_at
                    WHERE users.status = 'ACTIVE'
                    """,
                    UuidV7Generator.generate(), subject, email.trim(), normalizedEmail, safeDisplayName,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
            );
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("This email is already linked to another identity", exception);
        }
        UUID userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE auth_subject = ? AND status = 'ACTIVE'",
                UUID.class,
                subject
        );
        List<Membership> memberships = jdbc.query(
                """
                SELECT o.id, o.name, o.slug, m.role
                FROM memberships m JOIN organizations o ON o.id = m.organization_id
                WHERE m.user_id = ? AND m.status = 'ACTIVE' AND o.status = 'ACTIVE'
                ORDER BY o.created_at, o.id
                """,
                (result, row) -> new Membership(
                        result.getObject("id", UUID.class), result.getString("name"),
                        result.getString("slug"), CurrentActor.Role.valueOf(result.getString("role"))
                ),
                userId
        );
        return new Me(userId, email.trim(), safeDisplayName, memberships);
    }

    @Transactional
    public Membership createOrganization(
            String subject,
            String email,
            String displayName,
            String name,
            String slug,
            String correlationId
    ) {
        return createOrganization(subject, email, displayName, name, slug, null, correlationId);
    }

    @Transactional
    public Membership createOrganization(
            String subject,
            String email,
            String displayName,
            String name,
            String slug,
            String acquisitionSource,
            String correlationId
    ) {
        Me me = provision(subject, email, displayName);
        Instant now = clock.instant();
        UUID organizationId = UuidV7Generator.generate();
        AcquisitionSource source = AcquisitionSource.parse(acquisitionSource);
        jdbc.update(
                "INSERT INTO organizations(id, name, slug, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                organizationId, name.trim(), slug, Timestamp.from(now), Timestamp.from(now)
        );
        jdbc.update(
                "INSERT INTO organization_acquisition_attributions(organization_id, source, attributed_at) VALUES (?, ?, ?)",
                organizationId, source.name(), Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO memberships(organization_id, user_id, role, status, joined_at)
                VALUES (?, ?, 'OWNER', 'ACTIVE', ?)
                """,
                organizationId, me.id(), Timestamp.from(now)
        );
        if (commercial.trialProcessedVideoSeconds() > 0) {
            jdbc.update(
                    """
                    INSERT INTO entitlements(
                        id, organization_id, metric, allowance, period_start, period_end,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UuidV7Generator.generate(), organizationId, UsageMetric.PROCESSED_VIDEO_SECOND.name(),
                    commercial.trialProcessedVideoSeconds(), Timestamp.from(now),
                    Timestamp.from(now.plus(commercial.trialDuration())), Timestamp.from(now), Timestamp.from(now)
            );
        }
        if (commercial.trialQaQueries() > 0) {
            jdbc.update(
                    """
                    INSERT INTO entitlements(
                        id, organization_id, metric, allowance, period_start, period_end,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UuidV7Generator.generate(), organizationId, UsageMetric.QA_QUERY.name(),
                    commercial.trialQaQueries(), Timestamp.from(now),
                    Timestamp.from(now.plus(commercial.trialDuration())), Timestamp.from(now), Timestamp.from(now)
            );
        }
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, created_at
                ) VALUES (?, ?, ?, 'ORGANIZATION_CREATED', 'Organization', ?, ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, me.id(), organizationId,
                correlationId, Timestamp.from(now)
        );
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, 'OrganizationCreated', 1, 'Organization', ?, ?,
                          jsonb_build_object(
                              'organizationId', CAST(? AS text), 'acquisitionSource', ?
                          ), ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, organizationId, correlationId, organizationId, source.name(),
                Timestamp.from(now), Timestamp.from(now)
        );
        if (notifications != null && (commercial.trialProcessedVideoSeconds() > 0
                || commercial.trialQaQueries() > 0)) {
            notifications.onboarding(
                    organizationId, me.id(), me.email(), now.plus(commercial.trialDuration())
            );
        }
        return new Membership(organizationId, name.trim(), slug, CurrentActor.Role.OWNER);
    }

    private static void requireClaim(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
    }

    public record Me(UUID id, String email, String displayName, List<Membership> organizations) {
    }

    public record Membership(UUID id, String name, String slug, CurrentActor.Role role) {
    }

    private enum AcquisitionSource {
        DIRECT,
        SAMPLE_COURSE;

        private static AcquisitionSource parse(String value) {
            if (value == null || value.isBlank()) return DIRECT;
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Unsupported acquisition source", invalid
                );
            }
        }
    }
}
