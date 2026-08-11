package com.eventledger.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http,
                                                    RateLimitFilter rateLimitFilter,
                                                    ObjectMapper objectMapper,
                                                    Tracer tracer) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/events").hasAuthority("SCOPE_events.write")
                        .requestMatchers(HttpMethod.GET, "/events", "/events/**")
                        .hasAuthority("SCOPE_events.read")
                        .requestMatchers(HttpMethod.GET, "/accounts/**")
                        .hasAuthority("SCOPE_accounts.read")
                        .requestMatchers("/actuator/**").hasAuthority("SCOPE_ops")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                objectMapper, tracer, request, response, HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED", "A valid Bearer token is required")))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(
                        (request, response, exception) -> writeError(
                                objectMapper, tracer, request, response, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "The token does not grant the required scope")))
                .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${event-ledger.security.jwt.secret}") String encodedSecret,
                          @Value("${event-ledger.security.jwt.issuer}") String issuer,
                          @Value("${event-ledger.security.jwt.audience}") String audience,
                          @Value("${event-ledger.security.jwt.jwk-set-uri:}") String jwkSetUri) {
        NimbusJwtDecoder decoder;
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        } else {
            byte[] secret = Base64.getDecoder().decode(encodedSecret);
            if (secret.length < 32) {
                throw new IllegalArgumentException("GATEWAY_JWT_SECRET must decode to at least 32 bytes");
            }
            var key = new SecretKeySpec(secret, "HmacSHA256");
            decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        }
        OAuth2TokenValidator<Jwt> audienceValidator = token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "The token audience is invalid", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        return decoder;
    }

    private static void writeError(ObjectMapper objectMapper,
                                   Tracer tracer,
                                   HttpServletRequest request,
                                   HttpServletResponse response,
                                   HttpStatus status,
                                   String code,
                                   String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        String traceId = tracer.currentSpan() == null ? "" : tracer.currentSpan().context().traceId();
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "code", code,
                "message", message,
                "traceId", traceId,
                "timestamp", Instant.now(),
                "details", List.of(),
                "path", request.getRequestURI()));
    }
}
