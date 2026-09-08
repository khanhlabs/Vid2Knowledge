package com.vid2knowledge.sales;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/pilot-leads")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PilotLeadController {
    private final PilotLeadService leads;

    public PilotLeadController(PilotLeadService leads) {
        this.leads = leads;
    }

    @PostMapping
    public ResponseEntity<PublicSubmission> submit(
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 160) String idempotencyKey,
            @Valid @RequestBody SubmitRequest request,
            HttpServletRequest servletRequest
    ) {
        PilotLeadService.Submission submission = leads.submit(
                new PilotLeadService.LeadRequest(
                        request.contactName(), request.workEmail(), request.organizationName(), request.buyerRole(),
                        request.monthlyVideoMinutes(), request.learnerCount(), request.primaryGoal(), request.note(),
                        request.acquisitionSource(), request.acquisitionCampaign(), request.contactConsent()
                ), idempotencyKey, servletRequest.getRemoteAddr() + "|" + servletRequest.getHeader("User-Agent")
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PublicSubmission(submission.id(), submission.receivedAt()));
    }

    public record SubmitRequest(
            @NotBlank @Size(max = 160) String contactName,
            @NotBlank @Size(max = 320) String workEmail,
            @NotBlank @Size(max = 240) String organizationName,
            @NotNull PilotLeadService.BuyerRole buyerRole,
            @NotNull PilotLeadService.Minutes monthlyVideoMinutes,
            @NotNull PilotLeadService.Learners learnerCount,
            @NotNull PilotLeadService.Goal primaryGoal,
            @Size(max = 1000) String note,
            @NotNull PilotLeadService.Source acquisitionSource,
            @Size(max = 80) String acquisitionCampaign,
            boolean contactConsent
    ) { }

    public record PublicSubmission(java.util.UUID id, java.time.Instant receivedAt) { }
}
