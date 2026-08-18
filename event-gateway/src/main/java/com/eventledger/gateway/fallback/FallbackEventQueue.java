package com.eventledger.gateway.fallback;

import com.eventledger.gateway.domain.EventEntity;

import java.time.Instant;

/** Atomically stages an event for asynchronous fallback delivery. */
public interface FallbackEventQueue {
    EventEntity enqueue(String eventId, Instant attemptedAt, String reason);
}
