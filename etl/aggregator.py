"""
Aggregation layer for the ETL.

Accumulates TRADE_EXECUTED events in memory and, on each flush,
computes per-account daily networth and gain, then writes to the
performance table.

Networth  = cash_balance + sum(holding.quantity * holding.average_price)
Gain      = networth - previous networth (or 0 on first insert for the day)
"""
import logging
from collections import defaultdict
from datetime import date, datetime, timezone
from decimal import Decimal

from etl import db

log = logging.getLogger(__name__)


class PerformanceAggregator:
    """
    Buffers account IDs that had activity since the last flush.
    On flush(), re-reads live balances from DB and upserts performance rows.
    """

    def __init__(self) -> None:
        # Set of account_ids that received a trade event since last flush
        self._dirty_accounts: set[int] = set()

    def record_trade(self, account_id: int) -> None:
        """Mark account as needing a performance snapshot on next flush."""
        self._dirty_accounts.add(account_id)

    def flush(self) -> None:
        """
        For every dirty account, compute today's networth and gain and upsert.
        """
        if not self._dirty_accounts:
            return

        today = date.today().isoformat()
        log.info("Flushing performance snapshots for %d accounts", len(self._dirty_accounts))

        for account_id in list(self._dirty_accounts):
            try:
                cash = db.fetch_account_cash(account_id)
                if cash is None:
                    log.warning("Account %s not found, skipping", account_id)
                    continue

                holding_value = db.fetch_holding_value(account_id)
                networth = cash + holding_value

                # Gain: today's networth minus yesterday's networth (best-effort)
                gain = _compute_gain(account_id, today, networth)

                db.upsert_performance(account_id, today, networth, gain)
                self._dirty_accounts.discard(account_id)

            except Exception:
                log.exception("Failed to flush performance for account %s", account_id)

        log.info("Performance flush complete")


def _compute_gain(account_id: int, today: str, today_networth: Decimal) -> Decimal:
    """
    Returns today_networth minus yesterday's networth.
    Returns 0 if no prior record exists.
    """
    try:
        with db.db_cursor() as cur:
            cur.execute(
                """
                SELECT networth FROM performance
                WHERE account_id = %s AND date < %s
                ORDER BY date DESC
                LIMIT 1
                """,
                (account_id, today),
            )
            row = cur.fetchone()
            if row:
                return today_networth - Decimal(str(row[0]))
    except Exception:
        log.exception("Could not fetch prior performance for account %s", account_id)
    return Decimal("0")
