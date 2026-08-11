package com.eventledger.gateway.fallback;

public class FallbackQueueUnavailableException extends RuntimeException {
    public FallbackQueueUnavailableException(String message) {
        super(message);
    }

    public FallbackQueueUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
