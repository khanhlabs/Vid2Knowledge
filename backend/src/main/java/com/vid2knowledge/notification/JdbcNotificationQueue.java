package com.vid2knowledge.notification;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.common.id.UuidV7Generator;
import com.vid2knowledge.common.id.RequestFingerprint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "true")
public class JdbcNotificationQueue implements NotificationQueue {
    private final JdbcTemplate jdbc;
    private final NotificationCipher cipher;
    private final ObjectMapper mapper;
    private final Clock clock = Clock.systemUTC();

    public JdbcNotificationQueue(JdbcTemplate jdbc, NotificationCipher cipher, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.mapper = mapper;
    }

    @Override
    public void invitation(UUID organizationId, UUID invitationId, String recipientEmail,
                           CurrentActor.Role role, String token, Instant expiresAt) {
        enqueue(organizationId, "INVITATION", "invitation/" + invitationId, recipientEmail, Map.of(
                "invitationId", invitationId.toString(), "role", role.name(), "token", token,
                "expiresAt", expiresAt.toString(), "organizationName", organizationName(organizationId)
        ));
    }

    @Override
    public void paymentReceipt(UUID organizationId, UUID invoiceId, String invoiceNumber,
                               long amountVnd, Instant paidAt) {
        for (String recipient : billingRecipients(organizationId)) {
            enqueue(organizationId, "PAYMENT_RECEIPT", "receipt/" + invoiceId + "/" + recipientKey(recipient),
                    recipient, Map.of(
                            "invoiceNumber", invoiceNumber, "amountVnd", amountVnd,
                            "paidAt", paidAt.toString(), "organizationName", organizationName(organizationId)
                    ));
        }
    }

    @Override
    public void cancellationScheduled(UUID organizationId, UUID subscriptionId, Instant periodEnd) {
        for (String recipient : billingRecipients(organizationId)) {
            enqueue(organizationId, "CANCELLATION_SCHEDULED",
                    "cancellation/" + subscriptionId + "/" + recipientKey(recipient),
                    recipient, Map.of(
                            "periodEnd", periodEnd.toString(), "organizationName", organizationName(organizationId)
                    ));
        }
    }

    @Override
    public void renewalPaymentRequired(UUID organizationId, UUID invoiceId, String invoiceNumber,
                                       long amountVnd, String checkoutUrl, Instant periodEnd) {
        for (String recipient : billingRecipients(organizationId)) {
            enqueue(organizationId, "RENEWAL_PAYMENT_REQUIRED",
                    "renewal/" + invoiceId + "/" + recipientKey(recipient), recipient, Map.of(
                            "invoiceNumber", invoiceNumber, "amountVnd", amountVnd,
                            "checkoutUrl", checkoutUrl, "periodEnd", periodEnd.toString(),
                            "organizationName", organizationName(organizationId)
                    ));
        }
    }

    private void enqueue(UUID organizationId, String type, String dedupeKey,
                         String recipient, Map<String, Object> payload) {
        try {
            UUID id = UuidV7Generator.generate();
            Instant now = clock.instant();
            String encrypted = cipher.encrypt(mapper.writeValueAsString(payload), id.toString());
            jdbc.update(
                    """
                    INSERT INTO notification_jobs(
                        id, organization_id, notification_type, dedupe_key, recipient_email,
                        encrypted_payload, available_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (dedupe_key) DO NOTHING
                    """,
                    id, organizationId, type, dedupeKey, recipient, encrypted,
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now)
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Could not queue notification", failure);
        }
    }

    private String organizationName(UUID organizationId) {
        return jdbc.queryForObject("SELECT name FROM organizations WHERE id = ?", String.class, organizationId);
    }

    private List<String> billingRecipients(UUID organizationId) {
        return jdbc.queryForList(
                """
                SELECT DISTINCT u.email FROM memberships m JOIN users u ON u.id = m.user_id
                WHERE m.organization_id = ? AND m.status = 'ACTIVE' AND u.status = 'ACTIVE'
                  AND m.role IN ('OWNER', 'ADMIN')
                """,
                String.class, organizationId
        );
    }

    private static String recipientKey(String recipient) {
        return RequestFingerprint.sha256(recipient.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
