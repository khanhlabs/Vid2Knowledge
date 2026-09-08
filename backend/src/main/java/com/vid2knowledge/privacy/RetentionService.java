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
        int webhookDeliveries = jdbc.update(
                """
                DELETE FROM webhook_deliveries
                WHERE state IN ('DELIVERED', 'DEAD_LETTER') AND created_at < ?
                """,
                Timestamp.from(now.minus(retention.terminalWebhookDelivery()))
        );
        int integrationSecrets = jdbc.update(
                """
                DELETE FROM webhook_endpoint_secrets s
                USING webhook_endpoints e
                WHERE e.id = s.endpoint_id AND s.version <> e.current_secret_version
                  AND s.created_at < ?
                  AND NOT EXISTS (
                    SELECT 1 FROM webhook_deliveries d
                    WHERE d.endpoint_id = s.endpoint_id AND d.secret_version = s.version
                  )
                """,
                Timestamp.from(now.minus(retention.terminalIntegrationCredential()))
        );
        int integrationApiKeys = jdbc.update(
                """
                DELETE FROM integration_api_keys
                WHERE COALESCE(revoked_at, expires_at) < ?
                  AND (revoked_at IS NOT NULL OR expires_at < ?)
                """,
                Timestamp.from(now.minus(retention.terminalIntegrationCredential())), Timestamp.from(now)
        );
        int webhookEndpoints = jdbc.update(
                """
                DELETE FROM webhook_endpoints e
                WHERE e.state = 'DISABLED' AND e.updated_at < ?
                  AND NOT EXISTS (SELECT 1 FROM webhook_deliveries d WHERE d.endpoint_id = e.id)
                """,
                Timestamp.from(now.minus(retention.terminalIntegrationCredential()))
        );
        int outboxEvents = jdbc.update(
                """
                DELETE FROM outbox_events
                WHERE COALESCE(published_at, dead_lettered_at) < ?
                  AND (published_at IS NOT NULL OR dead_lettered_at IS NOT NULL)
                  AND NOT EXISTS (
                    SELECT 1 FROM webhook_deliveries d WHERE d.outbox_event_id = outbox_events.id
                  )
                """,
                Timestamp.from(now.minus(retention.terminalOutbox()))
        );
        int notifications = jdbc.update(
                """
                DELETE FROM notification_jobs
                WHERE state IN ('SENT', 'DEAD', 'CANCELLED') AND updated_at < ?
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
        int sourceUploads = jdbc.update(
                """
                DELETE FROM source_uploads
                WHERE state IN ('REJECTED', 'EXPIRED') AND updated_at < ?
                """,
                Timestamp.from(now.minus(retention.terminalSourceUpload()))
        );
        int pilotLeads = jdbc.update(
                """
                UPDATE pilot_leads SET contact_name = 'REDACTED', work_email = 'REDACTED',
                    normalized_email = 'REDACTED', organization_name = 'REDACTED', note = NULL,
                    submitter_hash = 'REDACTED',
                    lost_reason = CASE WHEN status = 'LOST' THEN 'REDACTED' ELSE NULL END,
                    redacted_at = ?, updated_at = ?
                WHERE redacted_at IS NULL AND updated_at < ?
                  AND status <> 'WON'
                """,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.minus(retention.terminalPilotLead()))
        );
        return new CleanupResult(
                expiredIdempotency, generationPayloads, webhookPayloads, webhookDeliveries,
                integrationSecrets, integrationApiKeys, webhookEndpoints,
                outboxEvents, notifications, invitations, sourceUploads, pilotLeads
        );
    }

    public record CleanupResult(
            int expiredIdempotencyRecords,
            int redactedGenerationPayloads,
            int redactedPaymentWebhookPayloads,
            int deletedTerminalWebhookDeliveries,
            int deletedOldIntegrationSecrets,
            int deletedTerminalIntegrationApiKeys,
            int deletedDisabledWebhookEndpoints,
            int deletedTerminalOutboxEvents,
            int deletedTerminalNotifications,
            int deletedTerminalInvitations,
            int deletedTerminalSourceUploads,
            int redactedTerminalPilotLeads
    ) { }
}
