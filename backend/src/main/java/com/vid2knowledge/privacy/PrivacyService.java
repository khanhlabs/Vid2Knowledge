package com.vid2knowledge.privacy;

import com.vid2knowledge.common.id.RequestFingerprint;
import com.vid2knowledge.common.id.UuidV7Generator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PrivacyService {
    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final AuthIdentityAdmin identityAdmin;
    private final Clock clock = Clock.systemUTC();

    public PrivacyService(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            AuthIdentityAdmin identityAdmin
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.identityAdmin = identityAdmin;
    }

    public JsonNode export(UUID userId) {
        String json = jdbc.queryForObject(
                """
                SELECT jsonb_build_object(
                    'exportedAt', CAST(CURRENT_TIMESTAMP AS text),
                    'profile', (SELECT jsonb_build_object(
                        'id', u.id, 'email', u.email, 'displayName', u.display_name,
                        'locale', u.locale, 'createdAt', u.created_at, 'lastLoginAt', u.last_login_at
                    ) FROM users u WHERE u.id = ? AND u.status = 'ACTIVE'),
                    'memberships', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'organizationId', m.organization_id, 'organizationName', o.name,
                        'role', m.role, 'status', m.status, 'joinedAt', m.joined_at
                    ) ORDER BY m.joined_at) FROM memberships m JOIN organizations o ON o.id = m.organization_id
                      WHERE m.user_id = ?), '[]'::jsonb),
                    'assignments', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'assignmentId', ar.assignment_id, 'state', lp.status, 'assignedAt', ar.assigned_at,
                        'progressPercent', lp.progress_percent, 'completedAt', lp.completed_at
                    ) ORDER BY ar.assigned_at) FROM assignment_recipients ar
                      LEFT JOIN learner_progress lp ON lp.assignment_id = ar.assignment_id AND lp.user_id = ar.user_id
                      WHERE ar.user_id = ?), '[]'::jsonb),
                    'assessmentAttempts', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'id', a.id, 'snapshotId', a.snapshot_id, 'scorePercent', a.score_percent,
                        'submittedAt', a.submitted_at
                    ) ORDER BY a.submitted_at) FROM assessment_attempts a WHERE a.user_id = ?), '[]'::jsonb),
                    'flashcardReviews', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'assignmentId', r.assignment_id, 'cardId', r.card_id, 'rating', r.rating,
                        'reviewedAt', r.reviewed_at
                    ) ORDER BY r.reviewed_at) FROM flashcard_review_log r WHERE r.user_id = ?), '[]'::jsonb),
                    'qaThreads', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'id', t.id, 'assignmentId', t.assignment_id, 'createdAt', t.created_at
                    ) ORDER BY t.created_at) FROM qa_threads t WHERE t.user_id = ?), '[]'::jsonb),
                    'legalAcceptances', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'policySetVersion', l.policy_set_version, 'termsVersion', l.terms_version,
                        'privacyVersion', l.privacy_version, 'acceptableUseVersion', l.acceptable_use_version,
                        'aiNoticeVersion', l.ai_notice_version, 'acceptedAt', l.accepted_at
                    ) ORDER BY l.accepted_at) FROM legal_acceptances l WHERE l.user_id = ?), '[]'::jsonb),
                    'notificationPreferences', COALESCE((SELECT jsonb_build_object(
                        'productGuidanceEnabled', n.product_guidance_enabled,
                        'assignmentRemindersEnabled', n.assignment_reminders_enabled,
                        'marketingEnabled', n.marketing_enabled, 'updatedAt', n.updated_at
                    ) FROM notification_preferences n WHERE n.user_id = ?), jsonb_build_object(
                        'productGuidanceEnabled', TRUE, 'assignmentRemindersEnabled', TRUE,
                        'marketingEnabled', FALSE, 'updatedAt', NULL)),
                    'notificationPreferenceChanges', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'productGuidanceEnabled', c.product_guidance_enabled,
                        'assignmentRemindersEnabled', c.assignment_reminders_enabled,
                        'marketingEnabled', c.marketing_enabled, 'changedAt', c.changed_at
                    ) ORDER BY c.changed_at, c.id) FROM notification_preference_changes c
                      WHERE c.user_id = ?), '[]'::jsonb),
                    'deletionRequests', COALESCE((SELECT jsonb_agg(jsonb_build_object(
                        'id', d.id, 'state', d.state, 'requestedAt', d.requested_at,
                        'scheduledFor', d.scheduled_for, 'cancelledAt', d.cancelled_at,
                        'completedAt', d.completed_at
                    ) ORDER BY d.requested_at) FROM privacy_deletion_requests d WHERE d.user_id = ?), '[]'::jsonb)
                )::text
                """,
                String.class, userId, userId, userId, userId, userId, userId, userId, userId, userId, userId
        );
        try {
            return mapper.readTree(json);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialize privacy export", failure);
        }
    }

    public DeletionRequest requestDeletion(UUID userId) {
        return transactions.execute(status -> {
            Long soleOwnerships = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM memberships owner
                    WHERE owner.user_id = ? AND owner.role = 'OWNER' AND owner.status = 'ACTIVE'
                      AND NOT EXISTS (
                          SELECT 1 FROM memberships replacement
                          WHERE replacement.organization_id = owner.organization_id
                            AND replacement.user_id <> owner.user_id
                            AND replacement.role = 'OWNER' AND replacement.status = 'ACTIVE'
                      )
                    """,
                    Long.class, userId
            );
            if (soleOwnerships != null && soleOwnerships > 0) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Transfer organization ownership before deleting this account"
                );
            }
            List<DeletionRequest> existing = findActive(userId);
            if (!existing.isEmpty()) {
                return existing.getFirst();
            }
            Instant now = clock.instant();
            UUID id = UuidV7Generator.generate();
            jdbc.update(
                    """
                    INSERT INTO privacy_deletion_requests(id, user_id, requested_at, scheduled_for)
                    VALUES (?, ?, ?, ?)
                    """,
                    id, userId, Timestamp.from(now), Timestamp.from(now.plus(Duration.ofDays(7)))
            );
            return findActive(userId).getFirst();
        });
    }

    public DeletionRequest activeDeletion(UUID userId) {
        return findActive(userId).stream().findFirst().orElse(null);
    }

    public void cancelDeletion(UUID userId) {
        int updated = jdbc.update(
                """
                UPDATE privacy_deletion_requests SET state = 'CANCELLED', cancelled_at = ?
                WHERE user_id = ? AND state = 'REQUESTED'
                """,
                Timestamp.from(clock.instant()), userId
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active deletion request not found");
        }
    }

    public ProcessingResult processDueDeletions() {
        List<UUID> due = jdbc.query(
                """
                SELECT id FROM privacy_deletion_requests
                WHERE state IN ('REQUESTED', 'IDENTITY_PENDING') AND scheduled_for <= ?
                  AND (provider_next_attempt_at IS NULL OR provider_next_attempt_at <= CURRENT_TIMESTAMP)
                ORDER BY COALESCE(provider_next_attempt_at, scheduled_for), id LIMIT 3
                """,
                (result, row) -> result.getObject("id", UUID.class), Timestamp.from(clock.instant())
        );
        int completed = 0;
        for (UUID id : due) {
            try {
                PendingIdentity pending = transactions.execute(status -> eraseLocally(id));
                if (pending == null) continue;
                if (!identityAdmin.enabled()) throw new IllegalStateException("Auth identity deletion is disabled");
                identityAdmin.delete(pending.providerUserId());
                if (Boolean.TRUE.equals(transactions.execute(status -> markProviderDeleted(id)))) completed++;
            } catch (RuntimeException failure) {
                recordProviderFailure(id, failure);
            }
        }
        Long reviewCount = jdbc.queryForObject(
                "SELECT count(*) FROM privacy_deletion_requests WHERE state = 'IDENTITY_REVIEW'", Long.class);
        if (reviewCount != null && reviewCount > 0) {
            log.error("AUTH_IDENTITY_DELETION_FAILED legacyReviewCount={}", reviewCount);
        }
        return new ProcessingResult(due.size(), completed);
    }

    private PendingIdentity eraseLocally(UUID requestId) {
        Instant now = clock.instant();
        List<UserForDeletion> users = jdbc.query(
                """
                SELECT d.state, d.auth_provider_user_id, u.id, u.auth_subject, u.normalized_email
                FROM privacy_deletion_requests d JOIN users u ON u.id = d.user_id
                WHERE d.id = ? AND d.state IN ('REQUESTED', 'IDENTITY_PENDING') AND d.scheduled_for <= ?
                FOR UPDATE OF d, u
                """,
                (result, row) -> new UserForDeletion(
                        result.getString("state"), result.getObject("auth_provider_user_id", UUID.class),
                        result.getObject("id", UUID.class), result.getString("auth_subject"),
                        result.getString("normalized_email")
                ), requestId, Timestamp.from(now)
        );
        if (users.isEmpty()) return null;
        UserForDeletion user = users.getFirst();
        if ("IDENTITY_PENDING".equals(user.state())) {
            return new PendingIdentity(user.providerUserId());
        }
        UUID providerUserId;
        try {
            providerUserId = UUID.fromString(user.subject());
        } catch (IllegalArgumentException invalidSubject) {
            throw new IllegalStateException("Supabase auth subject is not a UUID", invalidSubject);
        }
        List<Boolean> ownerships = jdbc.query(
                "SELECT role = 'OWNER' AND status = 'ACTIVE' AS owns FROM memberships WHERE user_id = ? ORDER BY organization_id FOR UPDATE",
                (result, row) -> result.getBoolean("owns"), user.id());
        if (ownerships.contains(true)) {
            throw new IllegalStateException("Transfer organization ownership before account erasure");
        }
        jdbc.update("UPDATE memberships SET status = 'LEFT', updated_at = ? WHERE user_id = ?",
                Timestamp.from(now), user.id());
        jdbc.update(
                """
                UPDATE invitations SET revoked_at = CASE
                        WHEN accepted_at IS NULL THEN COALESCE(revoked_at, ?) ELSE revoked_at END,
                    email = 'deleted+' || CAST(id AS text) || '@redacted.invalid',
                    normalized_email = 'deleted+' || CAST(id AS text) || '@redacted.invalid'
                WHERE normalized_email = ?
                """,
                Timestamp.from(now), user.normalizedEmail()
        );
        jdbc.update(
                """
                UPDATE notification_jobs SET recipient_email = 'deleted@redacted.invalid',
                    encrypted_payload = 'REDACTED',
                    state = CASE WHEN state IN ('SENT', 'CANCELLED') THEN state ELSE 'DEAD' END,
                    dead_lettered_at = CASE WHEN state IN ('SENT', 'CANCELLED') THEN dead_lettered_at ELSE ? END,
                    lease_owner = NULL, lease_expires_at = NULL,
                    updated_at = ? WHERE lower(recipient_email) = ?
                """,
                Timestamp.from(now), Timestamp.from(now), user.normalizedEmail()
        );
        jdbc.update(
                """
                UPDATE qa_messages SET content = '[deleted]'
                WHERE thread_id IN (SELECT id FROM qa_threads WHERE user_id = ?)
                """,
                user.id()
        );
        jdbc.update(
                """
                INSERT INTO deleted_identity_blocks(subject_hash, deletion_request_id, blocked_at)
                VALUES (?, ?, ?)
                """,
                RequestFingerprint.sha256(user.subject()), requestId, Timestamp.from(now)
        );
        String deletedEmail = "deleted+" + user.id() + "@redacted.invalid";
        jdbc.update(
                """
                UPDATE users SET auth_subject = ?, email = ?, normalized_email = ?,
                    display_name = 'Deleted user', status = 'DELETED', last_login_at = NULL, updated_at = ?
                WHERE id = ?
                """,
                "deleted:" + user.id(), deletedEmail, deletedEmail, Timestamp.from(now), user.id()
        );
        jdbc.update(
                """
                UPDATE privacy_deletion_requests
                SET state = 'IDENTITY_PENDING', auth_provider_user_id = ?, provider_last_error = NULL,
                    locally_erased_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = 'REQUESTED'
                """,
                providerUserId, requestId
        );
        return new PendingIdentity(providerUserId);
    }

    private boolean markProviderDeleted(UUID requestId) {
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE privacy_deletion_requests
                SET state = 'COMPLETED', provider_attempt_count = provider_attempt_count + 1,
                    provider_last_error = NULL, provider_next_attempt_at = NULL,
                    provider_deleted_at = ?, completed_at = ?
                WHERE id = ? AND state = 'IDENTITY_PENDING'
                """,
                Timestamp.from(now), Timestamp.from(now), requestId
        );
        return updated == 1;
    }

    private void recordProviderFailure(UUID requestId, RuntimeException failure) {
        String error = failure.getClass().getSimpleName();
        jdbc.update(
                """
                UPDATE privacy_deletion_requests
                SET provider_attempt_count = provider_attempt_count + 1, provider_last_error = ?,
                    provider_next_attempt_at = CURRENT_TIMESTAMP
                        + make_interval(secs => LEAST(3600, 60 * power(2, LEAST(provider_attempt_count, 6)))::int)
                WHERE id = ? AND state IN ('REQUESTED', 'IDENTITY_PENDING')
                """,
                error.substring(0, Math.min(error.length(), 500)), requestId
        );
        log.error("AUTH_IDENTITY_DELETION_FAILED requestId={} errorType={}", requestId, error);
    }

    private List<DeletionRequest> findActive(UUID userId) {
        return jdbc.query(
                """
                SELECT id, state, requested_at, scheduled_for, cancelled_at, completed_at
                FROM privacy_deletion_requests WHERE user_id = ? AND state IN ('REQUESTED', 'IDENTITY_PENDING', 'IDENTITY_REVIEW')
                """,
                (result, row) -> new DeletionRequest(
                        result.getObject("id", UUID.class), result.getString("state"),
                        result.getTimestamp("requested_at").toInstant(),
                        result.getTimestamp("scheduled_for").toInstant(), null, null
                ), userId
        );
    }

    public record DeletionRequest(
            UUID id, String state, Instant requestedAt, Instant scheduledFor,
            Instant cancelledAt, Instant completedAt
    ) { }

    public record ProcessingResult(int checked, int completed) { }
    private record UserForDeletion(
            String state, UUID providerUserId, UUID id, String subject, String normalizedEmail
    ) { }
    private record PendingIdentity(UUID providerUserId) { }
}
