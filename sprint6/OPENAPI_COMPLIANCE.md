# Sprint 6 OpenAPI Spec Compliance Report

## 📋 SPECIFICATION REQUIREMENTS MET

### ✅ 1. Six Endpoints (100% Complete)

| Endpoint | Method | Status | Notes |
|----------|--------|--------|-------|
| `/api/v1/orders` | POST | ✅ | Place order, validates 8 rules |
| `/api/v1/orders/{id}` | DELETE | ✅ | Cancel order with guarded transition |
| `/api/v1/accounts/{id}` | GET | ✅ | Account details |
| `/api/v1/accounts/{id}/balance` | GET | ✅ | Cash balance only |
| `/api/v1/accounts/{id}/positions` | GET | ✅ | Holdings list |
| `/api/v1/accounts/{id}/orders` | GET | ✅ | Order history with filters |

### ✅ 2. Error Catalog (7 Codes)

| Code | HTTP | Meaning | Handler | Status |
|------|------|---------|---------|--------|
| ACC-404 | 404 | Account not found | GlobalExceptionHandler | ✅ |
| ACC-403 | 403 | Account not active / token mismatch | GlobalExceptionHandler | ✅ |
| INS-404 | 404 | Instrument not found / delisted | GlobalExceptionHandler | ✅ |
| ORD-400 | 400 | Insufficient funds (BUY) | GlobalExceptionHandler | ✅ |
| ORD-409 | 409 | Insufficient holdings / duplicate / not cancellable | GlobalExceptionHandler | ✅ |
| VAL-422 | 422 | Invalid input (validation) | GlobalExceptionHandler | ✅ |
| AUTH-401 | 401 | Missing / invalid / expired token | JwtAuthenticationFilter | ✅ |

### ✅ 3. Error Response Envelope

**Format** (Correct):
```json
{
  "errorCode": "ORD-409",
  "message": "Insufficient holdings"
}
```

**Implementation**:
- ErrorResponseDto is a record with exactly 2 fields
- GlobalExceptionHandler returns consistent format
- No stack traces, no internal details

### ✅ 4. Business Rules (8 Rules in Order)

Order placement validates rules 1-8 in strict sequence, first failure wins:

| # | Rule | Validation | Error | Status | Implemented |
|---|------|-----------|-------|--------|-------------|
| 1 | Account exists | accountMapper.findById(accountId) | ACC-404 | 404 | ✅ |
| 2 | Account ACTIVE | accRecord.getStatus() == ACTIVE | ACC-403 | 403 | ✅ |
| 3 | Instrument exists & tradable | !instrument.delisted | INS-404 | 404 | ✅ |
| 4 | Qty > 0 | quantity > 0 | VAL-422 | 422 | ✅ |
| 5 | Price > 0 | price > 0 | VAL-422 | 422 | ✅ |
| 6 | BUY: cash ≥ qty×price | balance >= (qty * price) | ORD-400 | 400 | ✅ |
| 7 | SELL: holdings ≥ qty | holdings >= qty | ORD-409 | 409 | ✅ |
| 8 | Idempotency key unique | Unique constraint + catch | ORD-409 | 409 | ✅ |

### ✅ 5. Authentication & Authorization

