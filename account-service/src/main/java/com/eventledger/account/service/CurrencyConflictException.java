package com.eventledger.account.service;

public class CurrencyConflictException extends RuntimeException {
    public CurrencyConflictException(String accountId, String expected, String actual) {
        super("Account '%s' uses %s and cannot accept a %s transaction"
                .formatted(accountId, expected, actual));
    }
}
