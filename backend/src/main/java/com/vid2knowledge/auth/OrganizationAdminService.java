package com.vid2knowledge.auth;

import com.vid2knowledge.common.id.UuidV7Generator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class OrganizationAdminService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OrganizationAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.clock = Clock.systemUTC();
    }

    public List<Member> members(UUID organizationId) {
        return jdbc.query(
                """
                SELECT u.id, u.email, u.display_name, m.role, m.status, m.joined_at
                FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.organization_id = ?
                ORDER BY CASE m.role WHEN 'OWNER' THEN 0 ELSE 1 END, u.display_name, u.id
                LIMIT 2000
                """,
                (result, row) -> new Member(
                        result.getObject("id", UUID.class), result.getString("email"),
                        result.getString("display_name"), CurrentActor.Role.valueOf(result.getString("role")),
                        result.getString("status"), result.getTimestamp("joined_at").toInstant()
                ),
                organizationId
        );
    }

    public List<Invitation> invitations(UUID organizationId) {
        return jdbc.query(
                """
                SELECT id, email, role, expires_at, accepted_at, revoked_at, created_at
                FROM invitations WHERE organization_id = ?
                ORDER BY created_at DESC, id DESC LIMIT 500
                """,
                (result, row) -> new Invitation(
                        result.getObject("id", UUID.class), result.getString("email"),
                        CurrentActor.Role.valueOf(result.getString("role")),
                        state(result.getTimestamp("accepted_at"), result.getTimestamp("revoked_at"),
                                result.getTimestamp("expires_at").toInstant(), clock.instant()),
                        result.getTimestamp("expires_at").toInstant(), result.getTimestamp("created_at").toInstant()
                ),
                organizationId
        );
    }

    @Transactional
    public Member changeRole(CurrentActor actor, UUID userId, CurrentActor.Role role, String correlationId) {
        if (role == CurrentActor.Role.OWNER || role == CurrentActor.Role.SUPPORT_READONLY) {
            throw new IllegalArgumentException("Use ownership transfer for this role");
        }
        int updated = jdbc.update(
                """
                UPDATE memberships SET role = ?, updated_at = ?
                WHERE organization_id = ? AND user_id = ? AND role <> 'OWNER' AND status = 'ACTIVE'
                """,
                role.name(), Timestamp.from(clock.instant()), actor.organizationId(), userId
        );
        if (updated != 1) throw notFound();
        audit(actor, "MEMBER_ROLE_CHANGED", userId, correlationId);
        return member(actor.organizationId(), userId);
    }

    @Transactional
    public void deactivate(CurrentActor actor, UUID userId, String correlationId) {
        int updated = jdbc.update(
                """
                UPDATE memberships SET status = 'SUSPENDED', updated_at = ?
                WHERE organization_id = ? AND user_id = ? AND role <> 'OWNER' AND status = 'ACTIVE'
                """,
                Timestamp.from(clock.instant()), actor.organizationId(), userId
        );
        if (updated != 1) throw notFound();
        audit(actor, "MEMBER_DEACTIVATED", userId, correlationId);
    }

    @Transactional
    public void transferOwnership(CurrentActor actor, UUID nextOwnerId, String correlationId) {
        if (actor.userId().equals(nextOwnerId)) {
            throw new IllegalArgumentException("The selected member is already the owner");
        }
        Integer eligible = jdbc.queryForObject(
                "SELECT count(*) FROM memberships WHERE organization_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Integer.class, actor.organizationId(), nextOwnerId
        );
        if (eligible == null || eligible != 1) throw notFound();
        Instant now = clock.instant();
        int previousOwner = jdbc.update(
                "UPDATE memberships SET role = 'ADMIN', updated_at = ? WHERE organization_id = ? AND user_id = ? AND role = 'OWNER' AND status = 'ACTIVE'",
                Timestamp.from(now), actor.organizationId(), actor.userId()
        );
        if (previousOwner != 1) throw notFound();
        int nextOwner = jdbc.update(
                "UPDATE memberships SET role = 'OWNER', updated_at = ? WHERE organization_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Timestamp.from(now), actor.organizationId(), nextOwnerId
        );
        if (nextOwner != 1) throw notFound();
        audit(actor, "OWNERSHIP_TRANSFERRED", nextOwnerId, correlationId);
    }

    private Member member(UUID organizationId, UUID userId) {
        return jdbc.queryForObject(
                """
                SELECT u.id, u.email, u.display_name, m.role, m.status, m.joined_at
                FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.organization_id = ? AND m.user_id = ?
                """,
                (result, row) -> new Member(
                        result.getObject("id", UUID.class), result.getString("email"),
                        result.getString("display_name"), CurrentActor.Role.valueOf(result.getString("role")),
                        result.getString("status"), result.getTimestamp("joined_at").toInstant()
                ), organizationId, userId
        );
    }

    private void audit(CurrentActor actor, String action, UUID resourceId, String correlationId) {
        jdbc.update(
                """
                INSERT INTO audit_logs(id, organization_id, actor_user_id, action, resource_type,
                                       resource_id, correlation_id, created_at)
                VALUES (?, ?, ?, ?, 'Membership', ?, ?, ?)
                """,
                UuidV7Generator.generate(), actor.organizationId(), actor.userId(), action, resourceId,
                correlationId, Timestamp.from(clock.instant())
        );
    }

    private static String state(Timestamp accepted, Timestamp revoked, Instant expiresAt, Instant now) {
        if (accepted != null) return "ACCEPTED";
        if (revoked != null) return "REVOKED";
        return expiresAt.isAfter(now) ? "PENDING" : "EXPIRED";
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Active member not found");
    }

    public record Member(UUID id, String email, String displayName, CurrentActor.Role role, String status, Instant joinedAt) {}
    public record Invitation(UUID id, String email, CurrentActor.Role role, String state, Instant expiresAt, Instant createdAt) {}
}
