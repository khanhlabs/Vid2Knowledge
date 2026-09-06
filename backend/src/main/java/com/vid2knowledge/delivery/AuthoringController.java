package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/authoring")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class AuthoringController {

    private final TenantAccessService access;
    private final AuthoringService authoring;

    public AuthoringController(TenantAccessService access, AuthoringService authoring) {
        this.access = access;
        this.authoring = authoring;
    }

    @GetMapping("/templates")
    public List<AuthoringService.TemplateView> templates(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireStaff(organizationId, authentication);
        return authoring.templates(organizationId);
    }

    @PostMapping("/templates")
    public AuthoringService.TemplateView createTemplate(
            @PathVariable UUID organizationId, @Valid @RequestBody TemplateRequest request,
            Authentication authentication, HttpServletRequest servletRequest
    ) {
        return authoring.createTemplate(
                requireAuthor(organizationId, authentication), request.name(), request.outputProfile(),
                correlation(servletRequest)
        );
    }

    @PatchMapping("/templates/{templateId}")
    public ResponseEntity<AuthoringService.TemplateView> updateTemplate(
            @PathVariable UUID organizationId, @PathVariable UUID templateId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody TemplateRequest request,
            Authentication authentication, HttpServletRequest servletRequest
    ) {
        var result = authoring.updateTemplate(
                requireAuthor(organizationId, authentication), templateId, parseEtag(ifMatch),
                request.name(), request.outputProfile(), correlation(servletRequest)
        );
        return ResponseEntity.ok().eTag('"' + Long.toString(result.version()) + '"').body(result);
    }

    @DeleteMapping("/templates/{templateId}")
    public ResponseEntity<Void> archiveTemplate(
            @PathVariable UUID organizationId, @PathVariable UUID templateId,
            Authentication authentication, HttpServletRequest servletRequest
    ) {
        authoring.archiveTemplate(
                requireAuthor(organizationId, authentication), templateId, correlation(servletRequest)
        );
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/review-queue")
    public List<AuthoringService.ReviewQueueItem> reviewQueue(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireReviewer(organizationId, authentication);
        return authoring.reviewQueue(organizationId);
    }

    @GetMapping("/question-bank")
    public List<AuthoringService.QuestionBankItem> questionBank(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireStaff(organizationId, authentication);
        return authoring.questionBank(organizationId);
    }

    @GetMapping("/settings")
    public AuthoringService.Settings settings(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireStaff(organizationId, authentication);
        return authoring.settings(organizationId);
    }

    @PatchMapping("/settings")
    public AuthoringService.Settings updateSettings(
            @PathVariable UUID organizationId, @Valid @RequestBody SettingsRequest request,
            Authentication authentication, HttpServletRequest servletRequest
    ) {
        return authoring.updateSettings(
                requireAdmin(organizationId, authentication), request.approvalRequired(), correlation(servletRequest)
        );
    }

    @PostMapping("/feedback/{feedbackId}/resolution")
    public ResponseEntity<Void> resolveFeedback(
            @PathVariable UUID organizationId, @PathVariable UUID feedbackId,
            @Valid @RequestBody ResolutionRequest request, Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        authoring.resolveFeedback(
                requireReviewer(organizationId, authentication), feedbackId, request.resolution(),
                request.note(), correlation(servletRequest)
        );
        return ResponseEntity.noContent().build();
    }

    private CurrentActor requireAuthor(UUID organizationId, Authentication authentication) {
        return access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR);
    }

    private CurrentActor requireReviewer(UUID organizationId, Authentication authentication) {
        return access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.REVIEWER);
    }

    private CurrentActor requireAdmin(UUID organizationId, Authentication authentication) {
        return access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN);
    }

    private CurrentActor requireStaff(UUID organizationId, Authentication authentication) {
        return access.require(organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR, CurrentActor.Role.REVIEWER);
    }

    private static String correlation(HttpServletRequest request) {
        return request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString();
    }

    private static long parseEtag(String value) {
        try {
            return Long.parseLong(value.replace("\"", "").trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("If-Match must contain a numeric template version", exception);
        }
    }

    public record TemplateRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotNull JsonNode outputProfile
    ) {}
    public record SettingsRequest(boolean approvalRequired) {}
    public record ResolutionRequest(
            @NotNull AuthoringService.Resolution resolution,
            @NotBlank @Size(min = 3, max = 1000) String note
    ) {}
}
