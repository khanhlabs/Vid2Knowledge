package com.vid2knowledge.notification;

import com.vid2knowledge.auth.CurrentActor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface NotificationQueue {
    void onboarding(
            UUID organizationId, UUID userId, String recipientEmail, Instant trialEndsAt
    );

    void assignmentAvailable(
            UUID organizationId, UUID userId, String recipientEmail, String organizationName,
            UUID assignmentId, String assignmentTitle, Instant availableAt
    );

    void assignmentDue(
            UUID organizationId, UUID userId, String recipientEmail, String organizationName,
            UUID assignmentId, String assignmentTitle, Instant dueAt, Instant notifyAt
    );

    void reviewDue(
            UUID organizationId, UUID userId, String recipientEmail, String organizationName,
            int dueCards, LocalDate reminderDate, Instant notifyAt
    );

    void invitation(
            UUID organizationId, UUID invitationId, String recipientEmail,
            CurrentActor.Role role, String token, Instant expiresAt
    );

    void paymentReceipt(
            UUID organizationId, UUID invoiceId, String invoiceNumber, long amountVnd, Instant paidAt
    );

    void cancellationScheduled(UUID organizationId, UUID subscriptionId, Instant periodEnd);

    void renewalPaymentRequired(
            UUID organizationId, UUID invoiceId, String invoiceNumber,
            long amountVnd, String checkoutUrl, Instant periodEnd
    );

    void pilotLeadAlert(
            UUID leadId, String priority, String acquisitionSource, Instant receivedAt, Instant contactDueAt
    );
}
