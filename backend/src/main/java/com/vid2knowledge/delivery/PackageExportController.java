package com.vid2knowledge.delivery;

import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/packages/{packageId}/exports")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class PackageExportController {
    private final TenantAccessService access;
    private final PackageExportService exports;

    public PackageExportController(TenantAccessService access, PackageExportService exports) {
        this.access = access;
        this.exports = exports;
    }

    @GetMapping("/{format:markdown|word}")
    public ResponseEntity<byte[]> export(
            @PathVariable UUID organizationId, @PathVariable UUID packageId, @PathVariable String format,
            Authentication authentication, HttpServletRequest request
    ) {
        CurrentActor actor = access.require(
                organizationId, authentication, CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR, CurrentActor.Role.REVIEWER
        );
        PackageExportService.Format selected = "word".equals(format)
                ? PackageExportService.Format.WORD : PackageExportService.Format.MARKDOWN;
        PackageExportService.ExportedFile file = exports.export(
                actor, packageId, selected,
                request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString()
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(file.body());
    }
}
