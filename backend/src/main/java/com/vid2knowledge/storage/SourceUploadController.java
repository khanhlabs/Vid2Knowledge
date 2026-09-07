package com.vid2knowledge.storage;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/source-uploads")
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class SourceUploadController {
    private final TenantAccessService access;
    private final SourceUploadService uploads;

    public SourceUploadController(TenantAccessService access, SourceUploadService uploads) {
        this.access = access;
        this.uploads = uploads;
    }

    @GetMapping
    public List<SourceUploadService.UploadView> list(
            @PathVariable UUID organizationId, Authentication authentication
    ) {
        requireAuthor(organizationId, authentication);
        return uploads.list(organizationId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceUploadService.UploadReservation reserve(
            @PathVariable UUID organizationId,
            Authentication authentication,
            @Valid @RequestBody ReserveRequest request,
            HttpServletRequest servletRequest
    ) {
        return uploads.reserve(requireAuthor(organizationId, authentication), request.filename(),
                request.contentType(), request.sizeBytes(), correlation(servletRequest));
    }

    @PostMapping("/{uploadId}/complete")
    public SourceUploadService.UploadView complete(
            @PathVariable UUID organizationId,
            @PathVariable UUID uploadId,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        return uploads.complete(requireAuthor(organizationId, authentication), uploadId, correlation(servletRequest));
    }

    private CurrentActor requireAuthor(UUID organizationId, Authentication authentication) {
        return access.require(organizationId, authentication, CurrentActor.Role.OWNER,
                CurrentActor.Role.ADMIN, CurrentActor.Role.INSTRUCTOR);
    }

    private static String correlation(HttpServletRequest request) {
        return request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString();
    }

    public record ReserveRequest(
            @NotBlank @Size(max = 255) String filename,
            @NotBlank @Size(max = 80) String contentType,
            @Positive long sizeBytes
    ) {}
}
