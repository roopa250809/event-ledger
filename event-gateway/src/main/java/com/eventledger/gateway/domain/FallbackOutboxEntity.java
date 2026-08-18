package com.eventledger.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Stores a durable Kafka message until broker acknowledgment is recorded. */
@Entity
@Table(name = "fallback_outbox", indexes = {
        @Index(name = "idx_outbox_pending_attempt", columnList = "published_at,next_attempt_at,created_at")
})
public class FallbackOutboxEntity {
    @Id
    @Column(name = "outbox_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 100)
    private String eventId;

    @Column(nullable = false, updatable = false, length = 200)
    private String topic;

    @Column(name = "message_key", nullable = false, updatable = false, length = 100)
    private String messageKey;

    @Column(nullable = false, updatable = false, length = 1000)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Version
    private long version;

    protected FallbackOutboxEntity() {
    }

    public FallbackOutboxEntity(String eventId, String topic, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.topic = topic;
        this.messageKey = eventId;
        this.payload = eventId;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public void markPublished(Instant attemptedAt) {
        attemptCount++;
        lastAttemptAt = attemptedAt;
        publishedAt = attemptedAt;
        lastError = null;
    }

    public void markPublishFailed(Instant attemptedAt, Instant retryAt, String reason) {
        attemptCount++;
        lastAttemptAt = attemptedAt;
        nextAttemptAt = retryAt;
        String resolved = reason == null ? "Kafka publish failed" : reason;
        lastError = resolved.substring(0, Math.min(500, resolved.length()));
    }

    public UUID getId() { return id; }
    public String getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastError() { return lastError; }
}
