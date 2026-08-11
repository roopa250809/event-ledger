package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(String accountId, BigDecimal balance, String currency, Instant asOf) {
}
