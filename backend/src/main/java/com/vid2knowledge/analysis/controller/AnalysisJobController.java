package com.vid2knowledge.analysis.controller;

import com.vid2knowledge.analysis.application.AnalysisApiService;
import com.vid2knowledge.analysis.dto.AnalysisJobResponse;
import com.vid2knowledge.analysis.dto.CreateAnalysisRequest;
import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/analysis-jobs")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisJobController {

    private final TenantAccessService access;
    private final AnalysisApiService analyses;

    public AnalysisJobController(TenantAccessService access, AnalysisApiService analyses) {
        this.access = access;
        this.analyses = analyses;
    }

    @PostMapping
    public ResponseEntity<AnalysisJobResponse> create(
            @PathVariable UUID organizationId,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 160) String idempotencyKey,
            @Valid @RequestBody CreateAnalysisRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        access.require(
                organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN, CurrentActor.Role.INSTRUCTOR
        );
        var job = analyses.submit(
                organizationId,
                request.sourceId(),
                request.outputProfile().toString(),
                idempotencyKey,
                servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString()
        );
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/organizations/" + organizationId + "/analysis-jobs/" + job.id()))
                .body(AnalysisJobResponse.from(job, analyses.packageId(organizationId, job)));
    }

    @GetMapping("/{jobId}")
    public AnalysisJobResponse get(
            @PathVariable UUID organizationId,
            @PathVariable UUID jobId,
            Authentication authentication
    ) {
        access.require(
                organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN,
                CurrentActor.Role.INSTRUCTOR, CurrentActor.Role.REVIEWER
        );
        var job = analyses.get(organizationId, jobId);
        return AnalysisJobResponse.from(job, analyses.packageId(organizationId, job));
    }
}
