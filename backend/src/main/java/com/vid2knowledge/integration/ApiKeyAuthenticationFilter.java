package com.vid2knowledge.integration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@ConditionalOnProperty(prefix = "integrations", name = "enabled", havingValue = "true")
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private final ApiKeyService keys;

    public ApiKeyAuthenticationFilter(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/integrations/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : null;
        IntegrationPrincipal principal = keys.authenticate(token);
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":401,\"code\":\"INVALID_API_KEY\",\"detail\":\"A valid Business API key is required\"}");
            return;
        }
        var authorities = principal.scopes().stream()
                .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList();
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        try {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
