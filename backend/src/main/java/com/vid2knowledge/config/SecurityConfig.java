package com.vid2knowledge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final boolean authEnabled;

    public SecurityConfig(@Value("${features.auth-enabled:true}") boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain internalSecurityFilterChain(
            HttpSecurity http,
            TaskQueueProperties taskQueue
    ) throws Exception {
        common(http).securityMatcher("/internal/**");
        if (authEnabled) {
            var decoder = NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
            var issuer = JwtValidators.createDefaultWithIssuer("https://accounts.google.com");
            var audience = new JwtClaimValidator<List<String>>(
                    "aud", values -> values != null && values.contains(taskQueue.oidcAudience())
            );
            var email = new JwtClaimValidator<String>(
                    "email", taskQueue.serviceAccountEmail()::equalsIgnoreCase
            );
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience, email));
            Converter<Jwt, AbstractAuthenticationToken> converter = jwt -> new JwtAuthenticationToken(
                    jwt, List.of(new SimpleGrantedAuthority("SCOPE_internal.tasks")), jwt.getSubject()
            );
            http.authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_internal.tasks"))
                    .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt
                            .decoder(decoder)
                            .jwtAuthenticationConverter(converter)
                    ));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain publicApiSecurityFilterChain(HttpSecurity http) throws Exception {
        common(http);
        if (authEnabled) {
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers(
                                    "/actuator/health/**", "/api/v1/webhooks/**", "/api/v1/certificates/**"
                            ).permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/v1/legal/manifest").permitAll()
                            .anyRequest().authenticated()
                    )
                    .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> {}));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }

    private static HttpSecurity common(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                );
    }
}
