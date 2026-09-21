# Auth Server Implementation Status

**Generated:** 2026-09-21  
**Current File:** [auth-server.js](auth-server.js)  
**Current Setup:** Node.js + JavaScript (not TypeScript)

---

## ✅ COMPLETED FEATURES

### Core Infrastructure
- [x] **Environment Variables** - JWT_SECRET read from .env
- [x] **Port Configuration** - Listening on port 3000 (configurable via PORT env var)
- [x] **CORS Handling** - CORS headers applied with origin validation
- [x] **Health Check** - GET /health endpoint
- [x] **Request Body Parsing** - JSON parsing with size limit (1MB)
- [x] **Error Handling** - Try-catch wrapper around request handlers

### JWT Token Management
- [x] **Token Generation** - POST /api/auth creates JWT tokens
- [x] **Token Verification** - GET /api/auth/verify validates tokens
- [x] **Algorithm** - HS256 implemented correctly
- [x] **Issuer** - Set to 'auth-service'
- [x] **Claims Structure** - Contains `sub`, `accountId`, `roles`
- [x] **Expiry** - Configurable via TTL_SECONDS (default 900s = 15 min)
- [x] **Token Type** - Bearer token returned
- [x] **Input Normalization** - userId, accountId, roles normalized with defaults
- [x] **Token Payload Validation** - Claims verified on token check

### Request/Response Handling
- [x] **JSON Response Format** - Consistent JSON error/success responses
- [x] **Status Codes** - 200 (success), 401 (unauthorized), 404 (not found), 400 (bad request)
- [x] **Bearer Token Extraction** - Authorization header parsing
- [x] **Cache Headers** - Cache-Control: no-store set on auth responses

---

## ❌ MISSING FEATURES

### 1. **Language/Framework**
- [ ] **TypeScript Migration** - Currently plain JavaScript, needs TypeScript setup
  - Need: tsconfig.json, .ts source files, build process
  - Impact: Type safety, better IDE support

### 2. **Authentication Routes**
- [ ] **POST /api/auth/register** - User registration endpoint
  - Should NOT issue tokens on registration (prevent auth bypass)
  - Should NOT create trading account
  - Needs input validation (class-validator)
  - Should return VAL-422 on field validation failure
  
- [ ] **POST /api/auth/login** - User login with username/password
  - Needs username/password from request body
  - Should verify password against stored hash
  - Should issue access token AND refresh token
  - Should apply login throttle (same response for unknown user & wrong password)
  - Should verify against dummy hash for unknown users (timing attack defense)
  - Documented cooldown and attempt count needed

- [ ] **POST /api/auth/refresh** - Refresh token endpoint
  - Accept refresh token
  - Issue NEW access token AND NEW refresh token
  - Implement token revocation (or document decision)
  - Store hash of refresh token, not the token itself

- [ ] **GET /api/auth/profile** - Protected user profile route
  - Requires valid access token
  - Guard to verify token
  - Return authenticated user info

### 3. **Password & Credential Management**
- [ ] **Password Hashing** - argon2id or bcrypt (cost ≥ 12)
  - Need: bcrypt or argon2 npm package
  - Implementation in credential store
  - Unit tests: correct password verifies, incorrect password fails, no MD5/SHA used

- [ ] **Credential Store** - Database/persistence layer
  - User table with username, password hash, email
  - Refresh token table with token hash
  - Repository pattern for queries

- [ ] **Database Connection**
  - SQL schema for users and refresh tokens
  - Connection pooling
  - Environment variable for DB connection string

### 4. **Security Features**
- [ ] **Login Throttle** - Rate limiting failed login attempts
  - Configurable attempt count and cooldown
  - Document in sprint README
  - Same response time for unknown user vs wrong password
  
- [ ] **Refresh Token Revocation** - Per requirement
  - Option 1: Implement revocation (check revoked flag in DB)
  - Option 2: Document decision and residual risk in security review
  
- [ ] **Token Refresh Rotation** - Issued on every refresh
  - Old token should be handled per revocation policy
  - New token works immediately

- [ ] **Password Logging Prevention**
  - Single logger with key-name redaction at any depth
  - Never log full request body
  - Handle error serialization safely

### 5. **Input Validation & Error Responses**
- [ ] **class-validator Integration** - Validation decorators
  - Need: class-validator, class-transformer npm packages
  - DTOs for register, login, refresh payloads
  
- [ ] **Validation Error Response** - VAL-422 status code
  - For field validation failures
  - List field-specific errors

- [ ] **Consistent Error Responses**
  - Unknown user, wrong password, expired token, wrong signature, malformed header
  - All return AUTH-401 with same status/body
  - Comparable timing (dummy hash verification for unknown user)

### 6. **OpenAPI Documentation**
- [ ] **OpenAPI Endpoint** - GET /api/openapi.json or /openapi
  - Return OpenAPI 3.0 document describing all routes
  - Generated from code (not hand-maintained YAML)
  
- [ ] **OpenAPI UI** - GET /docs or /swagger-ui.html
  - Human-readable interactive documentation
  - Paths documented in sprint README

### 7. **Testing**
- [ ] **Jest Test Suite** - Comprehensive unit tests
  - Guard tests: valid token accepted, expired token refused, wrong signature refused, malformed header refused
  - Password tests: correct password verifies, incorrect password fails
  - Throttle tests: repeated attempts limited
  - Token refresh tests: new token issued and works, revocation behavior verified

### 8. **Documentation & Configuration**
- [ ] **Sprint README** - Update with:
  - OpenAPI paths (JSON document and UI)
  - Login throttle cooldown and attempt count
  - Password hash algorithm and cost factor justification
  - JWT_SECRET rotation decision after real issuer
  - Refresh token revocation decision (built vs documented)
  - Security review findings
  
