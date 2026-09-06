package com.vid2knowledge.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "payos", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledPaymentGateway implements PaymentGateway {
    @Override
    public CheckoutLink createCheckout(long orderCode, long amountVnd, String description) {
        throw new IllegalStateException("Payments are not configured in this environment");
    }
}
