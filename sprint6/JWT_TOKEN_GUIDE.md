# JWT Token Generation Guide

## Overview

Sprint 6 now includes **JWT token generation endpoint** for easy testing and integration.

---

## Endpoint: POST /auth/login

**No authentication required** — anyone can request a token.

### Request
```json
POST /auth/login
Content-Type: application/json

{
  "accountId": 1
}
```

### Response (200 OK)
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "accountId": 1,
  "issuedAt": "2026-09-10T11:35:00Z",
  "expiresAt": "2026-09-10T12:35:00Z"
}
```

### Error Cases

#### Missing accountId → VAL-422
```json
POST /auth/login
Content-Type: application/json

{}

Response: {"errorCode": "VAL-422", "message": "..."}
```

#### Invalid accountId → VAL-422
```json
{
  "accountId": 0
}

Response: {"errorCode": "VAL-422", "message": "Account ID must be at least 1"}
```

---

## Using the Token

### Bearer Token Format
```bash
Authorization: Bearer <token_value>
```

### Example: Get Account Details
```bash
curl -X GET http://localhost:8080/api/v1/accounts/1 \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json"
```

### Token Validation Rules
1. ✅ Token must start with "Bearer " (7 chars) in header
2. ✅ Signature must match JWT_SECRET (HS256)
3. ✅ Algorithm must be HS256 (not HS512, RS256, etc.)
4. ✅ Token must not be expired
5. ✅ Token must have accountId claim

### Token Rejection (AUTH-401)
```bash
# Missing token
curl http://localhost:8080/api/v1/accounts/1
Response: {"errorCode": "AUTH-401", "message": "Unauthorised"}

# Invalid format
curl -H "Authorization: invalid" http://localhost:8080/api/v1/accounts/1
Response: {"errorCode": "AUTH-401", "message": "Unauthorised"}

# Expired or tampered
curl -H "Authorization: Bearer expired.token.here" http://localhost:8080/api/v1/accounts/1
Response: {"errorCode": "AUTH-401", "message": "Unauthorised"}
```

---

## Account Access Control (ACC-403)

Even with a valid token, the accountId in the token **must match** the target account:

```bash
# Token has accountId: 1, requesting account 2 → ACC-403
curl -H "Authorization: Bearer <token_for_account_1>" \
  http://localhost:8080/api/v1/accounts/2

Response: {"errorCode": "ACC-403", "message": "Account is not active"}

# Same for order placement
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer <token_for_account_1>" \
  -H "Content-Type: application/json" \
  -d '{"accountId": 2, ...}'

Response: {"errorCode": "ACC-403", "message": "Account is not active"}
```

---

## Configuration

### JWT Secret (REQUIRED)
Set in environment or `.env` file:
```bash
JWT_SECRET=your-secret-key-minimum-32-characters-long
```

### Token Expiration (Optional)
Default: 3600000 ms (1 hour)

Override in environment:
```bash
JWT_EXPIRATION_MS=7200000  # 2 hours
```

### application.yml
```yaml
jwt:
  secret: ${JWT_SECRET:}
  expiration:
    ms: ${JWT_EXPIRATION_MS:3600000}
```

---

## Testing Workflow

### 1. Generate Token
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1}'

# Save token from response
```

### 2. Use Token in Tests
```bash
# Store in variable
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"accountId": 1}' | jq -r '.token')

# Use in subsequent requests
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/accounts/1
```

### 3. Test All Endpoints
```bash
export TOKEN=<your-token>

# Place order
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "accountId": 1,
    "symbol": "AAPL",
    "side": "BUY",
    "quantity": 100,
    "price": 150.50,
    "idempotencyKey": "order-1"
  }'

# Get balance
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/accounts/1/balance

# Get positions
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/accounts/1/positions

# Get orders
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/accounts/1/orders

# Get account
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/accounts/1
```

---

## Security Notes

⚠️ **For Testing Only**
- The /auth/login endpoint requires only accountId
- It does NOT verify account existence or credentials
- **In production (Sprint 8+)**, auth will be handled by Auth Service with password/2FA

✅ **What JWT Protects**
- All /api/v1/** endpoints require valid token
- /health, /auth/login bypass authentication
- Token signature verified with HS256
- Expiration enforced
- Algorithm strictly checked

---

## Token Payload Structure

```json
{
  "accountId": 1,
  "iat": 1694332500,
  "exp": 1694336100,
  "alg": "HS256"
}
```

- `accountId`: Account identifier (used for access control)
- `iat`: Issued at timestamp
- `exp`: Expiration timestamp (3600 seconds = 1 hour)
- `alg`: Algorithm (HS256 only)

