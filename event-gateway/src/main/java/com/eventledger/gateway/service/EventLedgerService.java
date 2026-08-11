package com.eventledger.gateway.service;

import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.api.EventResponse;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceRejectedException;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.domain.EventEntity;
import com.eventledger.gateway.domain.ProcessingStatus;
import com.eventledger.gateway.fallback.FallbackEventPublisher;
import com.eventledger.gateway.fallback.FallbackQueueUnavailableException;
import com.eventledger.gateway.repository.EventRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class EventLedgerService {
    private static final Logger log = LoggerFactory.getLogger(EventLedgerService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final PayloadFingerprint fingerprint;
    private final FallbackEventPublisher fallbackEventPublisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public EventLedgerService(EventRepository eventRepository,
                              AccountServiceClient accountServiceClient,
                              PayloadFingerprint fingerprint,
                              FallbackEventPublisher fallbackEventPublisher,
                              MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.fingerprint = fingerprint;
        this.fallbackEventPublisher = fallbackEventPublisher;
        this.meterRegistry = meterRegistry;
        this.clock = Clock.systemUTC();
    }

    public SubmissionResult submit(EventRequest request) {
        var canonical = fingerprint.canonicalize(request);
        var existingEvent = eventRepository.findById(request.eventId());
        PendingResult pending = existingEvent
                .map(existing -> new PendingResult(verifyDuplicate(existing, canonical.hash()), false))
                .orElseGet(() -> createPending(request, canonical));
        EventEntity event = pending.event();

        if (event.getProcessingStatus() == ProcessingStatus.APPLIED) {
            count("duplicate");
            return new SubmissionResult(toResponse(event), HttpStatus.OK);
        }
        if (event.getProcessingStatus() == ProcessingStatus.QUEUED) {
            count("duplicate_queued");
            return new SubmissionResult(toResponse(event), HttpStatus.ACCEPTED);
        }

        try {
            accountServiceClient.apply(request);
            event.markApplied(clock.instant());
            event = saveAfterAttempt(event);
            count("applied");
            log.atInfo().addKeyValue("eventId", event.getEventId())
                    .addKeyValue("accountId", event.getAccountId())
                    .log("Event applied by Account Service");
            return new SubmissionResult(toResponse(event),
                    pending.created() ? HttpStatus.CREATED : HttpStatus.OK);
        } catch (CallNotPermittedException exception) {
            count("circuit_open");
            return queueForRetry(event, pending.created(),
                    "Account Service circuit breaker is open",
                    new AccountServiceUnavailableException(
                            "Account Service is temporarily unavailable; the circuit breaker is open", exception));
        } catch (AccountServiceUnavailableException exception) {
            return queueForRetry(event, pending.created(), exception.getMessage(), exception);
        }
    }

    public void retryQueued(String eventId) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        if (event.getProcessingStatus() == ProcessingStatus.APPLIED) {
            countFallback("already_applied");
            return;
        }

        EventRequest request = new EventRequest(event.getEventId(), event.getAccountId(), event.getType(),
                event.getAmount(), event.getCurrency(), event.getEventTimestamp(),
                fingerprint.readMetadata(event.getMetadataJson()));
        try {
            accountServiceClient.apply(request);
            event.markApplied(clock.instant());
            saveAfterAttempt(event);
            countFallback("applied");
            log.atInfo().addKeyValue("eventId", event.getEventId())
                    .addKeyValue("accountId", event.getAccountId())
                    .log("Kafka fallback event applied by Account Service");
        } catch (AccountServiceRejectedException exception) {
            event.markFailed(clock.instant(), exception.getMessage());
            saveAfterAttempt(event);
            countFallback("rejected");
            log.atError().addKeyValue("eventId", event.getEventId())
                    .addKeyValue("accountId", event.getAccountId())
                    .log("Kafka fallback event was permanently rejected by Account Service");
        } catch (CallNotPermittedException exception) {
            event.markQueued(clock.instant(), "Account Service circuit breaker is open");
            saveAfterAttempt(event);
            countFallback("retry");
            throw new AccountServiceUnavailableException(
                    "Account Service circuit breaker is open", exception);
        } catch (AccountServiceUnavailableException exception) {
            event.markQueued(clock.instant(), exception.getMessage());
            saveAfterAttempt(event);
            countFallback("retry");
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public EventResponse get(String eventId) {
        return eventRepository.findById(eventId).map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    @Transactional(readOnly = true)
    public List<EventResponse> listForAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAscEventIdAsc(accountId)
                .stream().map(this::toResponse).toList();
    }

    private PendingResult createPending(EventRequest request, PayloadFingerprint.CanonicalPayload canonical) {
        var createdAt = clock.instant();
        var candidate = new EventEntity(request.eventId(), request.accountId(), request.type(), request.amount(),
                canonical.currency(), request.eventTimestamp(), canonical.metadataJson(), canonical.hash(), createdAt);
        try {
            return new PendingResult(eventRepository.saveAndFlush(candidate), true);
        } catch (DataIntegrityViolationException race) {
            EventEntity winner = eventRepository.findById(request.eventId()).orElseThrow(() -> race);
            return new PendingResult(verifyDuplicate(winner, canonical.hash()), false);
        }
    }

    private EventEntity verifyDuplicate(EventEntity existing, String incomingHash) {
        if (!existing.getPayloadHash().equals(incomingHash)) {
            count("conflict");
            throw new EventConflictException(existing.getEventId());
        }
        return existing;
    }

    private EventEntity saveAfterAttempt(EventEntity event) {
        try {
            return eventRepository.saveAndFlush(event);
        } catch (ObjectOptimisticLockingFailureException race) {
            return eventRepository.findById(event.getEventId()).orElseThrow(() -> race);
        }
    }

    private SubmissionResult queueForRetry(EventEntity event,
                                           boolean created,
                                           String reason,
                                           AccountServiceUnavailableException accountFailure) {
        try {
            fallbackEventPublisher.enqueue(event.getEventId());
            event.markQueued(clock.instant(), reason);
            event = saveAfterAttempt(event);
            count("queued");
            log.atWarn().addKeyValue("eventId", event.getEventId())
                    .addKeyValue("accountId", event.getAccountId())
                    .log("Account Service unavailable; event queued in Kafka");
            HttpStatus status = event.getProcessingStatus() == ProcessingStatus.APPLIED
                    ? (created ? HttpStatus.CREATED : HttpStatus.OK)
                    : HttpStatus.ACCEPTED;
            return new SubmissionResult(toResponse(event), status);
        } catch (FallbackQueueUnavailableException queueFailure) {
            event.markFailed(clock.instant(), accountFailure.getMessage());
            saveAfterAttempt(event);
            count("failed");
            accountFailure.addSuppressed(queueFailure);
            throw accountFailure;
        }
    }

    private EventResponse toResponse(EventEntity entity) {
        return new EventResponse(entity.getEventId(), entity.getAccountId(), entity.getType(),
                entity.getAmount(), entity.getCurrency(), entity.getEventTimestamp(),
                fingerprint.readMetadata(entity.getMetadataJson()), entity.getProcessingStatus(),
                entity.getReceivedAt(), entity.getLastAttemptAt(), entity.getFailureReason());
    }

    private void count(String outcome) {
        meterRegistry.counter("event.submissions", "outcome", outcome).increment();
    }

    private void countFallback(String outcome) {
        meterRegistry.counter("event.fallback.processing", "outcome", outcome).increment();
    }

    public record SubmissionResult(EventResponse response, HttpStatus status) {
    }

    private record PendingResult(EventEntity event, boolean created) {
    }
}
