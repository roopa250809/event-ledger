package com.eventledger.account.security;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Secures all Account Service routes for authenticated service calls. */
@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain accountSecurityFilterChain(HttpSecurity http,
                                                    ServiceApiKeyFilter serviceApiKeyFilter,
                                                    ObjectMapper objectMapper,
                                                    Tracer tracer) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("SERVICE"))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                objectMapper, tracer, request, response, HttpStatus.UNAUTHORIZED,
                                "UNAUTHORIZED", "A valid service API key is required"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                objectMapper, tracer, request, response, HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "The caller is not permitted to access this resource")))
                .addFilterBefore(serviceApiKeyFilter, AnonymousAuthenticationFilter.class);
        return http.build();
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
            response.setHeader("WWW-Authenticate", "ApiKey realm=\"account-service\"");
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
