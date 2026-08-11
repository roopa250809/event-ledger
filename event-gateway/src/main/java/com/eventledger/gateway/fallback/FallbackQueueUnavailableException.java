package com.eventledger.gateway.fallback;

/** Signals that the configured fallback queue cannot accept an event. */
public class FallbackQueueUnavailableException extends RuntimeException {
    public FallbackQueueUnavailableException(String message) {
        super(message);
    }

    public FallbackQueueUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
