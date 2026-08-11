# Event Ledger

A Java 21/Spring Boot implementation of two independently runnable services that accept financial
transaction events, tolerate duplicate and out-of-order delivery, and remain observable and responsive
when a downstream service fails.

## Architecture

```text
Client
  |
  | synchronous REST
  v
Event Gateway :8080             H2 gateway-db
  |
  | synchronous REST + W3C trace context
  v
Account Service :8081           H2 account-db
```

The **Event Gateway** validates and stores public events, enforces idempotency, exposes event queries,
and calls the Account Service. The **Account Service** owns account transactions, balances, and recent
transaction history. The services have separate databases and share no runtime state.

The committed contracts are in [`contracts/gateway-api.yaml`](contracts/gateway-api.yaml) and
[`contracts/account-service-api.yaml`](contracts/account-service-api.yaml).

## Key decisions

### Duplicate delivery

Both services enforce a database-unique `eventId` and compare a canonical SHA-256 fingerprint of the
business payload. An identical duplicate returns the original event (`200 OK`) and cannot alter the
balance twice. Reusing an ID with different data returns `409 Conflict`. Defense-in-depth idempotency
at the Account Service makes a Gateway retry safe if the Account Service commits but its HTTP response
is lost.

### Out-of-order delivery

`eventTimestamp` is stored separately from arrival/application time. Gateway listings use
`eventTimestamp ASC, eventId ASC`, giving deterministic chronological results. Account balances are
computed from the authoritative transaction ledger as `SUM(CREDIT) - SUM(DEBIT)`, so arrival order
cannot affect the result. Money uses `BigDecimal`, never floating-point arithmetic.

An account is single-currency: its first transaction establishes the currency and a later mismatch is
rejected with `409 Conflict`. This prevents invalid cross-currency arithmetic.

### Resiliency

The Gateway's Account Service client uses:

- 500 ms connect and 1 second response timeouts;
- at most three attempts with exponential backoff;
- a Resilience4j circuit breaker that opens after repeated transient failures;
- no retries for downstream `4xx` responses.

These values are externally configurable. A failed post returns a structured `503` and records the
Gateway event as `FAILED`, allowing an identical resubmission to retry safely. Gateway-local reads keep
working while the Account Service is down. A queue was intentionally not added: the required boundary
is synchronous REST, while asynchronous fallback is listed as bonus scope.

## Prerequisites

- Java 21 for manual execution
- Docker with Docker Compose for the simplest startup and full integration tests

Maven does not need to be installed; the Maven Wrapper is included.

## Start with Docker Compose

```shell
docker compose up --build
```

The public Gateway is available at `http://localhost:8080`. The Account Service is reachable only on
the Compose network, matching its internal-service role.

Stop the system with:

```shell
docker compose down
```

Add `-v` only when you intentionally want to remove both persisted H2 volumes.

## Start manually

In terminal one:

```shell
./mvnw -pl account-service spring-boot:run
```

In terminal two:

```shell
./mvnw -pl event-gateway spring-boot:run
```

On Windows PowerShell, use `./mvnw.cmd` instead of `./mvnw`.

Manual mode stores separate H2 files below each process's `data` directory. Configuration can be
overridden with `ACCOUNT_DB_URL`, `GATEWAY_DB_URL`, and `ACCOUNT_SERVICE_URL`.

## Try the API

Submit an event:

```shell
curl -i -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": {"source": "mainframe-batch", "batchId": "B-9042"}
  }'
```

Read Gateway-local events:

```shell
curl http://localhost:8080/events/evt-001
curl "http://localhost:8080/events?account=acct-123"
```

Read Account Service state through the Gateway:

```shell
curl http://localhost:8080/accounts/acct-123/balance
curl http://localhost:8080/accounts/acct-123
```

## Response behavior

| Situation | Status |
|---|---:|
| Newly applied event | `201 Created` |
| Identical duplicate | `200 OK` |
| Invalid request | `400 Bad Request` |
| Conflicting event ID or currency | `409 Conflict` |
| Missing event/account | `404 Not Found` |
| Account Service timeout, outage, or open circuit | `503 Service Unavailable` |

Errors are JSON objects containing `code`, `message`, `traceId`, `timestamp`, and field-level
`details` where applicable.

## Observability

- `GET /health` on both services reports service and database status. Gateway health also reports
  Account Service availability without declaring Gateway-local reads down.
- `/actuator/prometheus` exposes JVM, HTTP, Resilience4j, and custom metrics.
- Custom metrics include `event_submissions_total`, `account_service_calls_total`,
  `account_service_latency_seconds`, and `transactions_applied_total` (Prometheus naming).
- Logs are JSON and include timestamp, level, service, trace ID, span ID, message, and structured
  event identifiers. Full financial payloads and metadata are not logged.
- Micrometer Tracing with the OpenTelemetry bridge creates traces and propagates W3C `traceparent`
  headers from Gateway to Account Service. `X-Trace-Id` is also returned for support correlation.

## Tests

Run service tests:

```shell
./mvnw test
```

Run all tests, including the Docker-based Gateway-to-Account flow:

```shell
./mvnw verify -Pintegration
```

The suite covers validation, balance calculation, duplicate and conflicting IDs, out-of-order
delivery, bounded retries, circuit breaker behavior, graceful degradation, trace propagation, and a
real two-container flow. The integration profile requires Docker; unit/service tests do not.

## Project structure

```text
event-ledger/
|-- account-service/       internal account ledger and queries
|-- event-gateway/         public API, local event store, resilient REST client
|-- integration-tests/     Docker-based full-flow test
|-- contracts/             OpenAPI 3.1 service contracts
|-- docker-compose.yml
`-- pom.xml                Maven reactor
```
