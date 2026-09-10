# Sprint 6 Quick Implementation Guide

## 🎯 What's Already Implemented

Everything you need is in place:

### Core Components ✅
1. **6 REST Endpoints** → Match OpenAPI spec exactly
2. **7 Error Codes** → Mapped in GlobalExceptionHandler with correct HTTP status
3. **JWT Authentication** → Protects all /api/v1/** routes
4. **MyBatis Mappers** → All queries parameterized with #{}
5. **@Transactional** → On placeOrder and cancelOrder
6. **Optimistic Locking** → On account updates (version-based)
7. **Multi-stage Dockerfile** → Production-ready build
8. **Error Envelope** → {"errorCode": "...", "message": "..."} format

---

## 🚀 To Run & Test

### 1. Build
```bash
cd sprint6
mvn clean verify
```

### 2. Run with Docker Compose
```bash
cp .env.example .env
# Edit .env: set DB_PASSWORD and JWT_SECRET (min 32 chars)
docker compose up --build
```

### 3. Test All 6 Endpoints

#### Health (no auth required)
```bash
curl http://localhost:8080/health
```

#### Generate JWT Token (NEW ENDPOINT)
```bash
# Login to get JWT token
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1}'

# Response:
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "accountId": 1,
  "issuedAt": "2026-09-10T11:35:00Z",
  "expiresAt": "2026-09-10T12:35:00Z"
}

# Copy the token value for testing other endpoints
```

#### Test Endpoints
```bash
# Create order (requires JWT)
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer <JWT_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": 1,
    "symbol": "AAPL",
    "side": "BUY",
    "quantity": 100,
    "price": 150.50,
    "idempotencyKey": "uuid-here"
  }'

# Get account
curl -X GET http://localhost:8080/api/v1/accounts/1 \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Get balance
curl -X GET http://localhost:8080/api/v1/accounts/1/balance \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Get positions
curl -X GET http://localhost:8080/api/v1/accounts/1/positions \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Get orders
curl -X GET http://localhost:8080/api/v1/accounts/1/orders \
  -H "Authorization: Bearer <JWT_TOKEN>"

# Cancel order
curl -X DELETE http://localhost:8080/api/v1/orders/<order-uuid> \
  -H "Authorization: Bearer <JWT_TOKEN>"
```

---

## 🔍 Test All Error Codes

### Missing JWT → AUTH-401
```bash
curl http://localhost:8080/api/v1/accounts/1
# Response: {"errorCode":"AUTH-401","message":"Unauthorised"}
```

### Invalid JWT → AUTH-401
```bash
curl -H "Authorization: Bearer invalid" http://localhost:8080/api/v1/accounts/1
```

### Wrong Account ID in Token → ACC-403
```bash
# Token has accountId: 2, but request is for account 1
curl -H "Authorization: Bearer <token-for-account-2>" \
  http://localhost:8080/api/v1/accounts/1
```

### Account Not Found → ACC-404
```bash
curl -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/v1/accounts/9999
```

### Instrument Not Found → INS-404
```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer <token>" \
  -d '{"accountId":1, "symbol":"NONEXIST", ...}'
```

### Insufficient Funds (BUY) → ORD-400
```bash
# Place order costing more than account balance
```

### Insufficient Holdings (SELL) → ORD-409
```bash
# Try to sell more shares than held
```

### Duplicate Idempotency Key → ORD-409
```bash
# Submit same order twice with same idempotencyKey
```

### Invalid Input → VAL-422
```bash
# quantity: 0, price: 0, or missing required fields
```

---

## 📋 Architecture Summary

```
HTTP Request
     ↓
JwtAuthenticationFilter (validates JWT, extracts accountId)
     ↓
AccountController / OrderController (HTTP only, no business logic)
     ↓
TradeService (orchestrates, @Transactional boundary)
     ↓
Mappers (MyBatis, parameterized SQL only)
     ↓
PostgreSQL (orders, accounts, instruments, holdings tables)
     ↓
If exception: GlobalExceptionHandler → error envelope
```

---

## 🔐 Security Checklist

- [x] JWT verified on signature, expiry, algorithm
- [x] accountId claim extracted and validated
- [x] Token mismatch returns ACC-403 (not AUTH-401)
- [x] All database queries parameterized (#{})
- [x] No SQL injection possible
- [x] No stack traces in responses
- [x] No internal identifiers leaked

---

## 💾 Database Assumptions

The service expects these tables (created by Sprint 3 migrations):

| Table | Key Columns | Notes |
|-------|------------|-------|
| accounts | id (PK), account_id, cash_balance, status, version | version for optimistic locking |
| orders | order_id (PK), idempotency_key (UNIQUE), status | UUID-based idempotency key |
| instruments | instrument_id (PK), symbol (UNIQUE), status | DELISTED status blocks trading |
| holdings | account_id + instrument_id (PK), quantity | Updated atomically with orders |

---

## ⚠️ Important Notes

1. **Environment Variables** (never in properties files):
   - `DB_HOST` (default: postgres)
   - `DB_PORT` (default: 5432)
   - `DB_NAME` (default: trading_db)
   - `DB_USER` (default: postgres)
   - `DB_PASSWORD` (must set in .env)
   - `JWT_SECRET` (min 32 chars, must set in .env)

2. **Concurrent Orders**: If two orders arrive simultaneously against one account:
   - Both read account balance
   - First order updates account (version increments)
   - Second order fails UPDATE (version mismatch) → ORD-409
   - No race condition, no lost updates

3. **Idempotency**: Retrying with same idempotencyKey returns ORD-409
   - Not a way to poll for status
   - Poll GET /api/v1/accounts/{id}/orders instead

4. **Order Status in Sprint 6**:
   - NEW (never seen, filled synchronously)
   - FILLED (buy/sell completed)
   - REJECTED (failed business rules)
   - CANCELLED (user-initiated)

---

## 📖 Reference: 8 Order Placement Rules

**Rules enforce in order. First failure wins.**

```
1. Account exists? → ACC-404
2. Account ACTIVE? → ACC-403  
3. Instrument tradable? → INS-404
4. Qty > 0? → VAL-422
5. Price > 0? → VAL-422
6. BUY: cash ≥ qty×price? → ORD-400
7. SELL: holdings ≥ qty? → ORD-409
8. Idempotency key unique? → ORD-409
```

All enforced inside `TradeService.placeOrder()` before `INSERT`.

---

## ✅ Readiness Checklist

Before submission, verify:

- [ ] `mvn clean verify` succeeds
- [ ] Docker image builds
- [ ] Service starts and health check passes
- [ ] All 6 endpoints respond
- [ ] All 7 error codes appear correctly
- [ ] JWT required on /api/v1/**
- [ ] Access control works (wrong account → ACC-403)
- [ ] Concurrent orders tested (no lost updates)
- [ ] Idempotency key duplicate tested (→ ORD-409)
- [ ] Dockerfile runs non-root
- [ ] No stack traces in error responses
- [ ] All queries use #{} parameterization
