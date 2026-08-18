package com.eventledger.gateway.fallback;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/** Configures the Kafka retry topic and unlimited transient retries. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "event-ledger.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaFallbackConfiguration {
    @Bean
    NewTopic accountRetryTopic(@Value("${event-ledger.kafka.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler kafkaFallbackErrorHandler(
            @Value("${event-ledger.kafka.retry-interval}") Duration retryInterval) {
        return new DefaultErrorHandler(new FixedBackOff(
                retryInterval.toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
