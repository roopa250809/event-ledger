package com.eventledger.gateway.fallback;

import com.eventledger.gateway.repository.FallbackOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Relays unpublished outbox messages to Kafka with at-least-once delivery. */
@Component
@ConditionalOnProperty(prefix = "event-ledger.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxRelay.class);

    private final FallbackOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final Duration publishTimeout;
    private final Duration publishRetryInterval;
    private final Duration retention;
    private final int batchSize;
    private final Clock clock = Clock.systemUTC();

    public KafkaOutboxRelay(FallbackOutboxRepository outboxRepository,
                            KafkaTemplate<String, String> kafkaTemplate,
                            MeterRegistry meterRegistry,
                            PlatformTransactionManager transactionManager,
                            @Value("${event-ledger.kafka.outbox.publish-timeout}") Duration publishTimeout,
                            @Value("${event-ledger.kafka.outbox.publish-retry-interval}") Duration publishRetryInterval,
                            @Value("${event-ledger.kafka.outbox.retention}") Duration retention,
                            @Value("${event-ledger.kafka.outbox.batch-size}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.publishTimeout = publishTimeout;
        this.publishRetryInterval = publishRetryInterval;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${event-ledger.kafka.outbox.relay-interval}")
    public void publishPending() {
        outboxRepository.findPendingIds(clock.instant(), PageRequest.of(0, batchSize))
                .forEach(this::publishOne);
    }

    @Scheduled(fixedDelayString = "${event-ledger.kafka.outbox.cleanup-interval}")
    public void deletePublished() {
        transactionTemplate.executeWithoutResult(status -> {
            long deleted = outboxRepository.deleteByPublishedAtBefore(clock.instant().minus(retention));
            if (deleted > 0) {
                meterRegistry.counter("event.fallback.outbox", "outcome", "deleted").increment(deleted);
            }
        });
    }

    private void publishOne(UUID outboxId) {
        transactionTemplate.executeWithoutResult(status -> outboxRepository.findByIdForUpdate(outboxId)
                .filter(message -> message.getPublishedAt() == null)
                .ifPresent(message -> {
                    Instant attemptedAt = clock.instant();
                    try {
                        kafkaTemplate.send(message.getTopic(), message.getMessageKey(), message.getPayload())
                                .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
                        message.markPublished(attemptedAt);
                        meterRegistry.counter("event.fallback.outbox", "outcome", "published").increment();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        message.markPublishFailed(attemptedAt,
                                attemptedAt.plus(publishRetryInterval), exception.getMessage());
                        meterRegistry.counter("event.fallback.outbox", "outcome", "publish_failed").increment();
                        log.atWarn().addKeyValue("outboxId", message.getId())
                                .addKeyValue("eventId", message.getEventId())
                                .log("Outbox publication was interrupted");
                    } catch (Exception exception) {
                        message.markPublishFailed(attemptedAt,
                                attemptedAt.plus(publishRetryInterval), exception.getMessage());
                        meterRegistry.counter("event.fallback.outbox", "outcome", "publish_failed").increment();
                        log.atWarn().addKeyValue("outboxId", message.getId())
                                .addKeyValue("eventId", message.getEventId())
                                .addKeyValue("error", exception.getClass().getSimpleName())
                                .log("Outbox publication failed and will be retried");
                    }
                }));
    }
}
