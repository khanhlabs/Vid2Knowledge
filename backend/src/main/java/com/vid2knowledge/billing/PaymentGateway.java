package com.vid2knowledge.billing;

import java.net.URI;
import java.util.Optional;

public interface PaymentGateway {
    CheckoutLink createCheckout(long orderCode, long amountVnd, String description);

    default Optional<PaymentStatus> getPayment(long orderCode) {
        return Optional.empty();
    }

    record CheckoutLink(String providerId, URI checkoutUrl) {
    }

    record PaymentStatus(long orderCode, long amountVnd, long amountPaidVnd, String status, String providerId) {
    }
}
