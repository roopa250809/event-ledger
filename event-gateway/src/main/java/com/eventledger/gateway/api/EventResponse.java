package com.eventledger.gateway.api;

import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.domain.ProcessingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Represents a stored event and its processing status. */
public record EventResponse(
        String eventId,
        String accountId,
        EventType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        Map<String, Object> metadata,
        ProcessingStatus status,
        Instant receivedAt,
        Instant lastAttemptAt,
        String failureReason
) {
}
