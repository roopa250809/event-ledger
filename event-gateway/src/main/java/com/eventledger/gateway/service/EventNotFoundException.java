package com.eventledger.gateway.service;

/** Signals that an event does not exist in the Gateway ledger. */
public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(String eventId) {
        super("Event '%s' was not found".formatted(eventId));
    }
}
