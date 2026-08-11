package com.eventledger.gateway.client;

import com.eventledger.gateway.domain.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record AccountTransactionResponse(
        String eventId, String accountId, EventType type, BigDecimal amount, String currency,
        Instant eventTimestamp, Map<String, Object> metadata, Instant appliedAt) {
}
