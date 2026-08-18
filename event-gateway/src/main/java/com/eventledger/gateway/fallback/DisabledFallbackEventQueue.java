package com.eventledger.gateway.fallback;

import com.eventledger.gateway.domain.EventEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Rejects fallback staging when Kafka support is disabled. */
@Component
@ConditionalOnProperty(prefix = "event-ledger.kafka", name = "enabled", havingValue = "false")
public class DisabledFallbackEventQueue implements FallbackEventQueue {
    @Override
    public EventEntity enqueue(String eventId, Instant attemptedAt, String reason) {
        throw new FallbackQueueUnavailableException("Kafka fallback is disabled");
    }
}
