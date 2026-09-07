package com.vid2knowledge.billing;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class BillingController {

    private final BillingService billing;
    private final TenantAccessService access;

    public BillingController(BillingService billing, TenantAccessService access) {
        this.billing = billing;
        this.access = access;
    }

    @GetMapping("/billing/plans")
    public List<BillingService.Plan> plans() {
        return billing.plans();
    }

    @PostMapping("/organizations/{organizationId}/billing/checkout-sessions")
    public BillingService.Checkout checkout(
            @PathVariable UUID organizationId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 160) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request,
            Authentication authentication
    ) {
        CurrentActor actor = access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN
        );
        return billing.checkout(actor, request.planId(), request.promotionCode(), idempotencyKey);
    }

    @PostMapping("/organizations/{organizationId}/billing/quotes")
    public BillingService.Quote quote(
            @PathVariable UUID organizationId,
            @Valid @RequestBody CheckoutRequest request,
            Authentication authentication
    ) {
        access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN);
        return billing.quote(organizationId, request.planId(), request.promotionCode());
    }

    @GetMapping("/organizations/{organizationId}/billing/usage")
    public BillingService.UsageView usage(
            @PathVariable UUID organizationId,
            Authentication authentication
    ) {
        access.require(
                organizationId, authentication, CurrentActor.Role.OWNER,
                CurrentActor.Role.ADMIN, CurrentActor.Role.INSTRUCTOR
        );
        return billing.usage(organizationId);
    }

    @GetMapping("/organizations/{organizationId}/billing/subscription")
    public BillingService.SubscriptionView subscription(
            @PathVariable UUID organizationId,
            Authentication authentication
    ) {
        access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN);
        return billing.subscription(organizationId).orElse(null);
    }

    @GetMapping("/organizations/{organizationId}/billing/invoices")
    public List<BillingService.InvoiceView> invoices(
            @PathVariable UUID organizationId,
            Authentication authentication
    ) {
        access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN);
        return billing.invoices(organizationId);
    }

    @GetMapping("/organizations/{organizationId}/billing/refunds")
    public List<BillingService.RefundView> refunds(
            @PathVariable UUID organizationId,
            Authentication authentication
    ) {
        access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN);
        return billing.refunds(organizationId);
    }

    @PostMapping("/organizations/{organizationId}/billing/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public BillingService.RefundView requestRefund(
            @PathVariable UUID organizationId,
            @Valid @RequestBody RefundRequest refund,
            Authentication authentication,
            HttpServletRequest request
    ) {
        CurrentActor actor = access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN
        );
        return billing.requestRefund(
                actor, refund.invoiceId(), refund.reason(),
                request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString()
        );
    }

    @PostMapping("/organizations/{organizationId}/billing/subscriptions/{subscriptionId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @PathVariable UUID organizationId,
            @PathVariable UUID subscriptionId,
            Authentication authentication,
            HttpServletRequest request
    ) {
        CurrentActor actor = access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN
        );
        billing.cancelAtPeriodEnd(
                actor, subscriptionId, request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString()
        );
    }

    @PostMapping("/webhooks/payos")
    public void payOsWebhook(@RequestBody JsonNode envelope) {
        billing.processWebhook(envelope);
    }

    public record CheckoutRequest(@NotNull UUID planId, @Size(max = 40) String promotionCode) {
    }

    public record RefundRequest(
            @NotNull UUID invoiceId,
            @NotNull @Size(min = 10, max = 1000) String reason
    ) {
    }
}
