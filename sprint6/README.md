# Sprint 6: Trade REST API

Spring Boot service exposing the six endpoints defined in `contracts/trade-api.yaml`.

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker & Docker Compose

## Structure

```
sprint6/
├── src/main/java/com/trading/
│   ├── domain/                  ← Sprint 5 domain (copied as source)
│   └── tradeapi/
│       ├── controller/          ← HTTP layer only (OrderController, AccountController)
│       ├── service/             ← TradeService — @Transactional, domain calls
│       ├── mapper/              ← MyBatis interfaces (AccountMapper, etc.)
│       ├── dto/                 ← Request/response records
│       ├── entity/              ← DB row POJOs (AccountRecord, etc.)
│       ├── security/            ← JwtAuthenticationFilter, JwtService
│       └── exception/           ← GlobalExceptionHandler, OrderNotFoundException
├── src/main/resources/
│   ├── application.yml
│   └── mapper/                  ← MyBatis XML mappers (all #{} parameterised)
├── src/test/java/com/trading/
│   ├── domain/                  ← Sprint 5 domain tests (copied)
│   └── tradeapi/                ← Controller slice tests, service unit tests, JWT tests
└── Dockerfile                   ← Multi-stage build
```

## Build

```bash
cd sprint-06-trade-api
mvn clean verify
```

## Run locally (full stack via Docker Compose)

```bash
# From repo root
cp .env.example .env
# Edit .env and set real DB_PASSWORD and JWT_SECRET
docker compose up --build
```

The service will be available at `http://localhost:8080`.
Health: `GET http://localhost:8080/health`

## Authentication

All `/api/v1/**` routes require `Authorization: Bearer <JWT>`.

The JWT must be signed with the same `JWT_SECRET` configured at runtime (via environment variable — never a properties file). Claims must include `accountId` (numeric). The token `accountId` must match the account being accessed, otherwise `ACC-403` is returned.

## Error catalogue

| Code    | HTTP | Meaning                                             |
|---------|------|-----------------------------------------------------|
| ACC-404 | 404  | Account not found                                   |
| ACC-403 | 403  | Account not active, or token accountId mismatch     |
| INS-404 | 404  | Instrument not found or delisted                    |
| ORD-400 | 400  | Insufficient funds (BUY)                            |
| ORD-409 | 409  | Insufficient holdings / duplicate key / not cancellable |
| VAL-422 | 422  | Field validation failed                             |
| AUTH-401| 401  | Missing, malformed, expired or wrongly signed token |

Every error response has exactly this shape:
```json
{ "errorCode": "ORD-409", "message": "Insufficient holdings" }
```
