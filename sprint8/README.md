# Sprint 8: Trade REST API

Spring Boot / MyBatis service (port **8085**) exposing the trading endpoints
defined in `contracts/trade-api.yaml`, extended for Sprint 8 to trust JWT
tokens issued by the separate **Sprint 08 Auth Service**
(`sprint8-auth-service/`).

- Routes under `/api/v1/**` require `Authorization: Bearer <accessToken>`.
- The JWT is validated with a pinned HS256 algorithm and a pinned issuer
  (`auth-service`); a user's trading `accountId` comes from the token's
  `accountId` claim, and cross-account access returns `401`.
- Account responses carry a formatted id (`ACC-000001`) and `holderName`
  resolved by joining `trading.account` with `auth.users`.
- `GET /health` reports liveness.

## Run

```bash
cp .env.example .env     # DB credentials + JWT secret/issuer (match the auth service)
mvn package -DskipTests
java -jar target/sprint-08-trade-api-1.0-SNAPSHOT.jar
```

See `SPRINT8_RUN.md` for the full Sprint 8 run book, database bootstrap,
seed credentials and the verified end-to-end transcript.