| Requirement | Implementation | Status |
|------------|-----------------|--------|
| JWT on /api/v1/** | JwtAuthenticationFilter | ✅ |
| Verify signature | JwtService.validateAndParseToken() | ✅ |
| Check expiry | JwtService.validateAndParseToken() | ✅ |
| Extract accountId | JwtService.extractAccountId(claims) | ✅ |
| Token accountId matches accessed account | AccountController.checkAccountAccess() | ✅ |
| Missing token → AUTH-401 | sendUnauthorized() | ✅ |
| Invalid token → AUTH-401 | sendUnauthorized() on exception | ✅ |
| Mismatch accountId → ACC-403 | throw AccountNotActiveException() | ✅ |

### ✅ 6. Transactions & Concurrency

| Requirement | Implementation | Status |
|------------|-----------------|--------|
| @Transactional on placeOrder | ✅ Present on line 55 | ✅ |
| Optimistic locking on account | version in WHERE, version++ | ✅ |
| Guarded order cancellation | status = NEW in WHERE | ✅ |
| Check row count from UPDATE | rows == 0 throws exception | ✅ |

**Optimistic Lock Pattern** (AccountMapper):
```sql
UPDATE account
SET cash_balance = #{cashBalance},
    version = version + 1
WHERE account_id = #{id}
  AND version = #{version}
```
If rows affected = 0, account was modified by another transaction → ORD-409

### ✅ 7. Persistence Layer (MyBatis)

| Requirement | Status |
|------------|--------|
| Parameterized statements (#{}) | ✅ All mappers use #{} |
| NO interpolation (${}) | ✅ Zero usages found |
| No SQL in controllers | ✅ Controllers are HTTP-only |
| No HTTP types in domain | ✅ Domain package isolated |
| Result mapping aliases | ✅ map_underscore_to_camel_case |

### ✅ 8. Layering (Strict Boundaries)

| Layer | Language | Allowed | NOT Allowed | Status |
|-------|----------|---------|------------|--------|
| **Controller** | HTTP, DTOs, Status Codes | Request objects, validation | SQL, transactions, business logic | ✅ Clean |
| **Service** | Domain package, mappers | Transactions, orchestration | Servlet types, status codes, SQL | ✅ Clean |
| **Mapper** | SQL (MyBatis) | Parameterized queries | Business decisions, HTTP types | ✅ Clean |
| **Domain** | Pure Java logic | Business rules | Servlet types, Spring, MyBatis | ✅ Isolated |

### ✅ 9. Container & Deployment

| Requirement | Implementation | Status |
|------------|-----------------|--------|
| Multi-stage Dockerfile | Builder stage → Runtime stage | ✅ |
| Non-root user | adduser tradeapi | ✅ |
| Health check | curl http://localhost:8080/health | ✅ |
| Runtime image | eclipse-temurin:21-jre-alpine | ✅ |
| Exposed port | EXPOSE 8080 | ✅ |
| Environment variables | DB_*, JWT_SECRET | ✅ |

---

## 📊 VERIFICATION CHECKLIST

When demonstrating to instructor, verify:

- [ ] All 6 endpoints respond with correct shape
- [ ] Each error code appears in response envelope
- [ ] Missing JWT token → `AUTH-401` with empty response body
- [ ] Tampered JWT → `AUTH-401`
- [ ] Token from different account → `ACC-403`
- [ ] Account not found → `ACC-404`
- [ ] Instrument not found → `INS-404`
- [ ] Insufficient funds (BUY) → `ORD-400`
- [ ] Insufficient holdings (SELL) → `ORD-409`
- [ ] Duplicate idempotency key → `ORD-409`
- [ ] Invalid input → `VAL-422`
- [ ] Order not cancellable → `ORD-409`
- [ ] Concurrent orders: cash reconciles against order history
- [ ] Dockerfile builds: `docker build -t trade-api .`
- [ ] Service runs: `docker run -p 8080:8080 trade-api`
- [ ] Service reaches database: connection successful
- [ ] Health check passes: `curl http://localhost:8080/health`

---

## 🔍 KEY DESIGN DECISIONS (Correct per Spec)

### Order ID Strategy
- **External identifier**: idempotencyKey (UUID, displayed as "ORD-{uuid}")
- **Internal identifier**: numeric order_id (auto-generated for DB relationships)
- **Why**: Idempotency key is already a UUID per spec, is guaranteed unique, and serves as the external order identifier

### Access Control Pattern
```java
// In AccountController for every endpoint:
Long authAccountId = (Long) request.getAttribute(AUTHENTICATED_ACCOUNT_ID_ATTR);
if (authAccountId != null && !authAccountId.equals(targetAccountId)) {
    throw new AccountNotActiveException();  // → ACC-403
}
```

### Idempotency Implementation
- Enforced by `UNIQUE(idempotency_key)` constraint on orders table
- First request succeeds, second request fails at INSERT
- Caught as exception, converted to `ORD-409`
- No race condition (constraint is checked at database level)

### Concurrency Control
- Optimistic locking prevents lost updates on concurrent orders
- Account write includes `version` in WHERE clause
- If version has changed since read, UPDATE returns 0 rows
- Service throws `ORD-409` ("Order rejected due to concurrent modification")

---

## 📚 Files Implementing Spec

| Component | Files | Completeness |
|-----------|-------|--------------|
| Controllers | AccountController, OrderController, HealthController | 100% |
| Service | TradeService | 100% |
| Mappers | AccountMapper, OrderMapper, InstrumentMapper, HoldingMapper | 100% |
| Exception Handling | GlobalExceptionHandler | 100% |
| Security | JwtAuthenticationFilter, JwtService | 100% |
| DTOs | ErrorResponseDto, OrderResponseDto, AccountResponseDto, etc. | 100% |
| Configuration | application.yml, pom.xml | 100% |
| Deployment | Dockerfile, docker-compose.yml | 100% |

---

## ✅ READY FOR REVIEW

This implementation satisfies:
- ✅ Contract-compliant endpoints, responses, error catalogue
- ✅ Controller, service, domain, mapper boundaries
- ✅ MyBatis persistence, transactions, concurrency control
- ✅ Validation, authentication, authorisation
- ✅ Reproducible multi-stage container build
- ✅ Business rules enforced in correct order
- ✅ No stack traces or internal details in responses
- ✅ Layering violations prevented by design
