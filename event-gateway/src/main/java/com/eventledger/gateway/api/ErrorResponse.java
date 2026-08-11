package com.eventledger.gateway.api;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(String code, String message, String traceId, Instant timestamp,
                            List<FieldErrorDetail> details) {
    public record FieldErrorDetail(String field, String message) {
    }
}
