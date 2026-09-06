package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/packages")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PackageController {

    private final TenantAccessService access;
    private final PackageWorkflowService packages;

    public PackageController(TenantAccessService access, PackageWorkflowService packages) {
        this.access = access;
        this.packages = packages;
    }

    @GetMapping("/{packageId}")
    public ResponseEntity<PackageWorkflowService.PackageView> get(
            @PathVariable UUID organizationId,
            @PathVariable UUID packageId,
            Authentication authentication
    ) {
        access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR, CurrentActor.Role.REVIEWER
        );
        var result = packages.get(organizationId, packageId);
        return ResponseEntity.ok().eTag('"' + Long.toString(result.version()) + '"').body(result);
    }

    @PatchMapping("/{packageId}/draft")
    public ResponseEntity<PackageWorkflowService.PackageView> saveDraft(
            @PathVariable UUID organizationId,
            @PathVariable UUID packageId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody DraftRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = author(organizationId, authentication);
        long version = parseEtag(ifMatch);
        var result = packages.saveDraft(actor, packageId, version, request.content(), correlation(servletRequest));
        return ResponseEntity.ok().eTag('"' + Long.toString(result.version()) + '"').body(result);
    }

    @PostMapping("/{packageId}/{action:submit-review|approve|reject|publish|archive}")
    public PackageWorkflowService.PackageView transition(
            @PathVariable UUID organizationId,
            @PathVariable UUID packageId,
            @PathVariable String action,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = "approve".equals(action) || "reject".equals(action)
                ? reviewer(organizationId, authentication)
                : author(organizationId, authentication);
        var transition = switch (action) {
            case "submit-review" -> PackageWorkflowService.Transition.SUBMIT_REVIEW;
            case "approve" -> PackageWorkflowService.Transition.APPROVE;
            case "reject" -> PackageWorkflowService.Transition.REJECT;
            case "publish" -> PackageWorkflowService.Transition.PUBLISH;
            case "archive" -> PackageWorkflowService.Transition.ARCHIVE;
            default -> throw new IllegalArgumentException("Unsupported action");
        };
        return packages.transition(actor, packageId, transition, correlation(servletRequest));
    }

    private CurrentActor author(UUID organizationId, Authentication authentication) {
        return access.require(
                organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN, CurrentActor.Role.INSTRUCTOR
        );
    }

    private CurrentActor reviewer(UUID organizationId, Authentication authentication) {
        return access.require(
                organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN, CurrentActor.Role.REVIEWER
        );
    }

    private static long parseEtag(String value) {
        try {
            return Long.parseLong(value.replace("\"", "").trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("If-Match must contain a numeric package version", exception);
        }
    }

    private static String correlation(HttpServletRequest request) {
        return request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString();
    }

    public record DraftRequest(@NotNull JsonNode content) {
    }
}
