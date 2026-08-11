package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AccountResponse(String accountId, BigDecimal balance, String currency, Instant createdAt,
                              List<AccountTransactionResponse> recentTransactions) {
}
