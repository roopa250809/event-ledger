package com.eventledger.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Represents account details and recent transaction history. */
public record AccountResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        Instant createdAt,
        List<TransactionResponse> recentTransactions
) {
}
