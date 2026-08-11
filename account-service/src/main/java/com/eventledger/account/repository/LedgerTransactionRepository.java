package com.eventledger.account.repository;

import com.eventledger.account.domain.LedgerTransactionEntity;
import com.eventledger.account.domain.TransactionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/** Provides transaction persistence, ordering, and balance aggregation queries. */
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransactionEntity, String> {
    List<LedgerTransactionEntity> findByAccountIdOrderByEventTimestampDescEventIdAsc(
            String accountId, Pageable pageable);

    @Query("select sum(case when t.type = :creditType then t.amount else -t.amount end) " +
            "from LedgerTransactionEntity t " +
            "where t.accountId = :accountId")
    BigDecimal calculateNetBalance(@Param("accountId") String accountId,
                                   @Param("creditType") TransactionType creditType);
}
