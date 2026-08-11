package com.eventledger.account.repository;

import com.eventledger.account.domain.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Provides persistence operations for account records. */
public interface AccountRepository extends JpaRepository<AccountEntity, String> {
}
