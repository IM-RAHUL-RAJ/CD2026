# Sprint 8 — Run Book & Verification

## Overview

Sprint 8 delivers a complete authentication layer (`Sprint 08 Auth Service`,
separate NestJS service) that **owns the users table and all password
material**, plus a Trade REST API (`sprint8/`, port **8085**) that trusts
tokens issued by the auth service, and a frontend (port **5000**) that
registers/logs in against the auth service and calls the trade API.

- **Auth service** — `sprint8-auth-service/` (NestJS, TypeScript, Jest)
- **Trade API** — `sprint8/` (Spring Boot / MyBatis, port 8085)
- **Frontend** — `sprint8/front-end/` (Flask, port 5000; register is the default page at `/`)
- **Database** — PostgreSQL `trading_system_db`, schemas `auth` + `trading`

## Ports

| Service     | Port |
| ----------- | ---- |
| Auth API    | 3000 |
| Trade API   | 8085 |
| Frontend    | 5000 |

## Prerequisites

- Java 21 + Maven 3.9+ (trade-api), Node 20/24 + npm (auth service), Python 3
  (frontend), PostgreSQL 13+ (uses `gen_random_uuid`).

## 1. Database bootstrap

```bash
psql -U postgres -h localhost -p 5432 -c "CREATE DATABASE trading_system_db;"
psql -U postgres -h localhost -p 5432 -d trading_system_db -v ON_ERROR_STOP=1 -f sprint8/db/schema.sql
psql -U postgres -h localhost -p 5432 -d trading_system_db -v ON_ERROR_STOP=1 -f sprint8/db/seed-data.sql
```

`schema.sql` sets `search_path = trading, public` for the postgres role in this
database so unqualified `account`/`orders`/`holding`/`instrument` queries (used
by the executor & ETL) keep resolving to `trading`.

Seed demo credentials:

| username | email             | password        |
| -------- | ----------------- | --------------- |
| demo     | demo@example.com  | Capstone@2026   |

Account **1** is seeded with an AAPL holding (50 @ 150.00), a filled order and
100000.00 USD.

## 2. Run the auth service (port 3000)

```bash
cd sprint8-auth-service
cp .env.example .env      # edit DB credentials if needed
npm install
npm test                  # 19 tests in 4 suites
npm run build
npm start
```

Swagger UI: http://localhost:3000/docs — OpenAPI JSON: http://localhost:3000/docs/json

## 3. Run the trade API (port 8085)

```bash
cd sprint8
cp .env.example .env      # edit DB credentials / JWT secret if needed
mvn package -DskipTests
java -jar target/sprint-08-trade-api-1.0-SNAPSHOT.jar
```

Health: http://localhost:8085/health

The `JWT_SECRET` and `JWT_ISSUER` **must match** the auth service's
configuration — the trade API rejects tokens with a different issuer, a blank
`sub`, or an empty `roles` claim.

## 4. Run the frontend (port 5000)

```bash
cd sprint8/front-end
pip install -r requirements.txt
python app.py
```

- `/` — **Create account** (registration, default page)
- `/login` — sign in with username or email
- `/dashboard` — profile / place order / holdings / orders (uses the trade API
  with the stored Bearer token; refreshes the access token on 401 via
  `/auth/refresh`)

## API reference (contract)

| Method | Path            | Auth  | Success | Errors |
| ------ | --------------- | ----- | ------- | ------ |
| POST   | `/auth/register`| none  | 201 token pair + user | 409 `AUTH-409`, 422 `VAL-422` |
| POST   | `/auth/login`   | none  | 200 token pair + user | 401 `AUTH-401`, 429 `RATE-429`, 422 `VAL-422` |
| POST   | `/auth/refresh` | none  | 200 rotated token pair | 401 `AUTH-401`, 422 `VAL-422` |
| GET    | `/auth/me`      | Bearer| 200 profile + accountId | 401 `AUTH-401` |

Error envelope: `{ "errorCode": string, "message": string }`.

Registration atomically creates `auth.users` row **and** a
`trading.account` (USD, 100000.00, ACTIVE, version 1) in one transaction.
JWT claims: `sub` = user `uuid`, `accountId`, `roles` (always non-empty),
`iss` = `auth-service`, 15-minute expiry.

Trade API endpoints (Bearer token required for `/api/v1/**`; cross-account
access returns 401):

- `GET /api/v1/accounts/{id}` — account incl. formatted id `ACC-000001` and `holderName` joined from `auth.users`
- `GET /api/v1/accounts/{id}/balance`
- `GET /api/v1/accounts/{id}/positions`
- `GET /api/v1/accounts/{id}/orders?status=&from=&to=`
- `POST /api/v1/orders` — place order (`idempotencyKey` required, min 8 chars)
- `DELETE /api/v1/orders/{idempotencyKey}` — cancel a `NEW` order

## Verified end-to-end (this machine, 2026-09-24)

1. `CREATE DATABASE trading_system_db` + `schema.sql` + `seed-data.sql` — OK.
2. Auth: register `tuser2` → **201** (accountId 3); duplicate register → **409 `AUTH-409`**.
3. Login `tuser1` → **200** (accountId 2); wrong password → **401 `AUTH-401`**;
   unknown user → **401 `AUTH-401`** (identical envelope, uniform 150ms delay).
4. `GET /auth/me` → **200** with accountId.
5. `POST /auth/refresh` → **200** rotated pair; replaying an already-rotated
   token → **401** and its whole family is revoked.
6. Trade API with an auth-issued token:
   - `GET /api/v1/accounts/2` → **200** `ACC-000002`, holder `Test User`, 100000.00.
   - `GET /api/v1/accounts/1` (demo) → `ACC-000001`, holder `Demo Investor`,
     positions `AAPL 50 @ 150.00`, orders `ORD-seed-0001 FILLED`.
   - Cross-account `accounts/1` with tuser1's token → **401**.
   - Place order `AAPL BUY 5` → **200** status `NEW`; duplicate idempotency key → **409**;
     cancel → **200** `CANCELLED`.
7. `GET /health` (trade-api) → **200**.
8. Frontend: `/` serves registration page, `/login` serves login page; both link
   to each other; reserved `AUTH_URL=http://localhost:3000`, `BACKEND_URL=http://localhost:8085`.

## Known limitations (environment)

- **Docker is not installed on this machine** — the provided `Dockerfile`s
  (trade-api and auth service) and `docker-compose.yml` are untested here.
- **Kafka is unreachable** (`10.8.71.240:9092`): order placement succeeds and is
  persisted, but the `ORDER_PLACED` event cannot be published (logged:
  “Failed to publish order to Kafka, but order was saved”). Executor/order-fill
  E2E needs the Kafka broker, which is outside this machine.
- Register's refresh-token insert is not inside the registration transaction
  (a failure there would leave the user created but return 500). Known nuance;
  login still works for such a user.
- Login throttle state is in-memory (single instance). Documented in the auth
  service README; a shared store (e.g. Redis) is recommended before
  multi-instance deployment.