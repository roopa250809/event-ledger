package com.eventledger.account.api;

import com.eventledger.account.service.AccountLedgerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes internal transaction and account query endpoints. */
@RestController
@RequestMapping("/accounts")
@Validated
public class AccountController {
    private final AccountLedgerService ledgerService;

    public AccountController(AccountLedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> apply(@PathVariable String accountId,
                                                     @Valid @RequestBody TransactionRequest request) {
        var result = ledgerService.apply(accountId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        return ledgerService.balance(accountId);
    }

    @GetMapping("/{accountId}")
    public AccountResponse details(@PathVariable String accountId,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int recentLimit) {
        return ledgerService.details(accountId, recentLimit);
    }
}
