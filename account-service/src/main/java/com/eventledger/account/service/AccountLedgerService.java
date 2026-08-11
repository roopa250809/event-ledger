package com.eventledger.account.service;

import com.eventledger.account.api.AccountResponse;
import com.eventledger.account.api.BalanceResponse;
import com.eventledger.account.api.TransactionRequest;
import com.eventledger.account.api.TransactionResponse;
import com.eventledger.account.domain.AccountEntity;
import com.eventledger.account.domain.LedgerTransactionEntity;
import com.eventledger.account.domain.TransactionType;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.LedgerTransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Applies idempotent transactions and calculates account state. */
@Service
public class AccountLedgerService {
    private static final Logger log = LoggerFactory.getLogger(AccountLedgerService.class);

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final PayloadFingerprint fingerprint;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public AccountLedgerService(AccountRepository accountRepository,
                                LedgerTransactionRepository transactionRepository,
                                PayloadFingerprint fingerprint,
                                MeterRegistry meterRegistry,
                                PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.fingerprint = fingerprint;
        this.meterRegistry = meterRegistry;
        this.clock = Clock.systemUTC();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ApplyResult apply(String accountId, TransactionRequest request) {
        if (!accountId.equals(request.accountId())) {
            throw new IllegalArgumentException("Path accountId must match body accountId");
        }

        var canonical = fingerprint.canonicalize(request, accountId);
        var existing = transactionRepository.findById(request.eventId());
        if (existing.isPresent()) {
            return existingResult(existing.get(), canonical.hash());
        }

        try {
            return transactionTemplate.execute(status -> applyNew(accountId, request, canonical));
        } catch (DataIntegrityViolationException race) {
            LedgerTransactionEntity winner = transactionRepository.findById(request.eventId())
                    .orElseThrow(() -> race);
            return existingResult(winner, canonical.hash());
        }
    }

    private ApplyResult applyNew(String accountId, TransactionRequest request,
                                 PayloadFingerprint.CanonicalPayload canonical) {
        var existing = transactionRepository.findById(request.eventId());
        if (existing.isPresent()) {
            return existingResult(existing.get(), canonical.hash());
        }
        AccountEntity account = accountRepository.findById(accountId)
                .orElseGet(() -> accountRepository.save(
                        new AccountEntity(accountId, canonical.currency(), clock.instant())));
        if (!account.getCurrency().equals(canonical.currency())) {
            throw new CurrencyConflictException(accountId, account.getCurrency(), canonical.currency());
        }

        var transaction = new LedgerTransactionEntity(
                request.eventId(), accountId, request.type(), request.amount(), canonical.currency(),
                request.eventTimestamp(), canonical.metadataJson(), canonical.hash(), clock.instant());
        transactionRepository.saveAndFlush(transaction);

        counter("applied", request.type()).increment();
        log.atInfo().addKeyValue("eventId", request.eventId())
                .addKeyValue("accountId", accountId)
                .addKeyValue("type", request.type())
                .log("Transaction applied");
        return new ApplyResult(toResponse(transaction), true);
    }

    @Transactional(readOnly = true)
    public BalanceResponse balance(String accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return new BalanceResponse(accountId, calculateBalance(accountId), account.getCurrency(), clock.instant());
    }

    @Transactional(readOnly = true)
    public AccountResponse details(String accountId, int recentLimit) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        List<TransactionResponse> transactions = transactionRepository
                .findByAccountIdOrderByEventTimestampDescEventIdAsc(accountId, PageRequest.of(0, recentLimit))
                .stream().map(this::toResponse).toList();
        return new AccountResponse(accountId, calculateBalance(accountId), account.getCurrency(),
                account.getCreatedAt(), transactions);
    }

    private ApplyResult existingResult(LedgerTransactionEntity existing, String incomingHash) {
        if (!existing.getPayloadHash().equals(incomingHash)) {
            counter("conflict", existing.getType()).increment();
            throw new EventConflictException(existing.getEventId());
        }
        counter("duplicate", existing.getType()).increment();
        log.atInfo().addKeyValue("eventId", existing.getEventId())
                .addKeyValue("accountId", existing.getAccountId())
                .log("Duplicate transaction returned without reapplying");
        return new ApplyResult(toResponse(existing), false);
    }

    private BigDecimal calculateBalance(String accountId) {
        BigDecimal credits = valueOrZero(transactionRepository
                .sumByAccountIdAndType(accountId, TransactionType.CREDIT));
        BigDecimal debits = valueOrZero(transactionRepository
                .sumByAccountIdAndType(accountId, TransactionType.DEBIT));
        return credits.subtract(debits);
    }

    private TransactionResponse toResponse(LedgerTransactionEntity entity) {
        return new TransactionResponse(entity.getEventId(), entity.getAccountId(), entity.getType(),
                entity.getAmount(), entity.getCurrency(), entity.getEventTimestamp(),
                fingerprint.readMetadata(entity.getMetadataJson()), entity.getAppliedAt());
    }

    private Counter counter(String outcome, TransactionType type) {
        return Counter.builder("transactions.applied")
                .description("Account transaction processing outcomes")
                .tag("outcome", outcome)
                .tag("type", type.name().toLowerCase())
                .register(meterRegistry);
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Couples a transaction response with its creation outcome. */
    public record ApplyResult(TransactionResponse response, boolean created) {
    }
}
