package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.EventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Defines the validated public event submission payload. */
public record EventRequest(
        @NotBlank @Size(max = 100) String eventId,
        @NotBlank @Size(max = 100) String accountId,
        @NotNull EventType type,
        @NotNull @DecimalMin(value = "0.0", inclusive = false)
        @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "(?i)^[A-Z]{3}$") String currency,
        @NotNull Instant eventTimestamp,
        Map<String, Object> metadata
) {
}
