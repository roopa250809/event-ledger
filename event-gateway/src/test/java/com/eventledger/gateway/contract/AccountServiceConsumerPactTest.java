package com.eventledger.gateway.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTest;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.domain.EventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** Defines the Gateway's transaction-application contract with Account Service. */
@PactConsumerTest
class AccountServiceConsumerPactTest {
    private static final String SERVICE_KEY = "test-service-key-1234567890";
    private static final EventRequest EVENT = new EventRequest(
            "evt-contract-001", "acct-contract-123", EventType.CREDIT,
            new BigDecimal("150.00"), "USD", Instant.parse("2026-05-15T14:02:11Z"),
            Map.of("source", "contract-test", "batchId", "B-9042"));

    @Pact(provider = "account-service", consumer = "event-gateway")
    RequestResponsePact applyTransaction(PactDslWithProvider builder) {
        var response = new PactDslJsonBody()
                .stringValue("eventId", "evt-contract-001")
                .stringValue("accountId", "acct-contract-123")
                .stringValue("type", "CREDIT")
                .numberValue("amount", 150.00)
                .stringValue("currency", "USD")
                .stringValue("eventTimestamp", "2026-05-15T14:02:11Z")
                .object("metadata")
                    .stringValue("source", "contract-test")
                    .stringValue("batchId", "B-9042")
                .closeObject()
                .stringMatcher("appliedAt", "^\\d{4}-\\d{2}-\\d{2}T.*Z$", "2026-05-15T14:02:12Z");

        return builder
                .given("account acct-contract-123 has no transactions")
                .uponReceiving("a CREDIT transaction from the Gateway")
                    .path("/accounts/acct-contract-123/transactions")
                    .method("POST")
                    .headers(Map.of(
                            HttpHeaders.CONTENT_TYPE, "application/json",
                            "X-Service-Api-Key", SERVICE_KEY))
                    .body("""
                            {
                              "eventId": "evt-contract-001",
                              "accountId": "acct-contract-123",
                              "type": "CREDIT",
                              "amount": 150.00,
                              "currency": "USD",
                              "eventTimestamp": "2026-05-15T14:02:11Z",
                              "metadata": {"source": "contract-test", "batchId": "B-9042"}
                            }
                            """)
                .willRespondWith()
                    .status(201)
                    .headers(Map.of(HttpHeaders.CONTENT_TYPE, "application/json"))
                    .body(response)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "applyTransaction")
    void appliesTransactionThroughProductionClient(MockServer mockServer) {
        RestClient restClient = RestClient.builder()
                .baseUrl(mockServer.getUrl())
                .defaultHeader("X-Service-Api-Key", SERVICE_KEY)
                .build();
        var client = new AccountServiceClient(restClient, new SimpleMeterRegistry());

        client.apply(EVENT);
    }
}
