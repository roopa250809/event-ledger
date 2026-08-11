package com.eventledger.gateway.api;

import java.time.Instant;
import java.util.List;

/** Provides the structured error contract returned by the Gateway. */
public record ErrorResponse(String code, String message, String traceId, Instant timestamp,
                            List<FieldErrorDetail> details) {
    /** Describes one invalid request field. */
    public record FieldErrorDetail(String field, String message) {
    }
}