- [ ] **Security Review** - OWASP categories:
  - Broken Authentication - password hashing, throttle, token validation
  - Broken Access Control - role-based access, protected routes
  - Token Leakage - secure storage, no logging, HTTPS in production
  - Replay Attacks - expiry, signature verification, refresh rotation
  - Weak Secrets - strong JWT_SECRET requirement, cost factor for hashing
  - Refresh token revocation decision and residual risk
  
- [ ] **Environment File** - .env.example with:
  - JWT_SECRET (required, no default)
  - PORT (default 3000)
  - JWT_TTL_SECONDS (default 900)
  - DB_CONNECTION_STRING or similar
  - CORS_ALLOWED_ORIGINS
  - Any password hash cost parameters
  - LOG_LEVEL or similar

### 9. **Docker & Deployment**
- [ ] **Dockerfile Review** - Verify multi-stage build
  - Build stage with npm dependencies
  - Runtime stage with minimal footprint
  - Non-root user (security best practice)
  
- [ ] **docker-compose.yml Integration** - Add to team orchestration
  - Port 3000 mapping
  - Shared JWT_SECRET from root .env
  - Database service (if using SQL)
  - Network configuration for Trade API communication

### 10. **Integration with Trade REST API**
- [ ] **JWT_SECRET Sharing** - Same secret as Trade API verifies with
  - Passed through orchestration environment
  
- [ ] **Issuer Configuration** - If Trade API pins issuer
  - Configure to accept 'auth-service' issuer
  - Or accept issuer through environment variable
  
- [ ] **Integration Test** - End-to-end verification
  - Get token from Auth Service
  - Call protected Trade API route with token
  - Verify acceptance with valid token
  - Verify refusal with no token
  - Verify refusal with token signed by wrong key

---

## IMMEDIATE NEXT STEPS (Priority Order)

1. **Create Registration Endpoint** (`POST /api/auth/register`)
   - Add username/email/password input validation
   - Hash password with bcrypt (cost 12)
   - Store user in database
   - Return error response (no token)

2. **Create Login Endpoint** (`POST /api/auth/login`)
   - Look up user by username
   - Verify password against stored hash
   - Issue access token + refresh token
   - Implement login throttle

3. **Add Database Layer**
   - User table schema
   - Refresh token table
   - Connection pooling
   - Repository pattern

4. **Implement Refresh Token Route** (`POST /api/auth/refresh`)
   - Accept refresh token
   - Verify token hash against database
   - Issue new tokens
   - Implement revocation strategy

5. **Create Protected Profile Route** (`GET /api/auth/profile`)
   - Implement authentication guard
   - Extract user from token
   - Return user data

6. **Add Input Validation**
   - Install class-validator, class-transformer
   - Create DTOs for each endpoint
   - Return VAL-422 on validation failure

7. **Generate OpenAPI Documentation**
   - Add endpoint to serve OpenAPI 3.0 JSON
   - Add Swagger UI endpoint

8. **Write Unit Tests**
   - Jest configuration
   - Guard tests
   - Password tests
   - Throttle tests
   - Token tests

9. **Security Review & Documentation**
   - Complete OWASP security review
   - Update sprint README
   - Document all decisions

10. **Docker & Integration Testing**
    - Verify Dockerfile
    - Add to docker-compose
    - Test with Trade API

---

## FILES TO CREATE/MODIFY

```
sprint6/auth_server/
├── auth-server.js                 (Current - MAJOR REWRITE for TypeScript/auth routes)
├── package.json                   (Add: bcrypt, class-validator, class-transformer, jest, @types/*)
├── tsconfig.json                  (New - TypeScript configuration)
├── jest.config.js                 (New - Jest testing)
├── src/                           (New directory for TypeScript sources)
│   ├── server.ts                  (Refactored main server)
│   ├── routes/
│   │   ├── auth.ts                (Register, login, refresh routes)
│   │   └── profile.ts             (Protected profile route)
│   ├── guards/
│   │   └── auth.guard.ts          (Token verification guard)
│   ├── services/
│   │   ├── auth.service.ts        (Token generation, password hash)
│   │   ├── user.service.ts        (User lookup, storage)
│   │   └── throttle.service.ts    (Login rate limiting)
│   ├── repositories/
│   │   ├── user.repository.ts
│   │   └── refresh-token.repository.ts
│   ├── dto/
│   │   ├── register.dto.ts
│   │   ├── login.dto.ts
│   │   └── refresh.dto.ts
│   ├── models/
│   │   └── user.model.ts
│   └── config/
│       └── database.ts
├── tests/                         (New - Jest test files)
│   ├── auth.guard.test.ts
│   ├── auth.service.test.ts
│   └── password.test.ts
├── .env.example                   (Update with all env vars)
├── Dockerfile                     (Verify/update)
├── README.md                      (New - with security review, decisions, paths)
└── security-review.md             (New - OWASP security review)
```

---

## ESTIMATED EFFORT

- **Phase 1 (Core Auth):** 2-3 days - Register, Login, Password hashing
- **Phase 2 (Advanced):** 2 days - Refresh, Throttle, Revocation
- **Phase 3 (Polish):** 1-2 days - OpenAPI, Tests, Documentation, Integration
- **Total:** ~1 week for complete implementation

---

## NOTES

- Current implementation is a solid foundation for token verification
- Refactor to TypeScript for type safety and consistency with team practices
- Password hashing is the highest priority security feature
- Login throttle prevents brute force attacks - implement early
- OpenAPI generation can use decorators (e.g., @ApiOperation, @ApiResponse from @nestjs/swagger if migrating to NestJS, or swagger-jsdoc if staying with Express)
