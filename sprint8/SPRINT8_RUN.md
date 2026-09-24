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
| Executor    | 8083 |

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

`auth.users.username` and `auth.users.email` are both `NOT NULL UNIQUE`
(duplicate registration → `409 AUTH-409`).

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
- `/analytics` — reporting dashboard reading `analytics.duckdb` (built by the
  ETL below)

The frontend purges any legacy `refreshToken` / `refresh_token` localStorage
keys on load — the refresh token is cookie-only now.

## 5. Run the order executor + analytics ETL

The executor (`executor/`, Spring Boot, port **8083**) consumes `ORDER_PLACED`
events from Kafka and fills orders in `trading_system_db` (transactional:
order → `FILLED`, account cash with optimistic `version` lock, holding upsert),
then publishes `ORDER_FILLED` / `ORDER_REJECTED` to the `trade-events` topic.
A `MarketDataPoller` also rescues still-`NEW` orders by polling quotes and
filling them once the price condition is met.

```bash
cd executor
# executor/.env must target trading_system_db and the Kafka broker:
#   DB_NAME=trading_system_db
#   KAFKA_BOOTSTRAP_SERVERS=<kafka-host>:9092   (see KAFKA_BOOTSTRAP_SERVERS in sprint8/.env)
mvn package -DskipTests
java -jar target/trade-executor-1.0-SNAPSHOT.jar
# with env overrides for DB_USER/DB_PASSWORD/FAUXNANCE_API_KEY/FAUXNANCE_BASE_URL
# (application.yml connects via ?currentSchema=trading and defaults DB_NAME=trading_system_db)
```

The jar's `EtlStartupRunner` launches `executor/etl_trigger_service.py`, which
every ~45 s runs `executor/analytics_pipeline.py` against **`trading_system_db`**
(schema is discovered via `current_schema()`; orders without `instrument_id`
are joined to `instrument` on `ticker`/`symbol`) and refreshes
`<repo>/analytics.duckdb` (`dim_account`, `dim_date`, `dim_instrument`,
`fact_trades`, `dead_letter_trades`), which the dashboard serves.

## API reference (contract)

| Method | Path            | Auth  | Success | Errors |
| ------ | --------------- | ----- | ------- | ------ |
| POST   | `/auth/register`| none  | 201 `{ message, user }` — account created, **no tokens and no cookie**; sign in afterwards | 409 `AUTH-409`, 422 `VAL-422` |
| POST   | `/auth/login`   | none  | 200 access body `{ accessToken, expiresIn, user }` + sets `refresh_token` cookie (HttpOnly; Secure; SameSite=Lax; Path=/; 7 days) | 401 `AUTH-401`, 429 `RATE-429`, 422 `VAL-422` |
| POST   | `/auth/refresh` | cookie (or body fallback)| 200 rotated access body + rotates the cookie | 401 `AUTH-401`, 422 `VAL-422` |
| POST   | `/auth/logout`  | cookie (or body fallback)| 200 `{ message }`; revokes + clears cookie | 401 `AUTH-401` |
| GET    | `/auth/me`      | Bearer| 200 profile + accountId | 401 `AUTH-401` |

Error envelope: `{ "errorCode": string, "message": string }`.

The **refresh token is never in a response body** — it is an HttpOnly + Secure +
SameSite cookie that JavaScript cannot read; the frontend only stores the
access token (localStorage) and sends `credentials: 'include'` so the cookie
travels with `/auth/refresh` and `/auth/logout`.

Registration atomically creates `auth.users` row **and** a
`trading.account` (USD, 100000.00, ACTIVE, version 1) in one transaction, but
**registration itself issues no token and sets no cookie** — the flow is
register → `POST /auth/login` → tokens (the frontend redirects to `/login`
after registration). Login returns the access body and sets the `refresh_token`
cookie.
JWT claims: `sub` = user `uuid`, `accountId`, `roles` (always non-empty),
`iss` = `auth-service`, 15-minute expiry (access `JWT_TTL_SECONDS=900`;
refresh cookie `REFRESH_TTL_SECONDS=604800`, 7 days). Tokens are issued at
every successful login (and rotated at `/auth/refresh`).

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
2. Auth: register `tuser2` → **201** `{ message, user }`, **no access token and
   no `refresh_token` cookie** (must sign in afterwards); duplicate register →
   **409 `AUTH-409`**.
3. Login `tuser1` → **200** (accountId 2, access body + cookie set); wrong password → **401 `AUTH-401`**;
   unknown user → **401 `AUTH-401`** (identical envelope, uniform 150ms delay).
4. `GET /auth/me` → **200** with accountId.
5. `POST /auth/refresh` (cookie, empty body) → **200** rotated access token +
   rotated cookie; replaying an already-rotated token → **401** and its whole
   family is revoked; `POST /auth/logout` → **200** "Logged out" and the cookie
   is cleared (Max-Age 0).
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
9. **Order fill E2E (Kafka now reachable at `10.8.71.240:9092`)** — executor
   running against `trading_system_db` (`?currentSchema=trading`):
   - `POST /api/v1/orders` `{symbol: AAPL, accountId: 1, side: BUY, quantity: 5,
     orderType: MARKET, idempotencyKey}` → **200 NEW**.
   - Executor log: consumer `trade-executor` received `ORDER_PLACED`, order 4 → 
     **FILLED @ 337.02**; DB shows `status=FILLED`, `executed_price=337.02000000`.
   - Balance 100000.00 → **98314.90** (5 × 337.02), holding `AAPL 55 @ 167.00`
     (was 50 @ 150.00), account `version` incremented.
   - The earlier order (id 3) that had stayed `NEW` because the executor was on
     the old `trading_db` also filled at 337.02 after the re-point.
10. **Analytics ETL re-pointed** — `analytics_pipeline.py` now reads
    `trading_system_db` (via `PG_SCHEMA=trading`, `current_schema()` discovery);
    warehouse rebuilt from scratch:
    `fact_trades` = exactly the 4 sprint-8 orders (seed FILLED, CANCELLED,
    orders 3+4 FILLED), `dim_account` = ACCOUNT-1 + ACCOUNT-6, `dim_instrument`
    = AAPL/EQUITY/USD/US. Legacy sprint-6 rows that collided on
    `source_order_id` were removed (old file kept as `analytics.duckdb.sprint6-backup`).
11. Tests: trade-api `mvn clean test` → **74/74**, auth `npx jest` → **19/19**.

## Known limitations (environment)

- **Docker is not installed on this machine** — the provided `Dockerfile`s
  (trade-api and auth service) and `docker-compose.yml` are untested here. The
  Kafka broker runs in Docker on the user's machine at the private IP in
  `sprint8/.env` (`KAFKA_BOOTSTRAP_SERVERS`); the executor and trade-api must
  point at it or fills stay `NEW`.
- Registration intentionally returns **no token**: the refresh-token insert
  happens only at `/auth/login`, so there is no partial-state risk during
  signup (a failed signup leaves no user session). The frontend redirects to
  `/login?registered=1` after a successful register.
- Login throttle state is in-memory (single instance). Documented in the auth
  service README; a shared store (e.g. Redis) is recommended before
  multi-instance deployment.