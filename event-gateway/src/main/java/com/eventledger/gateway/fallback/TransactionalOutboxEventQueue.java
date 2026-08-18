package com.eventledger.gateway.fallback;

import com.eventledger.gateway.domain.EventEntity;
import com.eventledger.gateway.domain.FallbackOutboxEntity;
import com.eventledger.gateway.domain.ProcessingStatus;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.repository.FallbackOutboxRepository;
import com.eventledger.gateway.service.EventNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Persists the queued event transition and its Kafka message in one transaction. */
@Component
@ConditionalOnProperty(prefix = "event-ledger.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TransactionalOutboxEventQueue implements FallbackEventQueue {
    private final EventRepository eventRepository;
    private final FallbackOutboxRepository outboxRepository;
    private final String topic;

    public TransactionalOutboxEventQueue(EventRepository eventRepository,
                                         FallbackOutboxRepository outboxRepository,
                                         @Value("${event-ledger.kafka.topic}") String topic) {
        this.eventRepository = eventRepository;
        this.outboxRepository = outboxRepository;
        this.topic = topic;
    }

    @Override
    @Transactional
    public EventEntity enqueue(String eventId, Instant attemptedAt, String reason) {
        EventEntity event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        if (event.getProcessingStatus() == ProcessingStatus.APPLIED
                || event.getProcessingStatus() == ProcessingStatus.QUEUED) {
            return event;
        }

        event.markQueued(attemptedAt, reason);
        outboxRepository.save(new FallbackOutboxEntity(eventId, topic, attemptedAt));
        return eventRepository.save(event);
    }
}
