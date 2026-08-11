package com.eventledger.gateway.client;

import com.eventledger.gateway.api.EventRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class AccountServiceClient {
    private static final String RESILIENCE_INSTANCE = "accountService";

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public AccountServiceClient(RestClient accountRestClient, MeterRegistry meterRegistry) {
        this.restClient = accountRestClient;
        this.meterRegistry = meterRegistry;
    }

    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public void apply(EventRequest event) {
        timed("apply", () -> {
            restClient.post()
                    .uri("/accounts/{accountId}/transactions", event.accountId())
                    .body(event)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        throw rejected(response.getStatusCode(), response.getBody().readAllBytes());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new AccountServiceUnavailableException(
                                "Account Service returned " + response.getStatusCode());
                    })
                    .toBodilessEntity();
            return Boolean.TRUE;
        });
    }

    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public BalanceResponse balance(String accountId) {
        return timed("balance", () -> restClient.get()
                .uri("/accounts/{accountId}/balance", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw rejected(response.getStatusCode(), response.getBody().readAllBytes());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new AccountServiceUnavailableException(
                            "Account Service returned " + response.getStatusCode());
                })
                .body(BalanceResponse.class));
    }

    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    public AccountResponse details(String accountId, int recentLimit) {
        return timed("details", () -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/accounts/{accountId}")
                        .queryParam("recentLimit", recentLimit).build(accountId))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw rejected(response.getStatusCode(), response.getBody().readAllBytes());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new AccountServiceUnavailableException(
                            "Account Service returned " + response.getStatusCode());
                })
                .body(AccountResponse.class));
    }

    public boolean isHealthy() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private <T> T timed(String operation, SupplierWithResult<T> supplier) {
        long start = System.nanoTime();
        try {
            T result = supplier.get();
            meterRegistry.counter("account.service.calls", "operation", operation, "outcome", "success")
                    .increment();
            return result;
        } catch (AccountServiceRejectedException exception) {
            meterRegistry.counter("account.service.calls", "operation", operation, "outcome", "rejected")
                    .increment();
            throw exception;
        } catch (ResourceAccessException exception) {
            meterRegistry.counter("account.service.calls", "operation", operation, "outcome", "unavailable")
                    .increment();
            throw new AccountServiceUnavailableException("Account Service is unreachable", exception);
        } finally {
            Timer.builder("account.service.latency").tag("operation", operation)
                    .register(meterRegistry).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private static AccountServiceRejectedException rejected(HttpStatusCode status, byte[] body) {
        String message = body.length == 0
                ? "Account Service rejected the request"
                : new String(body, StandardCharsets.UTF_8);
        return new AccountServiceRejectedException(status, message);
    }

    @FunctionalInterface
    private interface SupplierWithResult<T> {
        T get();
    }
}
