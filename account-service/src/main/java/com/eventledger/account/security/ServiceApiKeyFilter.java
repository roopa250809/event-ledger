package com.eventledger.account.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class ServiceApiKeyFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-Service-Api-Key";
    private final byte[] expectedApiKey;

    public ServiceApiKeyFilter(@Value("${event-ledger.security.service-api-key}") String expectedApiKey) {
        if (expectedApiKey == null || expectedApiKey.length() < 20) {
            throw new IllegalArgumentException("ACCOUNT_SERVICE_API_KEY must contain at least 20 characters");
        }
        this.expectedApiKey = expectedApiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String suppliedApiKey = request.getHeader(HEADER);
        if (suppliedApiKey != null && MessageDigest.isEqual(
                expectedApiKey, suppliedApiKey.getBytes(StandardCharsets.UTF_8))) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "event-gateway", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }
}
