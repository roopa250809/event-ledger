package com.eventledger.gateway.fallback;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "event-ledger.kafka", name = "enabled", havingValue = "false")
public class DisabledFallbackEventPublisher implements FallbackEventPublisher {
    @Override
    public void enqueue(String eventId) {
        throw new FallbackQueueUnavailableException("Kafka fallback is disabled");
    }
}
