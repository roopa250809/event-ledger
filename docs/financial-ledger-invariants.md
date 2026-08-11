# Financial ledger invariants

The Account Service's immutable transaction ledger is the source of truth. The current balance is a
derived value, not independently mutable state.

## Balance correctness

For the set `U` of committed, unique events belonging to an account:

```text
signedAmount(event) = amount when CREDIT, -amount when DEBIT
balance(account) = sum(signedAmount(event)) for event in U
```

Because addition is commutative, every arrival permutation produces the same balance. The repository
calculates the signed sum in one database statement so a balance read uses one statement-level
snapshot rather than combining results observed at different times.

The global `eventId` primary key makes membership in `U` database-enforced. An identical retry returns
the original transaction. Reuse with a different canonical payload hash is rejected. The database
constraint, rather than a read-before-write check, resolves concurrent duplicates.

## Financial time

- `eventTimestamp` is the business-effective time supplied by the source and orders statements.
- `appliedAt` is the server-controlled posting time and records when the ledger accepted the event.
- `eventId` is the deterministic tie-breaker when effective timestamps are equal.

Current balance includes every committed transaction. A future accounting-period feature must define
whether late events targeting a closed period are rejected or represented by explicit adjustment
entries; it must never edit a posted ledger row.

## Money and corrections

Amounts are positive `BigDecimal` values limited to 15 integer and four fractional digits. Currency is
normalized to an uppercase three-letter code, and one account cannot mix currencies. Corrections are
new reversal/replacement events, never mutations of posted transactions.

## Scaling without weakening correctness

The signed ledger aggregate is the correctness-first implementation. If its query cost becomes too
high, introduce a materialized balance projection only as an optimization. Insert the unique ledger
row and update the projection atomically in the same database transaction, then continuously reconcile
the projection against the authoritative signed ledger sum.
