"""
ETL entry point.

Starts a Kafka consumer on the trade-events topic, routes
TRADE_EXECUTED events to the PerformanceAggregator, and flushes
snapshots to PostgreSQL every BATCH_FLUSH_INTERVAL_SECONDS.

Run:
    python -m etl.main
or via Docker:
    CMD ["python", "-m", "etl.main"]
"""
import logging
import signal
import time

from etl import config
from etl.aggregator import PerformanceAggregator
from etl.consumer import consume_messages, make_consumer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s %(name)s — %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)
log = logging.getLogger(__name__)

# ── graceful shutdown ─────────────────────────────────────────────────────────
_shutdown = False


def _handle_signal(signum, frame):
    global _shutdown
    log.info("Shutdown signal received (%s)", signum)
    _shutdown = True


signal.signal(signal.SIGTERM, _handle_signal)
signal.signal(signal.SIGINT, _handle_signal)
# ─────────────────────────────────────────────────────────────────────────────


def main() -> None:
    log.info("Trade Analytics ETL starting …")
    log.info("  Kafka  : %s", config.KAFKA_BOOTSTRAP_SERVERS)
    log.info("  Topics : %s, %s", config.TRADE_EVENTS_TOPIC, config.MARKET_DATA_TOPIC)
    log.info("  DB     : %s:%s/%s", config.DB_HOST, config.DB_PORT, config.DB_NAME)

    consumer = make_consumer([config.TRADE_EVENTS_TOPIC])
    aggregator = PerformanceAggregator()
    last_flush = time.monotonic()

    try:
        for message in consume_messages(consumer, poll_timeout=config.POLL_TIMEOUT_SECONDS):
            if _shutdown:
                break

            # Empty heartbeat — check flush interval
            if not message:
                _maybe_flush(aggregator, last_flush, config.BATCH_FLUSH_INTERVAL_SECONDS)
                last_flush = time.monotonic()
                continue

            event_type = message.get("eventType", "")

            if event_type in ("TRADE_EXECUTED", "ORDER_FILLED"):
                payload = message.get("payload", {})
                account_id = payload.get("accountId") or payload.get("account_id")
                if account_id is not None:
                    aggregator.record_trade(int(account_id))
                    log.info("Recorded %s event for account %s", event_type, account_id)

            # Flush periodically
            now = time.monotonic()
            if now - last_flush >= config.BATCH_FLUSH_INTERVAL_SECONDS:
                aggregator.flush()
                last_flush = now

    finally:
        log.info("Final flush before shutdown …")
        aggregator.flush()
        consumer.close()
        log.info("ETL stopped.")


def _maybe_flush(aggregator: PerformanceAggregator,
                 last_flush: float,
                 interval: int) -> None:
    now = time.monotonic()
    if now - last_flush >= interval:
        aggregator.flush()


if __name__ == "__main__":
    main()
