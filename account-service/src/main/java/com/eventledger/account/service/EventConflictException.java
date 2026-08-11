package com.eventledger.account.service;

public class EventConflictException extends RuntimeException {
    public EventConflictException(String eventId) {
        super("Event ID '%s' is already associated with a different payload".formatted(eventId));
    }
}
