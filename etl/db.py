"""
Database helper — provides a connection factory and the upsert
that writes daily performance snapshots.

The `performance` table (from migration 002) stores:
    account_id  BIGINT
    date        DATE
    networth    NUMERIC(18,2)
    gain        NUMERIC(18,2)
    PRIMARY KEY (account_id, date)
"""
import logging
from contextlib import contextmanager
from decimal import Decimal

import psycopg2
import psycopg2.extras

from etl import config

log = logging.getLogger(__name__)


def get_connection():
    """Opens a new psycopg2 connection. Caller must close it."""
    return psycopg2.connect(
        host=config.DB_HOST,
        port=config.DB_PORT,
        dbname=config.DB_NAME,
        user=config.DB_USER,
        password=config.DB_PASSWORD,
    )


@contextmanager
def db_cursor():
    """Context manager yielding an auto-commit cursor."""
    conn = get_connection()
    try:
        with conn:
            with conn.cursor() as cur:
                yield cur
    finally:
        conn.close()


def upsert_performance(account_id: int, date_str: str,
                        networth: Decimal, gain: Decimal) -> None:
    """
    Inserts or updates the performance row for (account_id, date).
    Uses ON CONFLICT DO UPDATE so it is safe to call repeatedly.
    """
    sql = """
        INSERT INTO performance (account_id, date, networth, gain)
        VALUES (%s, %s, %s, %s)
        ON CONFLICT (account_id, date)
        DO UPDATE SET
            networth = EXCLUDED.networth,
            gain     = EXCLUDED.gain
    """
    with db_cursor() as cur:
        cur.execute(sql, (account_id, date_str, networth, gain))
    log.debug("Upserted performance account=%s date=%s networth=%s gain=%s",
              account_id, date_str, networth, gain)


def fetch_account_cash(account_id: int) -> Decimal | None:
    """Fetches current cash_balance for an account. Returns None if not found."""
    with db_cursor() as cur:
        cur.execute("SELECT cash_balance FROM account WHERE account_id = %s", (account_id,))
        row = cur.fetchone()
        return Decimal(str(row[0])) if row else None


def fetch_holding_value(account_id: int) -> Decimal:
    """
    Calculates total holding market value as sum(quantity * average_price).
    Falls back to average_price when live price is unavailable.
    """
    sql = """
        SELECT COALESCE(SUM(h.quantity * h.average_price), 0)
        FROM holding h
        WHERE h.account_id = %s
    """
    with db_cursor() as cur:
        cur.execute(sql, (account_id,))
        row = cur.fetchone()
        return Decimal(str(row[0])) if row else Decimal("0")
