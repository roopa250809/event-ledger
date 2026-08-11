package com.eventledger.gateway.service;

/** Signals reuse of an event ID with a different payload. */
public class EventConflictException extends RuntimeException {
    public EventConflictException(String eventId) {
        super("Event ID '%s' is already associated with a different payload".formatted(eventId));
    }
}
