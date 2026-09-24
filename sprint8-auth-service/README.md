# Sprint 08 Auth Service

Standalone authentication service for **Sprint 8**, built with NestJS per the
`auth_api_yaml.txt` / `context.txt` contract. It is the **only** component that
touches passwords. No other service (Trade API, executor, ETL) can see the
user table or password hashes. Consumers validate JWT signature + issuer
locally with no network call.

## Responsibilities

- `POST /auth/register` — create a customer and, **in the same transaction**,
  open a `trading.account` (USD, `100000.00`, status `ACTIVE`, version `1`). Returns an access + refresh token pair.
- `POST /auth/login` — authenticate with username or email + password. Identical body/status for "unknown user" and "wrong password". Throttled and time-uniform.
- `POST /auth/refresh` — rotate the refresh token; a presented token that was already rotated (replay) revokes its whole family.
- `GET /auth/me` — current user profile + trading `accountId`, requires `Authorization: Bearer <accessToken>`.

## Route table

| Method | Path            | Auth  | Success                                                                                 | Errors                                          |
| ------ | --------------- | ----- | --------------------------------------------------------------------------------------- | ----------------------------------------------- |
| POST   | `/auth/register`| none  | `201` `{ accessToken, refreshToken, expiresIn, user: { userId, uuid, firstName, middleName, lastName, username, email, roles, accountId } }` | `409` `AUTH-409`, `422` `VAL-422`              |
| POST   | `/auth/login`   | none  | `200` same body as register                                                              | `401` `AUTH-401`, `429` `RATE-429`, `422` `VAL-422` |
| POST   | `/auth/refresh` | none  | `200` same body as register (rotated pair)                                               | `401` `AUTH-401`, `422` `VAL-422`              |
| GET    | `/auth/me`      | Bearer| `200` `{ userId, uuid, firstName, middleName, lastName, username, email, roles, accountId }` | `401` `AUTH-401`                              |
| GET    | `/docs`         | none  | Swagger UI                                                                               |                                                 |
| GET    | `/docs/json`    | none  | OpenAPI JSON                                                                             |                                                 |

Every error is a JSON envelope `{ "errorCode": string, "message": string }`
with codes `AUTH-401`, `AUTH-409`, `VAL-422`, `RATE-429`, `SRV-500`.

## Run

Requirements: Node 20+ (tested on Node 24), PostgreSQL with the
`sprint8/db/schema.sql` applied (schemas `auth` + `trading`).

```bash
npm install
cp .env.example .env   # adjust DB credentials
npm run build
npm start              # listens on http://localhost:3000
```

Then open http://localhost:3000/docs for Swagger UI and
http://localhost:3000/docs/json for the OpenAPI document.

### Tests

```bash
npm test
```

Jest suites live next to the code (`*.spec.ts`) covering token signing/issuer
validation, login throttling, registration/validation rules and the auth
flows with a mocked database.

### Docker

```bash
docker build -t sprint08-auth-service .
docker run --rm -p 3000:3000 --env-file .env sprint08-auth-service
```

## Configuration

| Variable              | Default                                             | Purpose                                    |
| --------------------- | --------------------------------------------------- | ------------------------------------------ |
| `PORT`                | `3000`                                              | HTTP port                                  |
| `DB_HOST/PORT/USER/PASSWORD/NAME` | `localhost/5432/postgres/n3u3d4!/trading_system_db` | PostgreSQL connection (schemas `auth` + `trading`) |
| `JWT_SECRET`          | `akatsuki_akatsuki_akatsuki_akatsuki_akatsuki`      | HS256 signing key (also used by the Trade API) |
| `JWT_ISSUER`          | `auth-service`                                      | `iss` claim; the Trade API rejects other issuers |
| `JWT_TTL_SECONDS`     | `900` (15 min)                                      | Access-token lifetime                       |
| `REFRESH_TTL_SECONDS` | `604800` (7 days)                                   | Refresh-token lifetime                      |
| `THROTTLE_MAX_ATTEMPTS` / `THROTTLE_WINDOW_MS` | `5` / `900000`                       | Login throttle window                       |

## Security decisions (mapped to `security_template.txt`)

- **Token**: JWT HS256 with `sub` = user `uuid` (stable, never revealed as an
  auto-increment), `accountId` (trading account id), `roles` (always a
  non-empty array, defaults to `CUSTOMER`), `iss` = `auth-service`,
  `exp` = 15 min. No password material is ever embedded.
- **Passwords**: bcrypt cost **12** (`bcryptjs`). Registration requires
  ≥ 12 chars with upper/lower/digit/special via `class-validator`. Plaintext
  and hashes are never logged and never returned.
- **No self-declared roles**: registration inserts `ARRAY['CUSTOMER']`
  server-side; the payload plays no role in role assignment.
- **Refresh rotation + replay protection**: tokens stored as SHA-256 digests.
  A refresh rotates the token (old one revoked, new one in the same `family_id`).
  Presenting an already-rotated token revokes the whole family and returns `AUTH-401`.
- **Login timing**: a fixed 150 ms delay is added before every failed response so
  unknown-user and wrong-password cases are indistinguishable in time and body.
- **Login throttle**: in-memory per-identifier sliding window (default 5 / 15 min)
  returning `429 RATE-429`. (In-memory by design for the scope; note in
  production a shared store such as Redis is recommended.)
- **Validation**: `ValidationPipe` with whitelist + `class-validator`, mapping to
  `422 VAL-422` with a summary message.
- **Errors**: `ErrorEnvelopeFilter` rewrites every response to the contract
  `{ errorCode, message }` envelope — no stack traces or internals leak.