package com.vid2knowledge.billing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.vid2knowledge.privacy.RetentionService;
import com.vid2knowledge.delivery.GroundedQaService;

@RestController
@RequestMapping("/internal/tasks/billing/reconcile")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalBillingController {

    private final BillingService billing;
    private final RetentionService retention;
    private final GroundedQaService groundedQa;

    public InternalBillingController(BillingService billing, RetentionService retention, GroundedQaService groundedQa) {
        this.billing = billing;
        this.retention = retention;
        this.groundedQa = groundedQa;
    }

    @PostMapping
    public MaintenanceResult reconcile() {
        return new MaintenanceResult(
                billing.reconcilePendingPayments(), retention.cleanup(), groundedQa.reconcileExpiredRequests()
        );
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
            RetentionService.CleanupResult retention,
            int expiredQaRequests
    ) { }
}
