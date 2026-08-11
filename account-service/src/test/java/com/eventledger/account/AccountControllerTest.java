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
import java.util.List;

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
    void everyArrivalPermutationProducesTheSameFinancialBalance() throws Exception {
        var credit100 = new Posting("credit-100", "CREDIT", "100.0000", "2026-05-15T14:00:00Z");
        var debit25 = new Posting("debit-25", "DEBIT", "25.0000", "2026-05-15T14:05:00Z");
        var credit10 = new Posting("credit-10", "CREDIT", "10.0000", "2026-05-15T13:55:00Z");
        var permutations = List.of(
                List.of(credit100, debit25, credit10),
                List.of(credit100, credit10, debit25),
                List.of(debit25, credit100, credit10),
                List.of(debit25, credit10, credit100),
                List.of(credit10, credit100, debit25),
                List.of(credit10, debit25, credit100));

        for (int index = 0; index < permutations.size(); index++) {
            String accountId = "acct-permutation-" + index;
            for (Posting posting : permutations.get(index)) {
                submit(accountId, posting.eventId() + "-" + index, posting.type(),
                        posting.amount(), posting.timestamp()).andExpect(status().isCreated());
            }
            mockMvc.perform(get("/accounts/{accountId}/balance", accountId)
                            .header("X-Service-Api-Key", SERVICE_API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(85.0));
        }
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
    void rejectsAmountsThatExceedTheSupportedFinancialScale() throws Exception {
        submit("evt-over-precision", "CREDIT", "1.00001", "2026-05-15T14:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field").value(org.hamcrest.Matchers.hasItem("amount")));
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
        return submit("acct-123", eventId, type, amount, timestamp);
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            String accountId, String eventId, String type, String amount, String timestamp) throws Exception {
        return mockMvc.perform(post("/accounts/{accountId}/transactions", accountId)
                .header("X-Service-Api-Key", SERVICE_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload(accountId, eventId, type, amount, timestamp)));
    }

    private String validPayload(String eventId, String type, String amount, String timestamp) {
        return validPayload("acct-123", eventId, type, amount, timestamp);
    }

    private String validPayload(String accountId, String eventId, String type, String amount, String timestamp) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s",
                  "metadata": {"source": "test"}
                }
                """.formatted(eventId, accountId, type, amount, timestamp);
    }

    /** Defines a transaction used to verify arrival-order permutations. */
    private record Posting(String eventId, String type, String amount, String timestamp) {
    }
}
