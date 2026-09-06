package com.vid2knowledge.legal;

import com.vid2knowledge.auth.IdentityService;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/legal")
@ConditionalOnProperty(prefix = "features", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class LegalController {
    private final LegalService legal;
    private final IdentityService identities;

    public LegalController(LegalService legal, IdentityService identities) {
        this.legal = legal;
        this.identities = identities;
    }

    @GetMapping("/manifest")
    public LegalService.Manifest manifest() {
        return legal.manifest();
    }

    @GetMapping("/status")
    public LegalService.Status status(@AuthenticationPrincipal Jwt jwt) {
        return legal.status(provision(jwt).id());
    }

    @PostMapping("/acceptances")
    public LegalService.Status accept(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody LegalService.AcceptanceRequest request,
            HttpServletRequest servletRequest
    ) {
        var user = provision(jwt);
        String correlation = String.valueOf(
                servletRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE)
        );
        String evidence = jwt.getSubject() + "|" + servletRequest.getRemoteAddr() + "|"
                + servletRequest.getHeader("User-Agent") + "|" + correlation;
        return legal.accept(user.id(), request, evidence);
    }

    private IdentityService.Me provision(Jwt jwt) {
        Boolean verified = jwt.getClaim("email_verified");
        if (Boolean.FALSE.equals(verified)) {
            throw new IllegalArgumentException("A verified email is required");
        }
        String name = jwt.getClaimAsString("name");
        return identities.provision(
                jwt.getSubject(), jwt.getClaimAsString("email"),
                name == null ? jwt.getClaimAsString("user_name") : name
        );
    }
}
