package com.vid2knowledge.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern ORGANIZATION = Pattern.compile("/organizations/([0-9a-fA-F-]{36})(?:/|$)");

    private final RequestRateLimiter limiter;

    public RateLimitInterceptor(RequestRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/") || !MUTATIONS.contains(request.getMethod())) {
            return true;
        }
        int limit = selectLimit(path);
        String scope = requestIdentity(request) + "|" + routeClass(path);
        enforce(limiter.consume(scope, limit), response);

        Matcher matcher = ORGANIZATION.matcher(path);
        if (matcher.find()) {
            enforce(limiter.consume("org:" + matcher.group(1) + "|" + routeClass(path), limit * 2), response);
        }
        return true;
    }

    private int selectLimit(String path) {
        var properties = limiter.properties();
        if (path.contains("/billing/") || path.contains("/webhooks/payos")) {
            return properties.paymentMutations();
        }
        if (path.contains("/analysis-jobs") || path.endsWith("/knowledge-index") || path.endsWith("/qa")) {
            return properties.expensiveMutations();
        }
        return properties.generalMutations();
    }

    private static String routeClass(String path) {
        if (path.contains("/billing/") || path.contains("/webhooks/payos")) return "payment";
        if (path.contains("/analysis-jobs") || path.endsWith("/knowledge-index") || path.endsWith("/qa")) {
            return "expensive";
        }
        return "mutation";
    }

    private static String requestIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return "account:" + authentication.getName();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static void enforce(RequestRateLimiter.Decision decision, HttpServletResponse response) {
        response.setHeader("RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("RateLimit-Remaining", Integer.toString(decision.remaining()));
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Request rate limit exceeded");
        }
    }
}
