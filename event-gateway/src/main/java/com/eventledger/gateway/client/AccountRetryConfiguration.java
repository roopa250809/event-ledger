package com.eventledger.gateway.client;

import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.github.resilience4j.core.IntervalFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Configures bounded exponential retry delays with jitter for Account Service calls. */
@Configuration
public class AccountRetryConfiguration {
    @Bean
    RetryConfigCustomizer accountServiceRetryCustomizer(
            @Value("${account-service.retry.initial-wait}") Duration initialWait,
            @Value("${account-service.retry.multiplier}") double multiplier,
            @Value("${account-service.retry.jitter-factor}") double jitterFactor,
            @Value("${account-service.retry.max-wait}") Duration maxWait) {
        var delay = IntervalFunction.ofExponentialRandomBackoff(
                initialWait, multiplier, jitterFactor, maxWait);
        return RetryConfigCustomizer.of("accountService", builder -> builder.intervalFunction(delay));
    }
}
