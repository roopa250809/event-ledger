package com.eventledger.gateway.observability;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Adds the active trace ID to Gateway responses. */
@Component
public class TraceResponseInterceptor implements HandlerInterceptor {
    private final Tracer tracer;

    public TraceResponseInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (tracer.currentSpan() != null) {
            response.setHeader("X-Trace-Id", tracer.currentSpan().context().traceId());
        }
        return true;
    }
}
