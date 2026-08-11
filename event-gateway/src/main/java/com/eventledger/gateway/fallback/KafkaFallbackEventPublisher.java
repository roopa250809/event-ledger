package com.eventledger.gateway.fallback;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Publishes broker-acknowledged event IDs to the Kafka retry topic. */
@Component
@ConditionalOnProperty(prefix = "event-ledger.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaFallbackEventPublisher implements FallbackEventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final String topic;
    private final Duration publishTimeout;

    public KafkaFallbackEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                       MeterRegistry meterRegistry,
                                       @Value("${event-ledger.kafka.topic}") String topic,
                                       @Value("${event-ledger.kafka.publish-timeout}") Duration publishTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.topic = topic;
        this.publishTimeout = publishTimeout;
    }

    @Override
    public void enqueue(String eventId) {
        try {
            kafkaTemplate.send(topic, eventId, eventId)
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            meterRegistry.counter("event.fallback.published", "outcome", "success").increment();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            meterRegistry.counter("event.fallback.published", "outcome", "failure").increment();
            throw new FallbackQueueUnavailableException("Kafka fallback publishing was interrupted", exception);
        } catch (Exception exception) {
            meterRegistry.counter("event.fallback.published", "outcome", "failure").increment();
            throw new FallbackQueueUnavailableException("Kafka fallback is unavailable", exception);
        }
    }
}
