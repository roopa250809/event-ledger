package com.eventledger.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "events", indexes = {
        @Index(name = "idx_event_account_time", columnList = "account_id,event_timestamp,event_id")
})
public class EventEntity {
    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(name = "account_id", nullable = false, updatable = false, length = 100)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private EventType type;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 10)
    private ProcessingStatus processingStatus;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Version
    private long version;

    protected EventEntity() {
    }

    public EventEntity(String eventId, String accountId, EventType type, BigDecimal amount,
                       String currency, Instant eventTimestamp, String metadataJson,
                       String payloadHash, Instant receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadataJson = metadataJson;
        this.payloadHash = payloadHash;
        this.processingStatus = ProcessingStatus.PENDING;
        this.receivedAt = receivedAt;
    }

    public void markApplied(Instant attemptedAt) {
        processingStatus = ProcessingStatus.APPLIED;
        lastAttemptAt = attemptedAt;
        failureReason = null;
    }

    public void markQueued(Instant attemptedAt, String reason) {
        processingStatus = ProcessingStatus.QUEUED;
        lastAttemptAt = attemptedAt;
        failureReason = truncatedReason(reason);
    }

    public void markFailed(Instant attemptedAt, String reason) {
        processingStatus = ProcessingStatus.FAILED;
        lastAttemptAt = attemptedAt;
        failureReason = truncatedReason(reason);
    }

    private String truncatedReason(String reason) {
        String resolved = reason == null ? "Account Service unavailable" : reason;
        return resolved.substring(0, Math.min(500, resolved.length()));
    }

    public String getEventId() { return eventId; }
    public String getAccountId() { return accountId; }
    public EventType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public String getMetadataJson() { return metadataJson; }
    public String getPayloadHash() { return payloadHash; }
    public ProcessingStatus getProcessingStatus() { return processingStatus; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public String getFailureReason() { return failureReason; }
}
