package com.eventledger.gateway.repository;

import com.eventledger.gateway.domain.FallbackOutboxEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Provides ordered polling and row locking for pending outbox messages. */
public interface FallbackOutboxRepository extends JpaRepository<FallbackOutboxEntity, UUID> {
    @Query("select o.id from FallbackOutboxEntity o " +
            "where o.publishedAt is null and o.nextAttemptAt <= :now " +
            "order by o.createdAt asc, o.id asc")
    List<UUID> findPendingIds(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from FallbackOutboxEntity o where o.id = :id")
    Optional<FallbackOutboxEntity> findByIdForUpdate(@Param("id") UUID id);

    long deleteByPublishedAtBefore(Instant cutoff);
}
