package com.vid2knowledge.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal/promotions")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class InternalPromotionController {
    private final PromotionService promotions;

    public InternalPromotionController(PromotionService promotions) {
        this.promotions = promotions;
    }

    @GetMapping
    public List<PromotionService.Campaign> campaigns() {
        return promotions.campaigns();
    }

    @PostMapping
    public PromotionService.Campaign create(
            @Valid @RequestBody CreateRequest request, Authentication authentication
    ) {
        return promotions.create(new PromotionService.CreateCampaign(
                request.code(), request.name(), request.discountBps(), request.planCodePrefix(),
                request.attributionChannel(), request.partnerReference(), request.startsAt(), request.endsAt(),
                request.maxRedemptions()
        ), authentication == null ? "internal" : authentication.getName());
    }

    @DeleteMapping("/{campaignId}")
    public void deactivate(@PathVariable UUID campaignId, Authentication authentication) {
        promotions.deactivate(campaignId, authentication == null ? "internal" : authentication.getName());
    }

    public record CreateRequest(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 160) String name,
            @Min(1) @Max(5000) int discountBps,
            @Size(max = 40) String planCodePrefix,
            @NotNull PromotionService.Channel attributionChannel,
            @Size(max = 120) String partnerReference,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @Min(1) @Max(1_000_000) int maxRedemptions
    ) { }
}
