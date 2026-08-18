package com.eventledger.gateway.repository;

import com.eventledger.gateway.domain.EventEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Provides event persistence and chronological account queries. */
public interface EventRepository extends JpaRepository<EventEntity, String> {
    List<EventEntity> findByAccountIdOrderByEventTimestampAscEventIdAsc(String accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EventEntity e where e.eventId = :eventId")
    Optional<EventEntity> findByIdForUpdate(@Param("eventId") String eventId);
}
