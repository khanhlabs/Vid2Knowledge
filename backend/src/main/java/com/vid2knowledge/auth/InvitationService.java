package com.vid2knowledge.auth;

import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.config.CommercialProperties;
import com.vid2knowledge.notification.NotificationQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InvitationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final IdentityService identities;
    private final CommercialProperties commercial;
    private final NotificationQueue notifications;
    private final Clock clock;

    public InvitationService(
            JdbcTemplate jdbc,
            IdentityService identities,
            CommercialProperties commercial,
            NotificationQueue notifications
    ) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.commercial = commercial;
        this.notifications = notifications;
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public InvitationCreated invite(
            CurrentActor actor,
            String email,
            CurrentActor.Role role,
            String correlationId
    ) {
        if (role == CurrentActor.Role.OWNER || role == CurrentActor.Role.SUPPORT_READONLY) {
            throw new IllegalArgumentException("This role cannot be assigned by invitation");
        }
        String normalizedEmail = normalize(email);
        Instant now = clock.instant();
        jdbc.update(
                """
                UPDATE invitations SET revoked_at = ?
                WHERE organization_id = ? AND normalized_email = ?
                  AND accepted_at IS NULL AND revoked_at IS NULL
                """,
                Timestamp.from(now), actor.organizationId(), normalizedEmail
        );
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String hash = RequestFingerprint.sha256(token);
        UUID id = UuidV7Generator.generate();
        Instant expiresAt = now.plus(commercial.invitationTtl());
        jdbc.update(
                """
                INSERT INTO invitations(
                    id, organization_id, email, normalized_email, role, token_hash,
                    invited_by, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, actor.organizationId(), email.trim(), normalizedEmail, role.name(), hash,
                actor.userId(), Timestamp.from(expiresAt), Timestamp.from(now)
        );
        audit(actor.organizationId(), actor.userId(), "MEMBER_INVITED", id, correlationId, now);
        outbox(actor.organizationId(), "MemberInvited", id, correlationId, now);
        notifications.invitation(actor.organizationId(), id, email.trim(), role, token, expiresAt);
        return new InvitationCreated(id, token, email.trim(), role, expiresAt);
    }

    @Transactional
    public IdentityService.Membership accept(
            String token,
            String subject,
            String verifiedEmail,
            String displayName,
            String correlationId
    ) {
        String hash = RequestFingerprint.sha256(token);
        Instant now = clock.instant();
        List<PendingInvitation> pending = jdbc.query(
                """
                SELECT id, organization_id, normalized_email, role
                FROM invitations
                WHERE token_hash = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at > ?
                FOR UPDATE
                """,
                (result, row) -> new PendingInvitation(
                        result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                        result.getString("normalized_email"), CurrentActor.Role.valueOf(result.getString("role"))
                ),
                hash, Timestamp.from(now)
        );
        PendingInvitation invitation = pending.stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation is invalid or expired")
        );
        if (!invitation.normalizedEmail().equals(normalize(verifiedEmail))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invitation email does not match");
        }
        IdentityService.Me me = identities.provision(subject, verifiedEmail, displayName);
        jdbc.update(
                """
                INSERT INTO memberships(organization_id, user_id, role, status, joined_at)
                VALUES (?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (organization_id, user_id) DO UPDATE
                SET role = EXCLUDED.role, status = 'ACTIVE', joined_at = EXCLUDED.joined_at, updated_at = EXCLUDED.joined_at
                """,
                invitation.organizationId(), me.id(), invitation.role().name(), Timestamp.from(now)
        );
        int accepted = jdbc.update(
                "UPDATE invitations SET accepted_at = ? WHERE id = ? AND accepted_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(now), invitation.id()
        );
        if (accepted != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation was already used");
        }
        audit(invitation.organizationId(), me.id(), "INVITATION_ACCEPTED", invitation.id(), correlationId, now);
        outbox(invitation.organizationId(), "InvitationAccepted", invitation.id(), correlationId, now);
        String[] organization = jdbc.queryForObject(
                "SELECT name, slug FROM organizations WHERE id = ?",
                (result, row) -> new String[]{result.getString("name"), result.getString("slug")},
                invitation.organizationId()
        );
        return new IdentityService.Membership(
                invitation.organizationId(), organization[0], organization[1], invitation.role()
        );
    }

    @Transactional
    public void revoke(CurrentActor actor, UUID invitationId, String correlationId) {
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE invitations SET revoked_at = ?
                WHERE id = ? AND organization_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
                """,
                Timestamp.from(now), invitationId, actor.organizationId()
        );
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pending invitation not found");
        }
        audit(actor.organizationId(), actor.userId(), "INVITATION_REVOKED", invitationId, correlationId, now);
        outbox(actor.organizationId(), "InvitationRevoked", invitationId, correlationId, now);
    }

    private void audit(UUID organizationId, UUID actorId, String action, UUID invitationId, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO audit_logs(
                    id, organization_id, actor_user_id, action, resource_type,
                    resource_id, correlation_id, created_at
                ) VALUES (?, ?, ?, ?, 'Invitation', ?, ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, actorId, action, invitationId,
                correlationId, Timestamp.from(now)
        );
    }

    private void outbox(UUID organizationId, String eventType, UUID invitationId, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO outbox_events(
                    id, organization_id, event_type, event_version, aggregate_type,
                    aggregate_id, correlation_id, payload_json, occurred_at, available_at
                ) VALUES (?, ?, ?, 1, 'Invitation', ?, ?,
                          jsonb_build_object('invitationId', CAST(? AS text)), ?, ?)
                """,
                UuidV7Generator.generate(), organizationId, eventType, invitationId, correlationId,
                invitationId, Timestamp.from(now), Timestamp.from(now)
        );
    }

    private static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record InvitationCreated(
            UUID id, String token, String email, CurrentActor.Role role, Instant expiresAt
    ) {
    }

    private record PendingInvitation(
            UUID id, UUID organizationId, String normalizedEmail, CurrentActor.Role role
    ) {
    }
}
