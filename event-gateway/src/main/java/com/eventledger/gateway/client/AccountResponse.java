package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Represents account details returned through the Gateway. */
public record AccountResponse(String accountId, BigDecimal balance, String currency, Instant createdAt,
                              List<AccountTransactionResponse> recentTransactions) {
}
