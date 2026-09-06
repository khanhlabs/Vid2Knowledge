package com.vid2knowledge.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/tasks/billing/reconcile")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalBillingController {

    private final BillingService billing;

    public InternalBillingController(BillingService billing) {
        this.billing = billing;
    }

    @PostMapping
    public BillingService.ReconciliationResult reconcile() {
        return billing.reconcilePendingPayments();
    }
}
