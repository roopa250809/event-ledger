package com.eventledger.account.service;

/** Signals that an account does not exist in the ledger. */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(String accountId) {
        super("Account '%s' was not found".formatted(accountId));
    }
}
