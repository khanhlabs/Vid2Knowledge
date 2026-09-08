package com.vid2knowledge.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/sales/pilot-leads")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalPilotLeadController {
    private final PilotLeadService leads;

    public InternalPilotLeadController(PilotLeadService leads) {
        this.leads = leads;
    }

    @GetMapping
    public ResponseEntity<List<PilotLeadService.LeadView>> queue(
            @RequestParam(required = false) PilotLeadService.Status status
    ) {
        return noStore(leads.queue(status));
    }

    @GetMapping("/funnel")
    public ResponseEntity<List<PilotLeadService.FunnelRow>> funnel() {
        return noStore(leads.funnel());
    }

    @PatchMapping("/{leadId}")
    public ResponseEntity<PilotLeadService.LeadView> update(
            @PathVariable UUID leadId, @Valid @RequestBody UpdateRequest request,
            Authentication authentication
    ) {
        return noStore(leads.update(
                leadId, new PilotLeadService.StatusUpdate(
                        request.status(), request.organizationId(), request.lostReason()
                ), authentication == null ? "internal" : authentication.getName()
        ));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    public record UpdateRequest(
            @NotNull PilotLeadService.Status status,
            UUID organizationId,
            @Size(max = 500) String lostReason
    ) { }
}
