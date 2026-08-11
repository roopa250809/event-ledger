# Event Ledger

A Java 21/Spring Boot implementation of two independently runnable services that accept financial
transaction events, tolerate duplicate and out-of-order delivery, and remain observable and responsive
when a downstream service fails.

## Architecture

```text
Client
  |
  | HTTPS + scoped Bearer JWT
  v
Event Gateway :8080             H2 gateway-db
  |
  | synchronous REST + service API key + W3C trace context
  v
Account Service :8081           H2 account-db
```

The **Event Gateway** validates and stores public events, enforces idempotency, exposes event queries,
and calls the Account Service. The **Account Service** owns account transactions, balances, and recent
transaction history. The services have separate databases and share no runtime state.

### Security boundary

The Gateway is an OAuth2 resource server. Local demos use HS256 JWTs; production can set
`GATEWAY_JWT_JWK_SET_URI` to validate asymmetric tokens from an OAuth2/OIDC provider. It verifies the
signature, expiration, issuer, and audience, then applies least-privilege scopes:

| Route | Required scope |
|---|---|
| `POST /events` | `events.write` |
| `GET /events...` | `events.read` |
| `GET /accounts...` | `accounts.read` |
| `/actuator/**` | `ops` |

The required assessment endpoint `GET /health` remains anonymous and returns only basic diagnostics.
All other Gateway routes require authentication. The internal Account Service requires a separate
`X-Service-Api-Key` on every route, including health and actuator endpoints. The Gateway adds this
credential automatically; clients never receive it. API requests are limited per authenticated JWT
subject and Gateway instance (120 requests/minute by default) and return `429` plus `Retry-After`
when exhausted.

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

The checked-in credential defaults are strictly for local demonstration. For any shared environment,
copy `.env.example` to `.env`, replace both secrets, and mint JWTs using the same Base64 JWT secret.

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

## Authentication and HTTPS

Generate a one-hour local development token in PowerShell:

```powershell
$token = powershell -ExecutionPolicy Bypass -File ./scripts/New-DevToken.ps1
```

The script grants the demo client all API and operations scopes. Parameters can restrict scopes or
change the subject, issuer, audience, lifetime, and secret. It is a development convenience, not an
identity provider; production should supply JWTs from the organization's OAuth2 authorization server
and configure its JWK Set URI.

HTTPS is configurable directly on the Gateway. To run the local containers with a generated,
self-signed development certificate:

```powershell
powershell -ExecutionPolicy Bypass -File ./scripts/New-DevCertificate.ps1
docker compose -f docker-compose.yml -f docker-compose.tls.yml up --build
```

The Gateway then uses `https://localhost:8080`. Browsers and curl will warn because the development
certificate is self-signed. In production, terminate TLS at the ingress/load balancer or mount a
managed PKCS#12 keystore and set the `GATEWAY_SSL_*` variables. Never commit a real certificate,
private key, service API key, or JWT signing secret.

## Try the API

Submit an event:

```powershell
curl.exe -i -X POST http://localhost:8080/events `
  -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" `
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

```powershell
curl.exe -H "Authorization: Bearer $token" http://localhost:8080/events/evt-001
curl.exe -H "Authorization: Bearer $token" "http://localhost:8080/events?account=acct-123"
```

Read Account Service state through the Gateway:

```powershell
curl.exe -H "Authorization: Bearer $token" http://localhost:8080/accounts/acct-123/balance
curl.exe -H "Authorization: Bearer $token" http://localhost:8080/accounts/acct-123
```

## Response behavior

| Situation | Status |
|---|---:|
| Newly applied event | `201 Created` |
| Identical duplicate | `200 OK` |
| Invalid request | `400 Bad Request` |
| Missing/invalid JWT | `401 Unauthorized` |
| Missing required scope | `403 Forbidden` |
| Conflicting event ID or currency | `409 Conflict` |
| Per-client rate limit exceeded | `429 Too Many Requests` |
| Missing event/account | `404 Not Found` |
| Account Service timeout, outage, or open circuit | `503 Service Unavailable` |

Errors are JSON objects containing `code`, `message`, `traceId`, `timestamp`, and field-level
`details` where applicable.

## Observability

- `GET /health` on both services reports service and database status. Gateway health is public and
  also reports Account Service availability without declaring Gateway-local reads down; direct
  Account Service health requires the internal service key.
- `/actuator/prometheus` exposes JVM, HTTP, Resilience4j, and custom metrics. Gateway actuator routes
  require the `ops` scope; Account Service actuator routes require the internal service key.
- Custom metrics include `event_submissions_total`, `account_service_calls_total`,
  `account_service_latency_seconds`, and `transactions_applied_total` (Prometheus naming).
- Logs are JSON and include timestamp, level, service, trace ID, span ID, message, and structured
  event identifiers. Credentials, full financial payloads, and metadata are not logged.
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
delivery, bounded retries, circuit breaker behavior, graceful degradation, trace propagation, JWT
authorization, service authentication, rate limiting, and a real two-container flow. The integration
profile requires Docker; unit/service tests do not.

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
