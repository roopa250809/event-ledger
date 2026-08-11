package com.eventledger.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Persists an account's identity, currency, and creation time. */
@Entity
@Table(name = "accounts")
public class AccountEntity {
    @Id
    @Column(name = "account_id", nullable = false, updatable = false, length = 100)
    private String accountId;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountEntity() {
    }

    public AccountEntity(String accountId, String currency, Instant createdAt) {
        this.accountId = accountId;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
