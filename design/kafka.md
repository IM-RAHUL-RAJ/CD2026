# Kafka Event Bus Design & Topic Architecture Specification

**Status**: Binding Design Contract  
**Sprint**: Sprint 7 (Event Backbone)  
**Topic Creation Script**: `infra/scripts/create-topics.sh`

---

## 1. Overview & System Purpose

The platform uses Apache Kafka as an asynchronous event bus to decouple order validation/acceptance from execution and downstream analytics.

- **`trade-api`** accepts incoming client orders, validates account eligibility, records the order in PostgreSQL with status `NEW`, and publishes an `ORDER_PLACED` event to the `orders` topic.
- **`trade-executor`** consumes orders from `orders`, fetches quotes from Fauxnance, executes fills or rejections, updates the DB idempotently, and publishes outcome events to `trade-events`.
- **`market-poller`** (inside `trade-executor`) polls Fauxnance quotes in batches and publishes quote updates to `market-data`.

---

## 2. Topic Catalogue & Configuration

| Topic | Purpose | Partition Key | Partitions | Replication | Retention | Cleanup Policy |
|---|---|---|---|---|---|---|
| `orders` | Accepted orders awaiting execution (Work Queue). | `accountId` (string) | 3 | 1 local (3 prod) | 7 days (`604800000` ms) | `delete` |
| `trade-events` | Order lifecycle outcomes (`FILLED`, `REJECTED`, `CANCELLED`). | `accountId` (string) | 3 | 1 local (3 prod) | 30 days (`2592000000` ms) | `delete` |
| `market-data` | Symbol price quote stream from Fauxnance API. | `symbol` (string) | 6 | 1 local (3 prod) | 1 day (`86400000` ms) | `delete` |
| `orders.DLT` | Dead-letter queue for malformed/unparseable order messages. | `accountId` (string) | 3 | 1 local (3 prod) | 7 days (`604800000` ms) | `delete` |
| `trade-events.DLT` | Dead-letter queue for execution event failure routing. | `accountId` (string) | 3 | 1 local (3 prod) | 30 days (`2592000000` ms) | `delete` |
| `market-data.DLT` | Dead-letter queue for unparseable quote payloads. | `symbol` (string) | 6 | 1 local (3 prod) | 1 day (`86400000` ms) | `delete` |

---

## 3. Keying & Partition Justification

### Why `orders` and `trade-events` are Keyed by `accountId`
- **Strict Per-Account Ordering Guarantee**: Kafka guarantees message ordering strictly **within a single partition**.
- Two orders placed on the same trading account (e.g., BUY 100 AAPL followed by SELL 100 AAPL) **must** be processed in exact chronological order. If `orders` were keyed by `orderId` or unkeyed (round-robin), consecutive orders for Account A would land on different partitions and could execute out of order (e.g. attempting to SELL before the BUY settles).
- Orders across different accounts have no sequential dependency and are parallelized across partitions.

### Why `market-data` is Keyed by `symbol`
- **Per-Instrument Quote Sequence**: Consumers of `market-data` (such as pricing components or strategy engines) require quotes for a specific symbol (e.g. `AAPL`) in chronological order. Keying by `symbol` guarantees that all quotes for `AAPL` land on the exact same partition.

### Partition Count Selection
- **`orders` & `trade-events` (3 Partitions)**: Allows up to 3 parallel instances of the `trade-executor` consumer group to run concurrently without idle consumers. Partitions cannot be decreased later without hashing key distribution breaks.
- **`market-data` (6 Partitions)**: Selected due to significantly higher message throughput from continuous quote polling across multiple tickers.

---

## 4. Disabling Topic Auto-Creation

Topic auto-creation is explicitly turned off on the broker (`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`).
- **Reasoning**: Auto-created topics default to 1 partition and default retention, silently corrupting system partition key distribution. Disabling auto-creation ensures producers attempting to write to missing topics fail explicitly with `UnknownTopicOrPartitionException`, making infrastructure configuration omissions immediately actionable.

---

## 5. Dead-Letter Topic (DLT) Design & Routing Strategy

Each primary topic has a paired Dead-Letter Topic (`<topic>.DLT`):
- **Poison Messages (Instant DLT)**: Unparseable JSON, illegal enum values, or schema version mismatch messages are immediately routed to `<topic>.DLT` with failure headers (`x-exception-message`, `x-original-topic`, `x-failure-timestamp`). This prevents bad messages from permanently blocking partition consumption.
- **Transient Failures (Retry first)**: Database timeouts or network glitches are retried with exponential backoff before landing in DLT.

---

## 6. Producer & Consumer Matrix

| Service Component | `orders` | `trade-events` | `market-data` | Consumer Group ID |
|---|---|---|---|---|
| **Trade REST API** | Producer | Optional Consumer | Not Used | N/A |
| **Trade Executor** | Consumer | Producer | Producer (Poller) | `trade-executor` |
| **Portfolio & P&L** | Not Used | Consumer | Consumer | `portfolio-service` |
| **Watchlists & Alerts** | Not Used | Not Used | Consumer | `watchlist-service` |
| **Customer Notifications** | Not Used | Consumer | Not Used | `notification-service` |
| **Python Analytical ETL** | Not Used | Consumer (Optional) | Not Used | `analytics-loader` |

---

## 7. Production Security Architecture Plan

While local development runs plaintext for simplicity, production deployments must configure:
1. **TLS / SSL**: Encrypted transit between microservices and brokers using TLS 1.3.
2. **SASL / SCRAM-SHA-512**: Service authentication. Each container presents individual credentials.
3. **Kafka ACLs**:
   - `Trade REST API`: WRITE ONLY to `orders`.
   - `Trade Executor`: READ ONLY on `orders`, WRITE ONLY to `trade-events` and `market-data`.
   - Read-only consumers (Analytics/Notifications): READ ONLY on `trade-events`/`market-data`.
