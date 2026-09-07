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
import java.time.LocalDate;
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
    public void onboarding(UUID organizationId, UUID userId, String recipientEmail, Instant trialEndsAt) {
        String recipientKey = recipientKey(recipientEmail);
        Map<String, Object> payload = Map.of(
                "userId", userId.toString(), "trialEndsAt", trialEndsAt.toString(),
                "organizationName", organizationName(organizationId)
        );
        enqueueAt(organizationId, "ONBOARDING_WELCOME", "onboarding/welcome/" + organizationId + "/" + recipientKey,
                recipientEmail, payload, clock.instant());
        enqueueAt(organizationId, "ACTIVATION_NUDGE", "onboarding/nudge/" + organizationId + "/" + recipientKey,
                recipientEmail, payload, clock.instant().plus(java.time.Duration.ofDays(2)));
        Instant conversionAt = trialEndsAt.minus(java.time.Duration.ofDays(3));
        enqueueAt(organizationId, "TRIAL_EXPIRING", "onboarding/trial-expiring/" + organizationId + "/" + recipientKey,
                recipientEmail, payload, conversionAt.isAfter(clock.instant())
                        ? conversionAt : clock.instant().plus(java.time.Duration.ofHours(1)));
    }

    @Override
    public void assignmentAvailable(
            UUID organizationId, UUID userId, String recipientEmail, String organizationName,
            UUID assignmentId, String assignmentTitle, Instant availableAt
    ) {
        enqueueAt(
                organizationId, "ASSIGNMENT_AVAILABLE", "assignment/available/" + assignmentId + "/" + userId,
                recipientEmail, Map.of(
                        "userId", userId.toString(), "assignmentId", assignmentId.toString(),
                        "assignmentTitle", assignmentTitle, "organizationName", organizationName
                ), availableAt.isAfter(clock.instant()) ? availableAt : clock.instant()
        );
    }

    @Override
    public void assignmentDue(
            UUID organizationId, UUID userId, String recipientEmail, String organizationName,
            UUID assignmentId, String assignmentTitle, Instant dueAt, Instant notifyAt
    ) {
        enqueueAt(
                organizationId, "ASSIGNMENT_DUE", "assignment/due/" + assignmentId + "/" + userId,
                recipientEmail, Map.of(
                        "userId", userId.toString(), "assignmentId", assignmentId.toString(),
                        "assignmentTitle", assignmentTitle, "dueAt", dueAt.toString(),
                        "organizationName", organizationName
                ), notifyAt.isAfter(clock.instant()) ? notifyAt : clock.instant()
        );
    }

    @Override
    public void reviewDue(
            UUID organizationId, UUID userId, String recipientEmail, String organizationName,
            int dueCards, LocalDate reminderDate, Instant notifyAt
    ) {
        enqueueAt(
                organizationId, "REVIEW_DUE", "review/due/" + organizationId + "/" + userId + "/" + reminderDate,
                recipientEmail, Map.of(
                        "userId", userId.toString(), "dueCards", dueCards,
                        "organizationName", organizationName, "reminderDate", reminderDate.toString()
                ), notifyAt
        );
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
        enqueueAt(organizationId, type, dedupeKey, recipient, payload, clock.instant());
    }

    private void enqueueAt(UUID organizationId, String type, String dedupeKey,
                           String recipient, Map<String, Object> payload, Instant availableAt) {
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
                    Timestamp.from(availableAt), Timestamp.from(now), Timestamp.from(now)
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
