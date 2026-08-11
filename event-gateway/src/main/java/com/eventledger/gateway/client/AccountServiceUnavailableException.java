package com.eventledger.gateway.client;

/** Represents a retryable Account Service communication failure. */
public class AccountServiceUnavailableException extends RuntimeException {
    public AccountServiceUnavailableException(String message) {
        super(message);
    }

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
