package com.vid2knowledge.legal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "features", name = "legal-acceptance-required", havingValue = "true")
public class LegalAcceptanceInterceptor implements HandlerInterceptor {
    private final JdbcTemplate jdbc;
    private final LegalService legal;

    public LegalAcceptanceInterceptor(JdbcTemplate jdbc, LegalService legal) {
        this.jdbc = jdbc;
        this.legal = legal;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equals(request.getMethod()) || excluded(request.getRequestURI())) return true;
        if (!(request.getUserPrincipal() instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED, "Current legal policies must be accepted"
            );
        }
        UUID userId = jdbc.query(
                "SELECT id FROM users WHERE auth_subject = ? AND status = 'ACTIVE'",
                (result, row) -> result.getObject("id", UUID.class), jwt.getSubject()
        ).stream().findFirst().orElse(null);
        if (userId == null || !legal.hasCurrentAcceptance(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED, "Current legal policies must be accepted"
            );
        }
        return true;
    }

    private static boolean excluded(String path) {
        return path.equals("/api/v1/me")
                || path.startsWith("/api/v1/legal/")
                || path.startsWith("/api/v1/privacy/")
                || path.startsWith("/api/v1/integrations/v1/")
                || path.startsWith("/api/v1/webhooks/")
                || path.startsWith("/api/v1/certificates/")
                || path.startsWith("/actuator/");
    }
}
