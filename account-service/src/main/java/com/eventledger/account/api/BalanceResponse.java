package com.eventledger.account.api;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(String accountId, BigDecimal balance, String currency, Instant asOf) {
}
