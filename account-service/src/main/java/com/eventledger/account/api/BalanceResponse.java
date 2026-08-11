package com.eventledger.account.api;

import java.math.BigDecimal;
import java.time.Instant;

/** Represents an account's current calculated balance. */
public record BalanceResponse(String accountId, BigDecimal balance, String currency, Instant asOf) {
}
