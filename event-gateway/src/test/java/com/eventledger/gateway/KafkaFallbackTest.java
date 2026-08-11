package com.eventledger.gateway;

import com.eventledger.gateway.repository.EventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies queued processing through an embedded Kafka broker. */
@SpringBootTest(properties = {
        "event-ledger.kafka.enabled=true",
        "event-ledger.kafka.retry-interval=100ms"
})
@AutoConfigureMockMvc
@EmbeddedKafka(kraft = true, partitions = 1, topics = "event-ledger.account-retry.v1",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class KafkaFallbackTest {
    private static final WireMockServer ACCOUNT_SERVICE = new WireMockServer(options().dynamicPort());

    static {
        ACCOUNT_SERVICE.start();
    }

    @DynamicPropertySource
    static void accountServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", ACCOUNT_SERVICE::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        ACCOUNT_SERVICE.resetAll();
        eventRepository.deleteAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    @AfterAll
    static void stopWireMock() {
        ACCOUNT_SERVICE.stop();
    }

    @Test
    void queuesThenAppliesAnEventAfterAccountServiceRecovery() throws Exception {
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.+/transactions"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/events")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_events.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-kafka-recovery",
                                  "accountId": "acct-kafka",
                                  "type": "CREDIT",
                                  "amount": 25,
                                  "currency": "USD",
                                  "eventTimestamp": "2026-05-15T14:00:00Z"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        ACCOUNT_SERVICE.resetAll();
        ACCOUNT_SERVICE.stubFor(post(urlPathMatching("/accounts/.+/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        String processingStatus = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            processingStatus = mockMvc.perform(get("/events/evt-kafka-recovery")
                            .with(jwt().authorities(
                                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                            "SCOPE_events.read"))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (processingStatus.contains("\"status\":\"APPLIED\"")) {
                break;
            }
            Thread.sleep(100);
        }
        org.assertj.core.api.Assertions.assertThat(processingStatus)
                .contains("\"status\":\"APPLIED\"");
    }
}
