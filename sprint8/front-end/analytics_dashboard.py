"""Analytics dashboard backed by the Sprint 7 DuckDB data warehouse.

Exposes the reporting queries used by the Flask frontend. Every query opens a
short-lived read-only connection so the ETL trigger service can keep refreshing
the same .duckdb file without locking conflicts. Endpoints return plain JSON
data; the browser builds the Plotly figures so they can be updated in place.
"""

import os
from pathlib import Path

import duckdb
import pandas as pd

_here = Path(__file__).resolve()
REPO_ROOT = _here.parents[2] if len(_here.parents) > 2 else _here.parent
REQUIRED_TABLES = {"dim_account", "dim_date", "dim_instrument", "fact_trades"}


class AnalyticsDashboard:

    def __init__(self, duckdb_path=None):
        env_path = os.getenv("DUCKDB_PATH")
        configured = duckdb_path or env_path
        repo_file = str(REPO_ROOT / "analytics.duckdb")
        if not configured:
            self.path = repo_file
        else:
            self.path = os.path.abspath(configured)
            # A relative DUCKDB_PATH (e.g. from sprint6/.env) resolves against
            # the app's own directory; fall back to the canonical repo file.
            if not os.path.isabs(configured) and not os.path.exists(self.path):
                self.path = repo_file

    def connect(self):
        # Kept for compatibility with app.py; connections are per query.
        return None

    def disconnect(self):
        return None

    def _open(self):
        return duckdb.connect(self.path, read_only=True)

    def is_available(self) -> bool:
        if not os.path.exists(self.path):
            return False
        try:
            with self._open() as conn:
                tables = {
                    row[0]
                    for row in conn.execute(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'main'"
                    ).fetchall()
                }
                return REQUIRED_TABLES.issubset(tables)
        except Exception:
            return False

    def _query_df(self, sql: str, params=None):
        with self._open() as conn:
            if params:
                return conn.execute(sql, params).fetchdf()
            return conn.execute(sql).fetchdf()

    @staticmethod
    def _iso(value):
        return value.isoformat() if hasattr(value, "isoformat") else str(value)

    def _rows(self, frame: pd.DataFrame) -> list:
        if frame is None or frame.empty:
            return []
        return [list(row) for row in frame.itertuples(index=False, name=None)]

    def get_summary_stats(self) -> dict:
        if not self.is_available():
            return None
        try:
            frame = self._query_df(
                """
                SELECT COUNT(*)                              AS total_trades,
                       COALESCE(SUM(f.trade_value), 0)       AS total_notional,
                       COALESCE(SUM(CASE WHEN f.status = 'FILLED' THEN 1 ELSE 0 END), 0) AS filled_trades,
                       MAX(f.loaded_at)                      AS last_loaded
                FROM fact_trades f
                """
            )
            row = frame.iloc[0]
            total = int(row["total_trades"])
            notional = float(row["total_notional"])
            filled = int(row["filled_trades"])
            fill_rate = round(filled / total * 100, 2) if total else 0.0
            last_loaded = row["last_loaded"]
            return {
                "total_trades": total,
                "total_notional": round(notional, 2),
                "filled_trades": filled,
                "fill_rate": fill_rate,
                "last_loaded": (
                    pd.Timestamp(last_loaded).strftime("%Y-%m-%d %H:%M:%S") if last_loaded is not None else None
                ),
            }
        except Exception:
            return None

    def get_trade_volume(self) -> dict:
        if not self.is_available():
            return None
        try:
            frame = self._query_df(
                """
                SELECT d.full_date                       AS trade_date,
                       COUNT(*)                          AS trades,
                       COALESCE(SUM(f.trade_value), 0)   AS notional
                FROM fact_trades f
                JOIN dim_date d USING (date_key)
                GROUP BY d.full_date
                ORDER BY d.full_date
                """
            )
            if frame.empty:
                return None
            return {
                "dates": [self._iso(v) for v in frame["trade_date"].tolist()],
                "notional": [round(float(v), 2) for v in frame["notional"].tolist()],
                "trades": [int(v) for v in frame["trades"].tolist()],
            }
        except Exception:
            return None

    def get_fill_rate(self) -> dict:
        if not self.is_available():
            return None
        try:
            frame = self._query_df(
                """
                SELECT f.status,
                       COUNT(*) AS count
                FROM fact_trades f
                GROUP BY f.status
                ORDER BY count DESC
                """
            )
            if frame.empty:
                return None
            return {
                "statuses": [str(v) for v in frame["status"].tolist()],
                "counts": [int(v) for v in frame["count"].tolist()],
            }
        except Exception:
            return None

    def get_instrument_exposure(self) -> dict:
        if not self.is_available():
            return None
        try:
            frame = self._query_df(
                """
                SELECT i.symbol,
                       COALESCE(SUM(f.trade_value), 0) AS exposure,
                       COUNT(*)                        AS trades
                FROM fact_trades f
                JOIN dim_instrument i USING (instrument_key)
                GROUP BY i.symbol
                ORDER BY exposure DESC
                """
            )
            if frame.empty:
                return None
            frame = frame.iloc[::-1]
            return {
                "symbols": [str(v) for v in frame["symbol"].tolist()],
                "exposure": [round(float(v), 2) for v in frame["exposure"].tolist()],
                "trades": [int(v) for v in frame["trades"].tolist()],
            }
        except Exception:
            return None

    def get_account_activity(self) -> dict:
        if not self.is_available():
            return None
        try:
            frame = self._query_df(
                """
                SELECT a.account_id,
                       d.full_date  AS trade_date,
                       COUNT(*)     AS trades
                FROM fact_trades f
                JOIN dim_account a USING (account_key)
                JOIN dim_date d USING (date_key)
                GROUP BY a.account_id, d.full_date
                ORDER BY d.full_date
                """
            )
            if frame.empty:
                return None
            series = {}
            for _, row in frame.iterrows():
                account = str(row["account_id"])
                entry = series.setdefault(account, {"dates": [], "trades": []})
                entry["dates"].append(self._iso(row["trade_date"]))
                entry["trades"].append(int(row["trades"]))
            return {
                "series": [
                    {"name": name, "dates": data["dates"], "trades": data["trades"]}
                    for name, data in series.items()
                ]
            }
        except Exception:
            return None

    def get_recent_trades(self, limit: int = 8) -> list:
        if not self.is_available():
            return None
        try:
            frame = self._query_df(
                """
                SELECT f.source_order_id,
                       f.created_at,
                       i.symbol,
                       f.side,
                       f.quantity,
                       f.executed_price,
                       f.price,
                       f.status,
                       f.trade_value,
                       a.account_id
                FROM fact_trades f
                JOIN dim_instrument i USING (instrument_key)
                JOIN dim_account a USING (account_key)
                ORDER BY f.created_at DESC
                LIMIT ?
                """,
                [limit],
            )
            if frame.empty:
                return None
            rows = []
            for _, row in frame.iterrows():
                executed = row["executed_price"]
                effective = executed if executed is not None else row["price"]
                rows.append(
                    {
                        "order_id": str(row["source_order_id"]),
                        "time": pd.Timestamp(row["created_at"]).strftime("%m-%d %H:%M:%S"),
                        "symbol": str(row["symbol"]),
                        "side": str(row["side"]),
                        "quantity": int(row["quantity"]),
                        "price": round(float(effective or 0), 2),
                        "status": str(row["status"]),
                        "value": round(float(row["trade_value"]), 2),
                        "account_id": str(row["account_id"]),
                    }
                )
            return rows
        except Exception:
            return None