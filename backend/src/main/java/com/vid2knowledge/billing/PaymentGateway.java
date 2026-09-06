package com.vid2knowledge.billing;

import java.net.URI;

public interface PaymentGateway {
    CheckoutLink createCheckout(long orderCode, long amountVnd, String description);

    record CheckoutLink(String providerId, URI checkoutUrl) {
    }
}
