package com.eventledger.gateway.fallback;

public interface FallbackEventPublisher {
    void enqueue(String eventId);
}
