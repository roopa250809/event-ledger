package com.eventledger.gateway;

import com.eventledger.gateway.repository.EventRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies Gateway API, resilience, tracing, and validation behavior. */
@SpringBootTest
@AutoConfigureMockMvc
class EventGatewayTest {
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
    private ObjectMapper objectMapper;

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
    void appliesAndReturnsAnIdenticalDuplicateOnlyOnce() throws Exception {
        stubSuccessfulApply();

        submit("evt-001", "CREDIT", "150.00", "2026-05-15T14:02:11Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        submit("evt-001", "CREDIT", "150.0", "2026-05-15T14:02:11Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-001"));

        ACCOUNT_SERVICE.verify(1, postRequestedFor(urlEqualTo("/accounts/acct-123/transactions")));
        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void ordersEventsByTheirOriginalTimestamp() throws Exception {
        stubSuccessfulApply();
        submit("evt-later", "DEBIT", "40", "2026-05-15T14:05:00Z")
                .andExpect(status().isCreated());
        submit("evt-earlier", "CREDIT", "100", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        mockMvc.perform(get("/events").with(clientToken()).param("account", "acct-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-earlier"))
                .andExpect(jsonPath("$[1].eventId").value("evt-later"));
    }

    @Test
    void rejectsAConflictingDuplicate() throws Exception {
        stubSuccessfulApply();
        submit("evt-conflict", "CREDIT", "50", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());
        submit("evt-conflict", "DEBIT", "50", "2026-05-15T14:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));
    }

    @Test
    void returns503ButKeepsLocalReadsAvailableWhenAccountServiceFails() throws Exception {
        ACCOUNT_SERVICE.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching("/accounts/.+/transactions"))
                .willReturn(aResponse().withStatus(503)));

        submit("evt-failed", "CREDIT", "25", "2026-05-15T14:00:00Z")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"));

        mockMvc.perform(get("/events/evt-failed").with(clientToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/events").with(clientToken()).param("account", "acct-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-failed"));
        ACCOUNT_SERVICE.verify(2, postRequestedFor(urlEqualTo("/accounts/acct-123/transactions")));
    }

    @Test
    void balanceProxyReturns503WhenAccountServiceFails() throws Exception {
        ACCOUNT_SERVICE.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                        urlEqualTo("/accounts/acct-123/balance"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(get("/accounts/acct-123/balance").with(clientToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void identicalResubmissionRecoversAPreviouslyFailedEvent() throws Exception {
        ACCOUNT_SERVICE.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        urlPathMatching("/accounts/.+/transactions"))
                .willReturn(aResponse().withStatus(503)));
        submit("evt-recovery", "CREDIT", "25", "2026-05-15T14:00:00Z")
                .andExpect(status().isServiceUnavailable());

        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        ACCOUNT_SERVICE.resetAll();
        stubSuccessfulApply();
        submit("evt-recovery", "CREDIT", "25.0", "2026-05-15T14:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesCreateOneGatewayEvent() throws Exception {
        stubSuccessfulApply();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var request = (java.util.concurrent.Callable<Integer>) () -> {
                ready.countDown();
                start.await();
                return submit("evt-concurrent", "CREDIT", "30", "2026-05-15T14:00:00Z")
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            ready.await();
            start.countDown();

            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(201, 200);
        }

        assertThat(eventRepository.count()).isEqualTo(1);
    }

    @Test
    void propagatesTraceContextToTheAccountService() throws Exception {
        stubSuccessfulApply();

        submit("evt-trace", "CREDIT", "10", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        ACCOUNT_SERVICE.verify(postRequestedFor(urlEqualTo("/accounts/acct-123/transactions"))
                .withHeader("traceparent", matching("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"))
                .withHeader("X-Trace-Id", matching("[0-9a-f]{32}"))
                .withHeader("X-Service-Api-Key", equalTo("test-service-key-1234567890")));
    }

    @Test
    void circuitBreakerOpensAndFailsWith503() throws Exception {
        ACCOUNT_SERVICE.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching("/accounts/.+/transactions"))
                .willReturn(aResponse().withStatus(503)));

        submit("evt-breaker", "CREDIT", "10", "2026-05-15T14:00:00Z")
                .andExpect(status().isServiceUnavailable());

        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("accountService");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        submit("evt-open", "CREDIT", "10", "2026-05-15T14:01:00Z")
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void validatesThePublicPayload() throws Exception {
        String invalid = """
                {
                  "eventId": "evt-invalid",
                  "accountId": "acct-123",
                  "type": "TRANSFER",
                  "amount": -1,
                  "currency": "USD",
                  "eventTimestamp": "not-a-time"
                }
                """;
        mockMvc.perform(post("/events").with(clientToken())
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsAmountsThatWouldRequireFinancialRounding() throws Exception {
        submit("evt-over-precision", "CREDIT", "1.00001", "2026-05-15T14:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field", hasItem("amount")));
    }

    @Test
    void acceptsTheDocumentedSamplePayload() throws Exception {
        stubSuccessfulApply();

        mockMvc.perform(post("/events").with(clientToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(samplePayload())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.metadata.source").value("mainframe-batch"))
                .andExpect(jsonPath("$.metadata.batchId").value("B-9042"));
    }

    @ParameterizedTest(name = "rejects sample payload missing required field: {0}")
    @ValueSource(strings = {
            "eventId", "accountId", "type", "amount", "currency", "eventTimestamp"
    })
    void rejectsEachMissingRequiredField(String missingField) throws Exception {
        ObjectNode payload = samplePayload();
        payload.remove(missingField);

        mockMvc.perform(post("/events").with(clientToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field", hasItem(missingField)));
    }

    @Test
    void requiresAuthenticationAndTheCorrectScope() throws Exception {
        mockMvc.perform(get("/events/evt-001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/events")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_events.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    private void stubSuccessfulApply() {
        ACCOUNT_SERVICE.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathMatching("/accounts/.+/transactions"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{}")));
    }

    private ObjectNode samplePayload() throws IOException {
        var resource = new ClassPathResource("examples/event-request.json");
        try (var input = resource.getInputStream()) {
            return (ObjectNode) objectMapper.readTree(input);
        }
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            String eventId, String type, String amount, String timestamp) throws Exception {
        return mockMvc.perform(post("/events")
                .with(clientToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "eventId": "%s",
                          "accountId": "acct-123",
                          "type": "%s",
                          "amount": %s,
                          "currency": "USD",
                          "eventTimestamp": "%s",
                          "metadata": {"source": "test"}
                        }
                        """.formatted(eventId, type, amount, timestamp)));
    }

    private static RequestPostProcessor clientToken() {
        return jwt().jwt(token -> token.subject("test-client"))
                .authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_events.read"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_events.write"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_accounts.read"));
    }
}
