package com.vid2knowledge.notification;

import com.vid2knowledge.auth.CurrentActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "notifications", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledNotificationQueue implements NotificationQueue {
    @Override
    public void onboarding(UUID organizationId, UUID userId, String recipientEmail, Instant trialEndsAt) {
    }

    @Override
    public void invitation(UUID organizationId, UUID invitationId, String recipientEmail,
                           CurrentActor.Role role, String token, Instant expiresAt) {
    }

    @Override
    public void paymentReceipt(UUID organizationId, UUID invoiceId, String invoiceNumber,
                               long amountVnd, Instant paidAt) {
    }

    @Override
    public void cancellationScheduled(UUID organizationId, UUID subscriptionId, Instant periodEnd) {
    }

    @Override
    public void renewalPaymentRequired(UUID organizationId, UUID invoiceId, String invoiceNumber,
                                       long amountVnd, String checkoutUrl, Instant periodEnd) {
    }
}
