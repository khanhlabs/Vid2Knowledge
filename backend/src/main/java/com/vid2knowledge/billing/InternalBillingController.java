package com.vid2knowledge.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.vid2knowledge.privacy.PrivacyService;

@RestController
@RequestMapping("/internal/tasks/billing/reconcile")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalBillingController {

    private final BillingService billing;
    private final PrivacyService privacy;

    public InternalBillingController(BillingService billing, PrivacyService privacy) {
        this.billing = billing;
        this.privacy = privacy;
    }

    @PostMapping
    public MaintenanceResult reconcile() {
        return new MaintenanceResult(billing.reconcilePendingPayments(), privacy.processDueDeletions());
    }

    @PostMapping("/refunds/{refundId}/confirm")
    public BillingService.RefundView confirmRefund(
            @PathVariable java.util.UUID refundId,
            @RequestBody RefundResolution request
    ) {
        return billing.resolveRefund(refundId, true, request.providerReference(), null);
    }

    @PostMapping("/refunds/{refundId}/reject")
    public BillingService.RefundView rejectRefund(
            @PathVariable java.util.UUID refundId,
            @RequestBody RefundResolution request
    ) {
        return billing.resolveRefund(refundId, false, null, request.reason());
    }

    public record RefundResolution(String providerReference, String reason) {
    }

    public record MaintenanceResult(
            BillingService.ReconciliationResult billing,
            PrivacyService.ProcessingResult privacyDeletions
    ) { }
}
