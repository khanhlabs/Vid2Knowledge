package com.vid2knowledge.notification;

import com.vid2knowledge.auth.CurrentActor;

import java.time.Instant;
import java.util.UUID;

public interface NotificationQueue {
    void onboarding(
            UUID organizationId, UUID userId, String recipientEmail, Instant trialEndsAt
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
}
