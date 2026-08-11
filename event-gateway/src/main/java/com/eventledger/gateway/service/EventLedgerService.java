package com.eventledger.gateway.service;

import com.eventledger.gateway.api.EventRequest;
import com.eventledger.gateway.api.EventResponse;
import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.domain.EventEntity;
import com.eventledger.gateway.domain.ProcessingStatus;
import com.eventledger.gateway.repository.EventRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public EventLedgerService(EventRepository eventRepository,
                              AccountServiceClient accountServiceClient,
                              PayloadFingerprint fingerprint,
                              MeterRegistry meterRegistry) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.fingerprint = fingerprint;
        this.meterRegistry = meterRegistry;
        this.clock = Clock.systemUTC();
    }

    public SubmissionResult submit(EventRequest request) {
        var canonical = fingerprint.canonicalize(request);
        var existingEvent = eventRepository.findById(request.eventId());
        boolean newSubmission = existingEvent.isEmpty();
        EventEntity event = existingEvent
                .map(existing -> verifyDuplicate(existing, canonical.hash()))
                .orElseGet(() -> createPending(request, canonical));

        if (event.getProcessingStatus() == ProcessingStatus.APPLIED) {
            count("duplicate");
            return new SubmissionResult(toResponse(event), false);
        }

        try {
            accountServiceClient.apply(request);
            event.markApplied(clock.instant());
            event = saveAfterAttempt(event);
            count("applied");
            log.atInfo().addKeyValue("eventId", event.getEventId())
                    .addKeyValue("accountId", event.getAccountId())
                    .log("Event applied by Account Service");
            return new SubmissionResult(toResponse(event), newSubmission);
        } catch (CallNotPermittedException exception) {
            event.markFailed(clock.instant(), "Account Service circuit breaker is open");
            saveAfterAttempt(event);
            count("circuit_open");
            throw new AccountServiceUnavailableException(
                    "Account Service is temporarily unavailable; the circuit breaker is open", exception);
        } catch (AccountServiceUnavailableException exception) {
            event.markFailed(clock.instant(), exception.getMessage());
            saveAfterAttempt(event);
            count("failed");
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

    private EventEntity createPending(EventRequest request, PayloadFingerprint.CanonicalPayload canonical) {
        var createdAt = clock.instant();
        var candidate = new EventEntity(request.eventId(), request.accountId(), request.type(), request.amount(),
                canonical.currency(), request.eventTimestamp(), canonical.metadataJson(), canonical.hash(), createdAt);
        try {
            return eventRepository.saveAndFlush(candidate);
        } catch (DataIntegrityViolationException race) {
            EventEntity winner = eventRepository.findById(request.eventId()).orElseThrow(() -> race);
            return verifyDuplicate(winner, canonical.hash());
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

    private EventResponse toResponse(EventEntity entity) {
        return new EventResponse(entity.getEventId(), entity.getAccountId(), entity.getType(),
                entity.getAmount(), entity.getCurrency(), entity.getEventTimestamp(),
                fingerprint.readMetadata(entity.getMetadataJson()), entity.getProcessingStatus(),
                entity.getReceivedAt(), entity.getLastAttemptAt(), entity.getFailureReason());
    }

    private void count(String outcome) {
        meterRegistry.counter("event.submissions", "outcome", outcome).increment();
    }

    public record SubmissionResult(EventResponse response, boolean created) {
    }
}
