# Sprint 7 — Complete Event Backbone & Trade Execution System Guide

## Overview & System Architecture

Sprint 7 decouples order placement from trade execution using Apache Kafka, PostgreSQL, Spring Boot, and Python ETL.

```
                         ┌─────────────────────────────────────────────────────────┐
                         │                     TRADING SYSTEM                      │
                         │                                                         │
[Bruno / Client] ───────►│  trade-api (:3000)                                       │
                         │    │ (Save NEW order to DB)                             │
                         │    └─────────► Kafka Topic: "orders" (3 partitions)     │
                         │                     │                                   │
                         │                     ▼                                   │
                         │  trade-executor (:8083)                                 │
                         │    │ 1. Read Order & Check Instrument Status          │
                         │    │ 2. Fetch Quote (GET /quotes/{symbol})             │
                         │    │ 3. Evaluate Limit Price & Funds                   │
                         │    │ 4. Settle DB (orders, cash, holdings)             │
                         │    │                                                    │
                         │    ├───► Kafka Topic: "trade-events" (3 partitions)     │
                         │    │                                                    │
                         │    └───► MarketDataPoller (GET /quotes?symbols=...)     │
                         │            └───► Kafka Topic: "market-data" (6 parts)   │
                         │                     (1 message / symbol)                │
                         │                                                         │
                         │  python-etl                                             │
                         │    └── Consumes "trade-events"                          │
                         │    └── Writes snapshots to DB table "performance"       │
                         │                                                         │
                         │  PostgreSQL (:5432)                                     │
                         │    └── Shared Database ("trading_db")                   │
                         └─────────────────────────────────────────────────────────┘
```

---

## Resolved Key Issues

1. **Kafka Consumer Partition Assignment:**
   - `KafkaConfig` sets `setConcurrency(3)` on `ConcurrentKafkaListenerContainerFactory` so all 3 partitions of the `orders` topic are consumed in parallel.
2. **Payload Compatibility:**
   - `OrderEventConsumer` safely parses both `"price"` and `"limitPrice"`, as well as `"symbol"` and `"ticker"`.
3. **Fauxnance API Integration:**
   - **MarketDataPoller**: Uses the batch API (`GET /quotes?symbols=AAPL,TSLA...`) fetching up to 25 symbols per request (1 quota unit) and publishes individual events per symbol to `market-data` (keyed by `symbol`).
   - **Trade Executor**: Uses `GET /quotes/{symbol}` during order evaluation (with fallback to `QuoteCache`).
4. **Database Settlement:**
   - Atomic `@Transactional` settlement: updates order (`status='FILLED'`, `executed_price`, `executed_on`), updates cash balance with optimistic locking (`version = version + 1`), and upserts holdings.
5. **Limit Price Logic:**
   - BUY: fills if `ask <= limitPrice`.
   - SELL: fills if `bid >= limitPrice`.
   - Otherwise rejects with `"PRICE_NOT_MET"`.

---

## How to Run All Containers on Linux VM

### Step 1: Clone & Switch Branch
```bash
git fetch origin
git checkout feature/pranav
git pull origin feature/pranav
```

### Step 2: Create Environment Configuration `.env`
Create a `.env` file at the root of the repository:
```bash
cat << 'EOF' > .env
DB_NAME=trading_db
DB_USER=postgres
DB_PASSWORD=postgres
JWT_SECRET=super_secret_jwt_key_at_least_32_characters_long
FAUXNANCE_API_KEY=your_fauxnance_api_key_here
FAUXNANCE_BASE_URL=https://y4t9nq2bqf.execute-api.eu-west-2.amazonaws.com/v1
POLL_INTERVAL_SECONDS=60
EOF
```

### Step 3: Start Infrastructure (Postgres & Kafka)
```bash
docker-compose up -d postgres kafka
```
Wait 15–20 seconds for Postgres and Kafka to become healthy. Check status:
```bash
docker ps
```

### Step 4: Create Kafka Topics
```bash
chmod +x infra/scripts/create-topics.sh
./infra/scripts/create-topics.sh
```
Verify topics created:
```bash
docker exec trading-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```
*Expected topics:* `orders`, `orders.DLT`, `trade-events`, `trade-events.DLT`, `market-data`, `market-data.DLT`.

### Step 5: Build & Run All Containers
```bash
docker-compose up -d --build
```
This builds and launches all 5 containers:
- `trading-postgres` (port 5432)
- `trading-kafka` (ports 9092, 29092)
- `trading-trade-api` (port 3000 -> 8080)
- `trading-executor` (port 8083)
- `trading-etl` (Python background ETL)

---

## How to Test Running Containers using Bruno / Postman / curl

Base URL: `http://<YOUR_VM_IP>:3000` (or `http://localhost:3000`)

### 1. Register a Client/User
* **Method:** `POST`
* **URL:** `http://<VM_IP>:3000/api/auth/register`
* **Headers:** `Content-Type: application/json`
* **Body:**
```json
{
  "username": "trader1",
  "password": "Password123!",
  "name": "Trader One"
}
```

---

### 2. Login to obtain JWT Token
* **Method:** `POST`
* **URL:** `http://<VM_IP>:3000/api/auth/login`
* **Headers:** `Content-Type: application/json`
* **Body:**
```json
{
  "username": "trader1",
  "password": "Password123!"
}
```
* **Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9..."
}
```
Copy the token string.

---

### 3. Place an Order (Async Execution)
* **Method:** `POST`
* **URL:** `http://<VM_IP>:3000/api/v1/orders` (or `/api/trades`)
* **Headers:** 
  - `Content-Type: application/json`
  - `Authorization: Bearer <YOUR_JWT_TOKEN>`
* **Body:**
```json
{
  "accountId": 1,
  "ticker": "AAPL",
  "side": "BUY",
  "quantity": 10,
  "price": 250.00,
  "idempotencyKey": "order-uuid-001"
}
```
* **Expected Response:** `200 OK` (or `201 Created`) with status `"NEW"`:
```json
{
  "orderId": "1",
  "status": "NEW",
  "message": "Order placed successfully",
  "symbol": "AAPL",
  "side": "BUY",
  "quantity": 10,
  "price": 250.00
}
```

---

### 4. Verify Async Execution Status
Wait 1–2 seconds for `trade-executor` to consume from Kafka and execute the order.

* **Method:** `GET`
* **URL:** `http://<VM_IP>:3000/api/v1/orders/1`
* **Headers:** `Authorization: Bearer <YOUR_JWT_TOKEN>`
* **Expected Response:**
```json
{
  "orderId": "1",
  "status": "FILLED",
  "symbol": "AAPL",
  "quantity": 10,
  "executedPrice": 150.50
}
```

---

### 5. Monitor Live Logs on VM

```bash
# Trade API logs
docker logs trading-trade-api -f

# Trade Executor logs (watch execution & limit price evaluation)
docker logs trading-executor -f

# Python ETL logs (watch performance snapshot calculations)
docker logs trading-etl -f
```

### 6. Monitor Kafka Topics Live

```bash
# Watch orders topic
docker exec trading-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic orders --from-beginning

# Watch trade-events topic
docker exec trading-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic trade-events --from-beginning

# Watch market-data topic
docker exec trading-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic market-data --from-beginning
```
