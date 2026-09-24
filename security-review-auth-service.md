# Security review: auth service

## Header

| Field | Value |
|---|---|
| Service | auth service, Sprint 8 |
| Reviewed by | ECRSEM1 team (Sprint 8) |
| Date of review | 2026-09-24 |
| Commit reviewed | Working tree of `sprint8-auth-service/` (not committed) |
| Version of the OWASP Top Ten used | 2021 |

## Categories

| Category | In scope | Finding | Disposition |
|---|---|---|---|
| A01 Broken access control | Yes | `/auth/me` reads identity exclusively from the verified JWT `sub` (user uuid) and never from a client parameter; `auth.guard.ts` validates the Bearer token and resolves the user by that uuid. Registration never accepts roles from the request — `auth.service.ts:register` inserts `ARRAY['CUSTOMER']` server-side. No ADMIN role exists anywhere; the trade API requires a non-empty `roles` claim but never branches on its contents. | Verified by reading `auth.guard.ts`, `auth.controller.ts`, `auth.service.ts`. Live: `/auth/me` without a token returns 401 `AUTH-401`; with a login token returns the profile. No change needed. |
| A02 Cryptographic failures | Yes | Passwords hashed with bcrypt **cost 12** (bcryptjs). JWT HS256 with the signing algorithm **pinned** in the trade API (`JwtService.validateAndParseToken` rejects non-HS256 headers) and issuer pinned to `auth-service`. Secret ≥32 chars read from environment (non-trivial documented default). Refresh tokens are stored as **SHA-256 digests**, never as the token itself. Access token contains no password material. | Implemented. Checked `BCRYPT_ROUNDS = 12` in `auth.service.ts`, algorithm/issuer pinning in `JwtService`, `hashRefreshToken` in `tokens.service.ts`. `npx tsc --noEmit` clean. |
| A03 Injection | Yes | **Finding of none.** Every statement touching the credential store (`auth.users`, `auth.refresh_token`, `trading.account`) is a parameterised pg query with `$1..$N` placeholders (`auth.service.ts`, `db.service.ts`); nothing is assembled by string concatenation. The Spring trade API uses MyBatis `#{}` bindings throughout its mapper XML. | Read every query site in `auth.service.ts` and `db.service.ts`; no concatenation found. No fix needed. |
| A04 Insecure design | Yes | Refresh **rotation** with a `family_id`: presenting an already-rotated token revokes the whole family and returns `AUTH-401`. Login is **throttled** (5 attempts / 15 min → 429 `RATE-429`) and a uniform **150 ms delay** is applied on every failed login so unknown-user and wrong-password are indistinguishable in time and body. Registration returns a token pair by the Sprint 08 API contract; roles are server-assigned. | Implemented and verified live: after a successful `/auth/refresh`, replaying the old token returns 401 and revokes the family; login for a non-existent user returns the byte-identical `AUTH-401` envelope as a wrong password. |
| A05 Security misconfiguration | Yes | Every config value has a default or `.env.example` (PORT, DB_*, JWT_*, TTLs, throttle). `AppModule` sets CORS `origin: true` (reflects any origin) — a finding; the trade API instead pins `Access-Control-Allow-Origin: http://localhost:5000`. Dockerfile runs as non-root `USER node`. `ErrorEnvelopeFilter` rewrites every error to `{errorCode, message}` so no exception name or stack reaches the client (observed live as `SRV-500`, never a stack). A fallback JWT secret exists in the trade API for dev convenience when env vars are absent. | Open CORS on the auth service: **accepted** for dev scope (restricting would break legitimate frontend origins during the demo), documented in the README. Fallback secret: **mitigated** — real runs use `.env` with a strong secret; README instructs it. No stack leaking, verified. |
| A06 Vulnerable and outdated components | Yes | `npm audit --omit=dev` reports 11 vulnerabilities (1 low, 6 moderate, 4 high) all **transitive**: `@nestjs/platform-express`→`multer` (DoS), `qs` (DoS), `js-yaml` (prototype pollution), `lodash` (code injection), `file-type`. None are reachable from this service's routes (no file uploads, no runtime YAML parsing; Swagger serves static assets). `package-lock.json` is pinned. `npm audit fix` applied compatible updates; the remainder needs a breaking jump to Nest 12 (`--force`), which was declined. | **Accepted** for the remaining transitive findings: fix requires a breaking framework upgrade with no reachable exposure in this service's code paths; lockfile pinned; upgrade tracked as an outstanding item. |
| A07 Identification and authentication failures | Yes | Unknown user and wrong password produce identical `AUTH-401` envelopes and comparable timing (150 ms delay). Access tokens live 15 min; **expiry is checked on every protected request** (trade-api `JwtService` and the auth guard). Password min length 12 with upper/lower/digit/special (`class-validator` `Matches`). Login accepts username or email. Failures throttled (429 after 5/15 min). Throttle state is in-memory (single instance). | Implemented and verified live: identical 401s for bad-password vs unknown-user (body bytes compared); weak password → `VAL-422`; Jest suites cover the rules. In-memory throttle **accepted** for single-instance scope and documented for scale-out. |
| A09 Security logging and monitoring failures | Yes | Login failures are now logged **without credentials**; refresh rotation and replay detection are logged with family id; registration success is logged. No record ever contains a password, token value, or token hash. | **Fixed** — added credential-free `Logger` statements to `auth/auth.service.ts` and `auth/login-throttle.service.ts`; verified live: `Login failed for identifier="demo"` with no password in the line. |

## Evidence

| Check | How it was performed | Result |
|---|---|---|
| Unit/contract tests | `npm test` in `sprint8-auth-service` | 4 suites, 19 tests passed |
| TypeScript compile | `npx tsc --noEmit` | Clean |
| Trade API regression | `mvn clean test` in `sprint8` | 74 tests passed |
| Runtime E2E | Live requests to :3000/:8085 (register 201, dup register 409 `AUTH-409`, login 200, wrong-password 401, unknown-user 401, `/auth/me` 200, refresh 200, replay 401, rotated-token replay 401, cross-account 401, order NEW→CANCELLED) | All matched the contract error envelope |
| Error-envelope leak test | Shutdown/restart and forced error paths | Only `{errorCode, message}` returned; no stack/exception names |
| Dependency audit | `npm audit --omit=dev` before/after `npm audit fix` | 11 (1 low, 6 moderate, 4 high), all transitive and not reachable; compatible fixes applied |
| Auth-less access | `GET /auth/me` with no / bad token | 401 `AUTH-401` |

## Outstanding items

| Item | Owner | Target date |
|---|---|---|
| Replace in-memory login-throttle store with a shared store (e.g. Redis) when the auth service is scaled to multiple instances | Team | Before horizontal scale-out |
| Make the refresh-token insert part of the registration transaction (currently outside it; user+account can be created if the token insert fails) | Team | Next sprint |
| Build & verify the provided Docker images on a machine with Docker (auth service + trade API) | Team | At deployment |
| Re-visit `npm audit fix --force` (Nest 12) once the framework bump can be scheduled | Team | Next sprint |