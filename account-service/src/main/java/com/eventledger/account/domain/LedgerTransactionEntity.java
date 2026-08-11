package com.eventledger.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Persists an immutable transaction in the account ledger. */
@Entity
@Table(name = "ledger_transactions", indexes = {
        @Index(name = "idx_transaction_account_time", columnList = "account_id,event_timestamp,event_id")
})
public class LedgerTransactionEntity {
    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(name = "account_id", nullable = false, updatable = false, length = 100)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(name = "metadata_json", nullable = false, updatable = false, length = 10000)
    private String metadataJson;

    @Column(name = "payload_hash", nullable = false, updatable = false, length = 64)
    private String payloadHash;

    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    protected LedgerTransactionEntity() {
    }

    public LedgerTransactionEntity(String eventId, String accountId, TransactionType type,
                                   BigDecimal amount, String currency, Instant eventTimestamp,
                                   String metadataJson, String payloadHash, Instant appliedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadataJson = metadataJson;
        this.payloadHash = payloadHash;
        this.appliedAt = appliedAt;
    }

    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public String getMetadataJson() { return metadataJson; }
    public String getPayloadHash() { return payloadHash; }
    public Instant getAppliedAt() { return appliedAt; }
}
