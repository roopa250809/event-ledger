package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;

/** Represents an account balance returned through the Gateway. */
public record BalanceResponse(String accountId, BigDecimal balance, String currency, Instant asOf) {
}
