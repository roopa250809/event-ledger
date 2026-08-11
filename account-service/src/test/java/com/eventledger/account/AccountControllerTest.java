package com.eventledger.account;

import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies Account Service API, ledger, validation, and security behavior. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {
    private static final String SERVICE_API_KEY = "test-service-key-1234567890";
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void computesBalanceRegardlessOfArrivalOrder() throws Exception {
        submit("evt-later", "DEBIT", "25.00", "2026-05-15T14:05:00Z")
                .andExpect(status().isCreated());
        submit("evt-earlier", "CREDIT", "100.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/acct-123/balance").header("X-Service-Api-Key", SERVICE_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-123"))
                .andExpect(jsonPath("$.balance").value(75.0))
                .andExpect(jsonPath("$.currency").value("USD"));

        mockMvc.perform(get("/accounts/acct-123").header("X-Service-Api-Key", SERVICE_API_KEY)
                        .param("recentLimit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentTransactions[0].eventId").value("evt-later"))
                .andExpect(jsonPath("$.recentTransactions[1].eventId").value("evt-earlier"));
    }

    @Test
    void identicalDuplicateReturnsOriginalAndDoesNotChangeBalance() throws Exception {
        submit("evt-duplicate", "CREDIT", "50.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());
        submit("evt-duplicate", "CREDIT", "50.0", "2026-05-15T14:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-duplicate"));

        mockMvc.perform(get("/accounts/acct-123/balance").header("X-Service-Api-Key", SERVICE_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50.0));
    }

    @Test
    void concurrentDuplicatesAreAppliedOnlyOnce() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var request = (java.util.concurrent.Callable<Integer>) () -> {
                ready.countDown();
                start.await();
                return submit("evt-concurrent", "CREDIT", "40.00", "2026-05-15T14:00:00Z")
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(request);
            var second = executor.submit(request);
            ready.await();
            start.countDown();

            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(201, 200);
        }

        mockMvc.perform(get("/accounts/acct-123/balance").header("X-Service-Api-Key", SERVICE_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(40.0));
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void reusedEventIdWithDifferentPayloadReturnsConflict() throws Exception {
        submit("evt-conflict", "CREDIT", "50.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        submit("evt-conflict", "DEBIT", "50.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_CONFLICT"));
    }

    @Test
    void rejectsInvalidPayloadsWithUsefulErrors() throws Exception {
        String invalid = """
                {
                  "eventId": "evt-invalid",
                  "accountId": "acct-123",
                  "type": "CREDIT",
                  "amount": 0,
                  "currency": "US",
                  "eventTimestamp": "2026-05-15T14:00:00Z"
                }
                """;

        mockMvc.perform(post("/accounts/acct-123/transactions")
                        .header("X-Service-Api-Key", SERVICE_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.length()").value(2));
    }

    @Test
    void rejectsCurrencyChangesForAnAccount() throws Exception {
        submit("evt-usd", "CREDIT", "10.00", "2026-05-15T14:00:00Z")
                .andExpect(status().isCreated());

        String euros = validPayload("evt-eur", "CREDIT", "10.00", "2026-05-15T14:01:00Z")
                .replace("\"USD\"", "\"EUR\"");
        mockMvc.perform(post("/accounts/acct-123/transactions")
                        .header("X-Service-Api-Key", SERVICE_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(euros))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_CONFLICT"));
    }

    @Test
    void reportsDatabaseHealth() throws Exception {
        mockMvc.perform(get("/health").header("X-Service-Api-Key", SERVICE_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database.status").value("UP"));
    }

    @Test
    void rejectsCallsWithoutTheGatewayServiceCredential() throws Exception {
        mockMvc.perform(get("/accounts/acct-123/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            String eventId, String type, String amount, String timestamp) throws Exception {
        return mockMvc.perform(post("/accounts/acct-123/transactions")
                .header("X-Service-Api-Key", SERVICE_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload(eventId, type, amount, timestamp)));
    }

    private String validPayload(String eventId, String type, String amount, String timestamp) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "acct-123",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s",
                  "metadata": {"source": "test"}
                }
                """.formatted(eventId, type, amount, timestamp);
    }
}
