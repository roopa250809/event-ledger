package com.eventledger.gateway.fallback;

/** Publishes events for asynchronous fallback processing. */
public interface FallbackEventPublisher {
    void enqueue(String eventId);
}
