package com.eventledger.gateway.fallback;

import com.eventledger.gateway.service.EventLedgerService;
import com.eventledger.gateway.service.EventNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "event-ledger.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaFallbackConsumer {
    private static final Logger log = LoggerFactory.getLogger(KafkaFallbackConsumer.class);
    private final EventLedgerService eventLedgerService;

    public KafkaFallbackConsumer(EventLedgerService eventLedgerService) {
        this.eventLedgerService = eventLedgerService;
    }

    @KafkaListener(topics = "${event-ledger.kafka.topic}")
    public void retry(String eventId) {
        try {
            eventLedgerService.retryQueued(eventId);
        } catch (EventNotFoundException exception) {
            log.atError().addKeyValue("eventId", eventId)
                    .log("Discarding orphaned Kafka fallback record");
        }
    }
}
