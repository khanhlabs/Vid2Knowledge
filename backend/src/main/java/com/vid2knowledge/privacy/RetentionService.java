package com.vid2knowledge.privacy;

import com.vid2knowledge.config.RetentionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

@Service
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionService {
    private final JdbcTemplate jdbc;
    private final RetentionProperties retention;
    private final Clock clock;

    @Autowired
    public RetentionService(JdbcTemplate jdbc, RetentionProperties retention) {
        this(jdbc, retention, Clock.systemUTC());
    }

    RetentionService(JdbcTemplate jdbc, RetentionProperties retention, Clock clock) {
        this.jdbc = jdbc;
        this.retention = retention;
        this.clock = clock;
    }

    @Transactional
    public CleanupResult cleanup() {
        Instant now = clock.instant();
        int expiredIdempotency = jdbc.update(
                "DELETE FROM idempotency_records WHERE expires_at < ?", Timestamp.from(now)
        );
        int generationPayloads = jdbc.update(
                """
                UPDATE generation_runs
                SET output_json = CASE WHEN output_json IS NULL THEN NULL ELSE '{}'::jsonb END,
                    raw_output = 'REDACTED'
                WHERE created_at < ?
                  AND (raw_output <> 'REDACTED'
                       OR (output_json IS NOT NULL AND output_json <> '{}'::jsonb))
                """,
                Timestamp.from(now.minus(retention.generationPayload()))
        );
        int webhookPayloads = jdbc.update(
                """
                UPDATE payment_webhook_inbox SET payload_json = '{}'::jsonb, signature = 'REDACTED'
                WHERE received_at < ? AND processed_at IS NOT NULL
                  AND (payload_json <> '{}'::jsonb OR signature <> 'REDACTED')
                """,
                Timestamp.from(now.minus(retention.paymentWebhookPayload()))
        );
        int outboxEvents = jdbc.update(
                """
                DELETE FROM outbox_events
                WHERE COALESCE(published_at, dead_lettered_at) < ?
                  AND (published_at IS NOT NULL OR dead_lettered_at IS NOT NULL)
                """,
                Timestamp.from(now.minus(retention.terminalOutbox()))
        );
        int notifications = jdbc.update(
                """
                DELETE FROM notification_jobs
                WHERE state IN ('SENT', 'DEAD') AND updated_at < ?
                """,
                Timestamp.from(now.minus(retention.terminalNotification()))
        );
        int invitations = jdbc.update(
                """
                DELETE FROM invitations
                WHERE created_at < ?
                  AND (accepted_at IS NOT NULL OR revoked_at IS NOT NULL OR expires_at < ?)
                """,
                Timestamp.from(now.minus(retention.terminalInvitation())), Timestamp.from(now)
        );
        return new CleanupResult(
                expiredIdempotency, generationPayloads, webhookPayloads,
                outboxEvents, notifications, invitations
        );
    }

    public record CleanupResult(
            int expiredIdempotencyRecords,
            int redactedGenerationPayloads,
            int redactedPaymentWebhookPayloads,
            int deletedTerminalOutboxEvents,
            int deletedTerminalNotifications,
            int deletedTerminalInvitations
    ) { }
}
