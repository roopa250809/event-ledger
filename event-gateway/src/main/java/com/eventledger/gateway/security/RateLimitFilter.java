package com.eventledger.gateway.security;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Enforces a per-client fixed-window request limit. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_SECONDS = 60;

    private final int limit;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong requestCount = new AtomicLong();

    public RateLimitFilter(@Value("${event-ledger.security.rate-limit.requests-per-minute}") int limit,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry,
                           Tracer tracer) {
        if (limit < 1) {
            throw new IllegalArgumentException("GATEWAY_RATE_LIMIT_PER_MINUTE must be at least 1");
        }
        this.limit = limit;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
        this.clock = Clock.systemUTC();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/events") || path.startsWith("/events/")
                || path.equals("/accounts") || path.startsWith("/accounts/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = clock.instant().getEpochSecond();
        if ((requestCount.incrementAndGet() & 1023) == 0) {
            long staleBefore = now - (WINDOW_SECONDS * 2);
            windows.entrySet().removeIf(entry -> entry.getValue().lastSeen() < staleBefore);
        }
        String client = authentication.getName();
        Decision decision = windows.computeIfAbsent(client, ignored -> new Window(now))
                .acquire(now, limit);
        response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
        response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        meterRegistry.counter("gateway.rate.limit.rejections").increment();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String traceId = tracer.currentSpan() == null ? "" : tracer.currentSpan().context().traceId();
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "code", "RATE_LIMIT_EXCEEDED",
                "message", "The client request limit has been exceeded; retry later",
                "traceId", traceId,
                "timestamp", Instant.now(),
                "details", List.of()));
    }

    /** Maintains request counts for one fixed time window. */
    private static final class Window {
        private long startedAt;
        private int used;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }

        private synchronized Decision acquire(long now, int limit) {
            if (now - startedAt >= WINDOW_SECONDS) {
                startedAt = now;
                used = 0;
            }
            if (used >= limit) {
                return new Decision(false, 0, Math.max(1, WINDOW_SECONDS - (now - startedAt)));
            }
            used++;
            return new Decision(true, limit - used, 0);
        }

        private synchronized long lastSeen() {
            return startedAt;
        }
    }

    /** Captures the result of a rate-limit evaluation. */
    private record Decision(boolean allowed, int remaining, long retryAfterSeconds) {
    }
}
