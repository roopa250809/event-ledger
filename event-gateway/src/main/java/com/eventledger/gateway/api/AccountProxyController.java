package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountResponse;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.BalanceResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes authenticated account queries through the public Gateway. */
@RestController
@RequestMapping("/accounts")
@Validated
public class AccountProxyController {
    private final AccountServiceClient accountServiceClient;

    public AccountProxyController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return accountServiceClient.balance(accountId);
    }

    @GetMapping("/{accountId}")
    public AccountResponse details(@PathVariable String accountId,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int recentLimit) {
        return accountServiceClient.details(accountId, recentLimit);
    }
}
