package com.vid2knowledge.common.api;

import com.vid2knowledge.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitInterceptorTest {
    private final RateLimitInterceptor interceptor = new RateLimitInterceptor(new RequestRateLimiter(
            new RateLimitProperties(true, 2, 2, 2, 2, Duration.ofMinutes(1), 1000),
            Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"), ZoneOffset.UTC)
    ));

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void unrelatedAnonymousClientsDoNotShareTheAnonymousUserAccountBucket() {
        anonymous();
        assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();

        accept("192.0.2.1");
        accept("192.0.2.1");
        var rejected = new MockHttpServletResponse();
        assertThatThrownBy(() -> interceptor.preHandle(request("192.0.2.1"), rejected, new Object()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(rejected.getHeader("RateLimit-Remaining")).isEqualTo("0");
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");

        accept("192.0.2.2");
        accept("192.0.2.2");
    }

    @Test
    void signedInAccountKeepsItsBudgetAcrossIpsButDoesNotConsumeOtherAccountsBudget() {
        signedIn("buyer-one");
        accept("192.0.2.1");
        accept("192.0.2.2");
        assertThatThrownBy(() -> accept("192.0.2.3"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        signedIn("buyer-two");
        accept("192.0.2.1");
        anonymous();
        accept("192.0.2.1");
    }

    @Test
    void frameworkForwardedHeadersCannotRotateAnonymousBudgetOrMergeDifferentPeers() throws Exception {
        anonymous();
        acceptThroughForwardedHeaderFilter("192.0.2.1", "198.51.100.1", false);
        acceptThroughForwardedHeaderFilter("192.0.2.1", "198.51.100.2", true);
        assertThatThrownBy(() -> acceptThroughForwardedHeaderFilter("192.0.2.1", "198.51.100.3", false))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        // A distinct real peer still gets its own budget even when the spoofed header is identical.
        acceptThroughForwardedHeaderFilter("192.0.2.2", "198.51.100.1", true);
    }

    @Test
    void absentOrUnauthenticatedPrincipalCannotCreateAnAccountBudget() {
        SecurityContextHolder.clearContext();
        accept("192.0.2.1");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("claimed-account", ""));
        accept("192.0.2.1");
        assertThatThrownBy(() -> accept("192.0.2.1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    private void accept(String peerAddress) {
        assertThat(interceptor.preHandle(request(peerAddress), new MockHttpServletResponse(), new Object())).isTrue();
    }

    private void acceptThroughForwardedHeaderFilter(
            String peerAddress, String forwardedAddress, boolean standardHeader
    ) throws Exception {
        var request = request(peerAddress);
        if (standardHeader) {
            request.addHeader("Forwarded", "for=" + forwardedAddress + ";proto=https;host=public.example.invalid");
        } else {
            request.addHeader("X-Forwarded-For", forwardedAddress);
        }
        new ForwardedHeaderFilter().doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            // Exercise the real framework wrapper that changes getRemoteAddr(), not merely raw headers.
            assertThat(wrapped.getRemoteAddr()).isEqualTo(forwardedAddress).isNotEqualTo(peerAddress);
            assertThat(interceptor.preHandle(
                    (HttpServletRequest) wrapped, (HttpServletResponse) response, new Object())).isTrue();
        });
    }

    private static MockHttpServletRequest request(String peerAddress) {
        var request = new MockHttpServletRequest("POST", "/api/v1/public/pilot-leads");
        request.setRemoteAddr(peerAddress);
        return request;
    }

    private static void anonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    }

    private static void signedIn(String account) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(account, "", List.of()));
    }
}
