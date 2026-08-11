package com.eventledger.account.api;

import java.time.Instant;
import java.util.List;

/** Provides the structured error contract returned by the Account Service. */
public record ErrorResponse(String code, String message, String traceId, Instant timestamp,
                            List<FieldErrorDetail> details) {
    /** Describes one invalid request field. */
    public record FieldErrorDetail(String field, String message) {
    }

    public static ErrorResponse of(String code, String message, String traceId) {
        return new ErrorResponse(code, message, traceId, Instant.now(), List.of());
    }
}
