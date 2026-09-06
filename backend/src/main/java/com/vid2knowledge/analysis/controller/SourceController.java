package com.vid2knowledge.analysis.controller;

import com.vid2knowledge.analysis.application.RegisterYoutubeSourceService;
import com.vid2knowledge.analysis.domain.RegisteredSource;
import com.vid2knowledge.analysis.dto.RegisterYoutubeSourceRequest;
import com.vid2knowledge.auth.CurrentActor;
import com.vid2knowledge.auth.TenantAccessService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/sources")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class SourceController {

    private final TenantAccessService access;
    private final RegisterYoutubeSourceService sources;

    public SourceController(TenantAccessService access, RegisterYoutubeSourceService sources) {
        this.access = access;
        this.sources = sources;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegisteredSource register(
            @PathVariable UUID organizationId,
            @Valid @RequestBody RegisterYoutubeSourceRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest
    ) {
        CurrentActor actor = access.require(
                organizationId, authentication,
                CurrentActor.Role.OWNER, CurrentActor.Role.ADMIN, CurrentActor.Role.INSTRUCTOR
        );
        return sources.register(
                actor, request.youtubeUrl(), request.rightsBasis(), request.termsAccepted(),
                servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE).toString()
        );
    }
}
