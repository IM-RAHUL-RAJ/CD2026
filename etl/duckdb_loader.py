"""
Incremental DuckDB ETL for star schema (contracts/analytics-schema.sql).

Loads dimensions and facts in correct order with watermarking and idempotency:
  1. dim_date (full range, once)
  2. dim_instrument (Type 1, merge on symbol)
  3. dim_account (Type 2 SCD, merge on account_id+effective_date)
  4. fact_trades (incremental, watermark on orders.created_on)

Usage:
    python -m etl.duckdb_loader [--date-start 2025-01-01] [--date-end 2026-12-31]
"""
import logging
import sys
from datetime import datetime, date, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Optional

import duckdb
import psycopg2
from psycopg2.extras import DictCursor

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s %(name)s — %(message)s",
    datefmt="%Y-%m-%dT%H:%M:%S",
)
log = logging.getLogger(__name__)


class AnalyticsDuckDBLoader:
    """Incremental star schema loader with watermarking and SCD Type 2."""

    def __init__(self, duckdb_path: str, pg_conn_str: str):
        self.duckdb_path = duckdb_path
        self.pg_conn_str = pg_conn_str
        self.duckdb_conn = None
        self.pg_conn = None
        self.next_account_key = 1
        self.next_instrument_key = 1
        self.next_trade_key = 1

    def connect(self):
        """Open DuckDB and PostgreSQL connections."""
        self.duckdb_conn = duckdb.connect(self.duckdb_path)
        self.pg_conn = psycopg2.connect(self.pg_conn_str)
        log.info("Connected to DuckDB: %s", self.duckdb_path)
        log.info("Connected to PostgreSQL")

    def close(self):
        """Close connections."""
        if self.duckdb_conn:
            self.duckdb_conn.close()
        if self.pg_conn:
            self.pg_conn.close()
        log.info("Closed DuckDB and PostgreSQL connections")

    def _init_surrogate_keys(self):
        """Initialize surrogate key counters from DuckDB state."""
        try:
            res = self.duckdb_conn.execute(
                "SELECT COALESCE(MAX(account_key), 0) + 1 FROM dim_account"
            ).fetchall()
            self.next_account_key = res[0][0] if res else 1

            res = self.duckdb_conn.execute(
                "SELECT COALESCE(MAX(instrument_key), 0) + 1 FROM dim_instrument"
            ).fetchall()
            self.next_instrument_key = res[0][0] if res else 1

            res = self.duckdb_conn.execute(
                "SELECT COALESCE(MAX(trade_key), 0) + 1 FROM fact_trades"
            ).fetchall()
            self.next_trade_key = res[0][0] if res else 1

            log.info(
                "Initialized surrogate keys: "
                "account_key=%d, instrument_key=%d, trade_key=%d",
                self.next_account_key,
                self.next_instrument_key,
                self.next_trade_key,
            )
        except Exception as e:
            log.warning("Could not init surrogate keys (tables may not exist): %s", e)

    def _initialize_schema(self):
        """Execute DDL from contracts/analytics-schema.sql to create tables if they don't exist."""
        try:
            schema_path = Path(__file__).resolve().parent.parent / "contracts" / "analytics-schema.sql"
            
            if not schema_path.exists():
                log.warning("Schema file not found at %s, skipping schema initialization", schema_path)
                return
            
            log.info("Initializing schema from %s", schema_path)
            with open(schema_path, 'r') as f:
                ddl = f.read()
            
            # Execute DDL
            self.duckdb_conn.execute(ddl)
            log.info("Schema initialized successfully")
        except Exception as e:
            log.error("Error initializing schema: %s", e)
            raise

    def load(
        self,
        date_start: date = None,
        date_end: date = None,
    ):
        """Execute full incremental load pipeline."""
        try:
            self.connect()
            self._initialize_schema()  # Create tables if they don't exist
            self._init_surrogate_keys()

            # Default date range (10 years)
            if not date_start:
                date_start = date.today() - timedelta(days=365 * 10)
            if not date_end:
                date_end = date.today() + timedelta(days=365)

            log.info("Starting incremental load: %s to %s", date_start, date_end)

            # Load in order
            self.load_dim_date(date_start, date_end)
            self.load_dim_instrument()
            self.load_dim_account()
            self.load_fact_trades()

            log.info("Incremental load complete")
        finally:
            self.close()

    # ── Dimension Loading ─────────────────────────────────────────────────────

    def load_dim_date(self, start_date: date, end_date: date):
        """Load or update dim_date (full range, done once)."""
        log.info("Loading dim_date from %s to %s", start_date, end_date)

        day_names = [
            "Monday",
            "Tuesday",
            "Wednesday",
            "Thursday",
            "Friday",
            "Saturday",
            "Sunday",
        ]
        month_names = [
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        ]

        rows = []
        current = start_date
        while current <= end_date:
            date_key = int(current.strftime("%Y%m%d"))
            day = current.day
            month = current.month
            year = current.year
            quarter = (month - 1) // 3 + 1
            day_of_week = current.weekday()  # 0=Monday, 6=Sunday
            day_name = day_names[day_of_week]
            month_name = month_names[month - 1]
            is_weekday = day_of_week < 5

            rows.append(
                (
                    date_key,
                    current.isoformat(),
                    day,
                    month,
                    year,
                    quarter,
                    day_of_week,
                    day_name,
                    month_name,
                    is_weekday,
                )
            )
            current += timedelta(days=1)

        # Idempotent: insert or ignore existing dates
        df = __import__("pandas").DataFrame(
            rows,
            columns=[
                "date_key",
                "full_date",
                "day",
                "month",
                "year",
                "quarter",
                "day_of_week",
                "day_name",
                "month_name",
                "is_weekday",
            ],
        )

        self.duckdb_conn.execute(
            """
            INSERT OR IGNORE INTO dim_date 
            SELECT * FROM df
            """
        )
        log.info("Loaded %d date rows", len(rows))

    def load_dim_instrument(self):
        """Load or update dim_instrument (Type 1, merge on symbol)."""
        log.info("Loading dim_instrument")

        # Fetch from PostgreSQL operational schema
        cursor = self.pg_conn.cursor(cursor_factory=DictCursor)
        cursor.execute(
            """
            SELECT instrument_id, symbol, ticker, asset_class, quote_currency
            FROM instrument
            WHERE status = 'TRADEABLE'
            ORDER BY symbol
            """
        )
        rows = cursor.fetchall()
        cursor.close()
        log.info("Fetched %d instruments from PostgreSQL", len(rows))

        for row in rows:
            symbol = row["symbol"].upper()
            name = row.get("ticker") or symbol  # Use ticker as name if available
            asset_class = row.get("asset_class") or "EQUITY"
            currency = row.get("quote_currency") or "USD"

            # Derive exchange from symbol
            exchange = self._derive_exchange(symbol)

            # Idempotent merge on symbol (Type 1 overwrite)
            self.duckdb_conn.execute(
                """
                WITH new_data AS (
                    SELECT ? as instrument_key, ? as symbol, ? as name, 
                           ? as asset_class, ? as currency, ? as exchange, 
                           true as tradable, now() as loaded_at
                )
                MERGE INTO dim_instrument t USING new_data s 
                ON t.symbol = s.symbol
                WHEN MATCHED THEN
                    UPDATE SET name = s.name, asset_class = s.asset_class, 
                               currency = s.currency, exchange = s.exchange,
                               tradable = s.tradable, loaded_at = s.loaded_at
                WHEN NOT MATCHED THEN
                    INSERT (instrument_key, symbol, name, asset_class, 
                            currency, exchange, tradable, loaded_at)
                    VALUES (s.instrument_key, s.symbol, s.name, s.asset_class,
                            s.currency, s.exchange, s.tradable, s.loaded_at)
                """,
                [
                    self.next_instrument_key,
                    symbol,
                    name,
                    asset_class,
                    currency,
                    exchange,
                ],
            )
            self.next_instrument_key += 1

        log.info("Loaded %d instruments", len(rows))

    def load_dim_account(self):
        """Load or update dim_account (Type 2 SCD, merge on account_id+effective_date)."""
        log.info("Loading dim_account (Type 2 SCD)")

        # Fetch current account state from PostgreSQL
        cursor = self.pg_conn.cursor(cursor_factory=DictCursor)
        cursor.execute(
            """
            SELECT account_id, account_name, status
            FROM account
            ORDER BY account_id
            """
        )
        rows = cursor.fetchall()
        cursor.close()
        log.info("Fetched %d accounts from PostgreSQL", len(rows))

        today = date.today()

        for row in rows:
            account_id = str(row["account_id"])
            holder_name = row.get("account_name") or "Unknown"
            status = row.get("status") or "ACTIVE"

            # Check if this account_id+status combination already exists as current
            existing = self.duckdb_conn.execute(
                """
                SELECT account_key, effective_date FROM dim_account
                WHERE account_id = ? AND is_current = true
                """,
                [account_id],
            ).fetchall()

            if existing:
                existing_key = existing[0][0]
                existing_effective = existing[0][1]

                # If status changed, close old version and insert new one
                if self._check_account_status_changed(account_id, status, existing_effective):
                    # Close previous version (Type 2 SCD)
                    self.duckdb_conn.execute(
                        """
                        UPDATE dim_account
                        SET end_date = ?, is_current = false
                        WHERE account_key = ?
                        """,
                        [today - timedelta(days=1), existing_key],
                    )
                    # Insert new version
                    self.duckdb_conn.execute(
                        """
                        INSERT INTO dim_account
                        (account_key, account_id, holder_name, status, effective_date,
                         end_date, is_current, source_id, loaded_at)
                        VALUES (?, ?, ?, ?, ?, null, true, ?, now())
                        """,
                        [
                            self.next_account_key,
                            account_id,
                            holder_name,
                            status,
                            today,
                            row["account_id"],
                        ],
                    )
                    self.next_account_key += 1
                    log.debug(
                        "Closed account_key=%d, opened new key=%d for %s",
                        existing_key,
                        self.next_account_key - 1,
                        account_id,
                    )
            else:
                # New account: insert as current version
                self.duckdb_conn.execute(
                    """
                    INSERT INTO dim_account
                    (account_key, account_id, holder_name, status, effective_date,
                     end_date, is_current, source_id, loaded_at)
                    VALUES (?, ?, ?, ?, ?, null, true, ?, now())
                    """,
                    [
                        self.next_account_key,
                        account_id,
                        holder_name,
                        status,
                        today,
                        row["account_id"],
                    ],
                )
                self.next_account_key += 1

        log.info("Loaded %d accounts", len(rows))

    def _check_account_status_changed(self, account_id: str, new_status: str, effective_date) -> bool:
        """Check if status changed since last load."""
        try:
            res = self.duckdb_conn.execute(
                """
                SELECT status FROM dim_account
                WHERE account_id = ? AND effective_date = ?
                """,
                [account_id, effective_date],
            ).fetchall()
            if res:
                return res[0][0] != new_status
            return True
        except:
            return True

    def load_fact_trades(self):
        """Load or update fact_trades (incremental, watermark on created_on)."""
        log.info("Loading fact_trades (incremental)")

        # Get last watermark
        try:
            res = self.duckdb_conn.execute(
                "SELECT last_loaded_ts FROM etl_watermark WHERE table_name = 'fact_trades'"
            ).fetchall()
            last_watermark = res[0][0] if res else datetime(2025, 1, 1)
        except:
            last_watermark = datetime(2025, 1, 1)

        log.info("Last watermark: %s", last_watermark)

        # Fetch new orders since watermark from PostgreSQL
        cursor = self.pg_conn.cursor(cursor_factory=DictCursor)
        cursor.execute(
            """
            SELECT order_id, account_id, ticker, side, quantity, price, order_type, 
                   status, received_at, executed_price, executed_on
            FROM orders
            WHERE received_at > %s
            ORDER BY received_at
            """,
            [last_watermark],
        )
        rows = cursor.fetchall()
        cursor.close()
        log.info("Fetched %d new orders since %s", len(rows), last_watermark)

        # Load facts with dimension key lookups
        loaded_count = 0
        for row in rows:
            try:
                # Validate and resolve dimensions
                account_key = self._lookup_account_key(row["account_id"])
                instrument_key = self._lookup_instrument_key(row["ticker"])
                date_key = self._date_to_key(row["received_at"].date())

                if not account_key or not instrument_key or not date_key:
                    log.warning(
                        "Skipping order %s: "
                        "account_key=%s, instrument_key=%s, date_key=%s",
                        row["order_id"],
                        account_key,
                        instrument_key,
                        date_key,
                    )
                    continue

                # Data quality checks
                side = row["side"].upper()
                quantity = int(row["quantity"])
                price = Decimal(str(row["price"]))
                status = row["status"]

                if quantity <= 0 or price <= 0:
                    log.warning(
                        "Skipping order %s: quantity=%d, price=%s",
                        row["order_id"],
                        quantity,
                        price,
                    )
                    continue

                if side not in ("BUY", "SELL"):
                    log.warning("Skipping order %s: invalid side %s", row["order_id"], side)
                    continue

                if status not in ("NEW", "FILLED", "REJECTED", "CANCELLED"):
                    log.warning("Skipping order %s: invalid status %s", row["order_id"], status)
                    continue

                # Compute trade_value
                if status == "FILLED" and row["executed_price"]:
                    trade_value = Decimal(str(quantity)) * Decimal(str(row["executed_price"]))
                else:
                    trade_value = Decimal(str(quantity)) * price

                # Idempotent insert on source_order_id
                try:
                    self.duckdb_conn.execute(
                        """
                        INSERT INTO fact_trades
                        (trade_key, account_key, instrument_key, date_key, side, quantity,
                         price, status, executed_price, trade_value, source_order_id,
                         created_at, loaded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                        """,
                        [
                            self.next_trade_key,
                            account_key,
                            instrument_key,
                            date_key,
                            side,
                            quantity,
                            float(price),
                            status,
                            float(row["executed_price"]) if row["executed_price"] else None,
                            float(trade_value),
                            str(row["order_id"]),
                            row["received_at"],
                        ],
                    )
                    self.next_trade_key += 1
                    loaded_count += 1
                except Exception as e:
                    if "UNIQUE constraint failed" in str(e):
                        log.debug("Order %s already loaded, skipping", row["order_id"])
                    else:
                        log.error("Error inserting order %s: %s", row["order_id"], e)

            except Exception as e:
                log.error("Error processing order %s: %s", row["order_id"], e)

        # Update watermark
        now = datetime.now()
        try:
            self.duckdb_conn.execute(
                """
                INSERT OR REPLACE INTO etl_watermark (table_name, last_loaded_ts)
                VALUES ('fact_trades', ?)
                """,
                [now],
            )
        except Exception as e:
            log.warning("Could not update watermark: %s", e)

        log.info("Loaded %d new fact_trades rows", loaded_count)

    # ── Dimension Lookup ──────────────────────────────────────────────────────

    def _lookup_account_key(self, account_id: int) -> Optional[int]:
        """Look up account_key by account_id where is_current=true."""
        try:
            res = self.duckdb_conn.execute(
                """
                SELECT account_key FROM dim_account
                WHERE account_id = ? AND is_current = true
                """,
                [str(account_id)],
            ).fetchall()
            return res[0][0] if res else None
        except Exception as e:
            log.error("Error looking up account_key for account_id %s: %s", account_id, e)
            return None

    def _lookup_instrument_key(self, symbol: str) -> Optional[int]:
        """Look up instrument_key by symbol."""
        try:
            res = self.duckdb_conn.execute(
                "SELECT instrument_key FROM dim_instrument WHERE symbol = ?",
                [symbol.upper()],
            ).fetchall()
            return res[0][0] if res else None
        except Exception as e:
            log.error("Error looking up instrument_key for symbol %s: %s", symbol, e)
            return None

    def _date_to_key(self, d: date) -> Optional[int]:
        """Convert date to date_key (YYYYMMDD)."""
        try:
            return int(d.strftime("%Y%m%d"))
        except:
            return None

    def _derive_exchange(self, symbol: str) -> str:
        """Derive exchange from symbol suffix."""
        symbol = symbol.upper()
        if ".NS" in symbol:
            return "NSE"
        elif ".BO" in symbol:
            return "BSE"
        elif symbol.startswith("FX:"):
            return "FX"
        elif symbol.startswith("X:"):
            return "CRYPTO"
        else:
            return "US"


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Incremental DuckDB analytics loader")
    parser.add_argument(
        "--duckdb-path",
        default="analytics.duckdb",
        help="Path to DuckDB file (default: analytics.duckdb)",
    )
    parser.add_argument(
        "--pg-conn",
        default="postgresql://postgres:postgres@localhost:5432/trading_db",
        help="PostgreSQL connection string",
    )
    parser.add_argument(
        "--date-start",
        type=lambda s: datetime.fromisoformat(s).date(),
        help="Start date (YYYY-MM-DD)",
    )
    parser.add_argument(
        "--date-end",
        type=lambda s: datetime.fromisoformat(s).date(),
        help="End date (YYYY-MM-DD)",
    )

    args = parser.parse_args()

    loader = AnalyticsDuckDBLoader(args.duckdb_path, args.pg_conn)
    loader.load(date_start=args.date_start, date_end=args.date_end)
