from __future__ import annotations

from pathlib import Path
from datetime import datetime

import duckdb
import pandas as pd


SEED_DATE = datetime(2025, 1, 1).date()

SEED_ACCOUNT = {
    "account_id": "ACC001",
    "holder_name": "Default Trading Account",
    "status": "ACTIVE",
    "effective_date": SEED_DATE,
}

SEED_INSTRUMENTS = {
    "AAPL":      {"name": "Apple Inc.",             "asset_class": "EQUITY", "currency": "USD"},
    "MSFT":      {"name": "Microsoft Corporation",  "asset_class": "EQUITY", "currency": "USD"},
    "GOOGL":     {"name": "Alphabet Inc.",           "asset_class": "EQUITY", "currency": "USD"},
    "AMZN":      {"name": "Amazon.com Inc.",         "asset_class": "EQUITY", "currency": "USD"},
    "TSLA":      {"name": "Tesla Inc.",              "asset_class": "EQUITY", "currency": "USD"},
    "NVDA":      {"name": "NVIDIA Corporation",      "asset_class": "EQUITY", "currency": "USD"},
    "META":      {"name": "Meta Platforms Inc.",     "asset_class": "EQUITY", "currency": "USD"},
    "NFLX":      {"name": "Netflix Inc.",            "asset_class": "EQUITY", "currency": "USD"},
    "INFY":      {"name": "Infosys Limited",         "asset_class": "EQUITY", "currency": "INR"},
    "RELIANCE":  {"name": "Reliance Industries",     "asset_class": "EQUITY", "currency": "INR"},
    "TATASTEEL": {"name": "Tata Steel",              "asset_class": "EQUITY", "currency": "INR"},
}

DEFAULT_INSTRUMENT = {"name": "Unknown Instrument", "asset_class": "EQUITY", "currency": "USD"}
SEED_FACT = {"side": "BUY", "quantity": 100, "price": 100.00, "status": "FILLED"}


class TradingLoadError(Exception):
    pass


class TradingLoader:

    EXPECTED_TABLES = {"dim_account", "dim_instrument", "dim_date", "fact_trades"}

    def __init__(self, database_path=None, schema_path=None):
        base = Path(__file__).resolve().parent.parent.parent
        self.database_path = Path(database_path).resolve() if database_path else base / "analytics.duckdb"
        self.schema_path = (
            Path(schema_path).resolve() if schema_path
            else Path(__file__).resolve().parent / "analytics-schema.sql"
        )

    def connect(self):
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        return duckdb.connect(str(self.database_path))

    # -- helpers ---------------------------------------------------------------

    def _find_col(self, df, candidates):
        for name in candidates:
            if name.lower() in df.columns:
                return name.lower()
        return None

    def _val(self, row, col):
        if col is None or col not in row.index:
            return None
        v = row[col]
        if pd.isna(v):
            return None
        if isinstance(v, str):
            v = v.strip()
            return v or None
        return v

    def _to_int(self, v):
        if v is None:
            return None
        try:
            return None if pd.isna(v) else int(float(v))
        except (TypeError, ValueError, OverflowError):
            return None

    def _to_float(self, v):
        if v is None:
            return None
        try:
            return None if pd.isna(v) else float(v)
        except (TypeError, ValueError, OverflowError):
            return None

    def _clean(self, v):
        if v is None:
            return None
        if isinstance(v, str):
            return v.strip() or None
        try:
            if pd.isna(v):
                return None
        except TypeError:
            pass
        return str(v).strip() or None

    def _seed_instrument(self, symbol):
        symbol = (self._clean(symbol) or "").upper()
        base = symbol.split("_")[0] if "_" in symbol else symbol
        return {**DEFAULT_INSTRUMENT, **SEED_INSTRUMENTS.get(base, {})}

    def _infer_symbol_from_file(self, file):
        return file.stem.strip().upper() or "AAPL"

    # -- schema ----------------------------------------------------------------

    def create_schema(self, conn):
        if not self.schema_path.exists():
            raise FileNotFoundError(f"Schema file missing: {self.schema_path}")
        sql = self.schema_path.read_text(encoding="utf-8")
        sql_idempotent = sql.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")
        conn.execute(sql_idempotent)

    # -- dimension get-or-create -----------------------------------------------

    def _get_or_create_instrument(self, conn, symbol):
        symbol = (self._clean(symbol) or "AAPL").upper()
        row = conn.execute(
            "SELECT instrument_key FROM dim_instrument WHERE symbol = ?", [symbol]
        ).fetchone()
        if row:
            return row[0]
        seed = self._seed_instrument(symbol)
        key = conn.execute(
            "SELECT COALESCE(MAX(instrument_key), 0) + 1 FROM dim_instrument"
        ).fetchone()[0]
        conn.execute(
            "INSERT INTO dim_instrument "
            "(instrument_key, symbol, name, asset_class, currency) "
            "VALUES (?, ?, ?, ?, ?)",
            [key, symbol, seed["name"], seed["asset_class"], seed["currency"]],
        )
        return key

    def _get_or_create_account(self, conn):
        aid, edate = SEED_ACCOUNT["account_id"], SEED_ACCOUNT["effective_date"]
        row = conn.execute(
            "SELECT account_key FROM dim_account WHERE account_id = ? AND effective_date = ?",
            [aid, edate],
        ).fetchone()
        if row:
            return row[0]
        key = conn.execute(
            "SELECT COALESCE(MAX(account_key), 0) + 1 FROM dim_account"
        ).fetchone()[0]
        conn.execute(
            "INSERT INTO dim_account "
            "(account_key, account_id, holder_name, status, effective_date) "
            "VALUES (?, ?, ?, ?, ?)",
            [key, aid, SEED_ACCOUNT["holder_name"], SEED_ACCOUNT["status"], edate],
        )
        return key

    def _get_or_create_date(self, conn, d):
        d = d or SEED_DATE
        row = conn.execute(
            "SELECT date_key FROM dim_date WHERE full_date = ?", [d]
        ).fetchone()
        if row:
            return row[0]
        key = int(d.strftime("%Y%m%d"))
        conn.execute(
            "INSERT INTO dim_date (date_key, full_date, day, month, year, quarter) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            [key, d, d.day, d.month, d.year, ((d.month - 1) // 3) + 1],
        )
        return key

    # -- dimension bulk loaders ------------------------------------------------

    def load_dim_date(self, conn, dataframes):
        print("\nDIM_DATE")
        all_dates = []
        for _, df in dataframes:
            col = self._find_col(df, ["date", "datetime", "timestamp", "created_at"])
            if col:
                all_dates.extend(
                    pd.to_datetime(df[col], errors="coerce").dt.date.dropna().tolist()
                )
        if not all_dates:
            all_dates.append(SEED_DATE)

        inserted = skipped = 0
        for d in sorted(set(all_dates)):
            if conn.execute("SELECT 1 FROM dim_date WHERE full_date = ?", [d]).fetchone():
                skipped += 1
                continue
            key = int(d.strftime("%Y%m%d"))
            conn.execute(
                "INSERT INTO dim_date (date_key, full_date, day, month, year, quarter) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                [key, d, d.day, d.month, d.year, ((d.month - 1) // 3) + 1],
            )
            inserted += 1
        print(f"  Inserted: {inserted}  Skipped: {skipped}")

    def load_dim_instrument(self, conn, dataframes):
        print("\nDIM_INSTRUMENT")
        inserted = skipped = 0
        seen: set = set()
        for file, df in dataframes:
            sym_col = self._find_col(df, ["symbol", "ticker", "instrument"])
            for _, row in df.iterrows():
                symbol = (
                    self._clean(self._val(row, sym_col)) or self._infer_symbol_from_file(file)
                ).upper()
                if symbol in seen:
                    skipped += 1
                    continue
                seen.add(symbol)
                if conn.execute("SELECT 1 FROM dim_instrument WHERE symbol = ?", [symbol]).fetchone():
                    skipped += 1
                    continue
                seed = self._seed_instrument(symbol)
                key = conn.execute(
                    "SELECT COALESCE(MAX(instrument_key), 0) + 1 FROM dim_instrument"
                ).fetchone()[0]
                try:
                    conn.execute(
                        "INSERT INTO dim_instrument "
                        "(instrument_key, symbol, name, asset_class, currency) "
                        "VALUES (?, ?, ?, ?, ?)",
                        [key, symbol, seed["name"], seed["asset_class"], seed["currency"]],
                    )
                    inserted += 1
                except Exception as e:
                    print(f"  Could not insert {symbol}: {e}")
                    skipped += 1
        print(f"  Inserted: {inserted}  Skipped: {skipped}")

    def load_dim_account(self, conn, dataframes):
        print("\nDIM_ACCOUNT")
        inserted = skipped = 0
        for _, df in dataframes:
            acc_col = self._find_col(df, ["account_id", "account"])
            for _, row in df.iterrows():
                account_id = self._clean(self._val(row, acc_col)) or SEED_ACCOUNT["account_id"]
                edate = SEED_ACCOUNT["effective_date"]
                if conn.execute(
                    "SELECT 1 FROM dim_account WHERE account_id = ? AND effective_date = ?",
                    [account_id, edate],
                ).fetchone():
                    skipped += 1
                    continue
                key = conn.execute(
                    "SELECT COALESCE(MAX(account_key), 0) + 1 FROM dim_account"
                ).fetchone()[0]
                try:
                    conn.execute(
                        "INSERT INTO dim_account "
                        "(account_key, account_id, holder_name, status, effective_date) "
                        "VALUES (?, ?, ?, ?, ?)",
                        [key, account_id, SEED_ACCOUNT["holder_name"],
                         SEED_ACCOUNT["status"], edate],
                    )
                    inserted += 1
                except Exception as e:
                    print(f"  Could not insert account {account_id}: {e}")
                    skipped += 1
        print(f"  Inserted: {inserted}  Skipped: {skipped}")

    def load_fact_trades(self, conn, dataframes):
        print("\nFACT_TRADES")
        inserted = skipped = 0
        account_key = self._get_or_create_account(conn)

        for file, df in dataframes:
            sym_col   = self._find_col(df, ["symbol", "ticker", "instrument"])
            date_col  = self._find_col(df, ["date", "datetime", "timestamp", "created_at"])
            price_col = self._find_col(df, ["price", "close"])
            qty_col   = self._find_col(df, ["quantity", "qty", "volume"])

            for row_num, (_, row) in enumerate(df.iterrows(), start=1):
                order_id = f"SEED-{file.stem.upper()}-{row_num:06d}"

                if conn.execute(
                    "SELECT 1 FROM fact_trades WHERE source_order_id = ?", [order_id]
                ).fetchone():
                    skipped += 1
                    continue

                symbol = (
                    self._clean(self._val(row, sym_col)) or self._infer_symbol_from_file(file)
                ).upper()
                instrument_key = self._get_or_create_instrument(conn, symbol)

                raw_date = self._val(row, date_col)
                if raw_date is not None:
                    parsed = pd.to_datetime(raw_date, errors="coerce")
                    trade_date = parsed.date() if not pd.isna(parsed) else SEED_DATE
                else:
                    trade_date = SEED_DATE
                date_key = self._get_or_create_date(conn, trade_date)

                price    = self._to_float(self._val(row, price_col)) or SEED_FACT["price"]
                quantity = self._to_int(self._val(row, qty_col))     or SEED_FACT["quantity"]
                if price    <= 0: price    = SEED_FACT["price"]
                if quantity <= 0: quantity = SEED_FACT["quantity"]

                trade_key = conn.execute(
                    "SELECT COALESCE(MAX(trade_key), 0) + 1 FROM fact_trades"
                ).fetchone()[0]
                try:
                    conn.execute(
                        "INSERT INTO fact_trades "
                        "(trade_key, account_key, instrument_key, date_key, "
                        " side, quantity, price, status, source_order_id) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        [trade_key, account_key, instrument_key, date_key,
                         SEED_FACT["side"], quantity, price, SEED_FACT["status"], order_id],
                    )
                    inserted += 1
                except Exception as e:
                    print(f"  Could not insert row {order_id}: {e}")
                    skipped += 1

        print(f"  Inserted: {inserted}  Skipped: {skipped}")

    # -- validation & summary --------------------------------------------------

    def validate_database(self, conn):
        print("\nVALIDATION")
        tables = {
            r[0].lower()
            for r in conn.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'main'"
            ).fetchall()
        }
        missing = self.EXPECTED_TABLES - tables
        if missing:
            raise TradingLoadError("Missing tables: " + ", ".join(sorted(missing)))
        print("  All required tables present.")

        checks = [
            ("SELECT symbol, COUNT(*) FROM dim_instrument WHERE symbol IS NOT NULL "
             "GROUP BY symbol HAVING COUNT(*) > 1",
             "duplicate symbols in dim_instrument"),
            ("SELECT full_date, COUNT(*) FROM dim_date WHERE full_date IS NOT NULL "
             "GROUP BY full_date HAVING COUNT(*) > 1",
             "duplicate dates in dim_date"),
            ("SELECT source_order_id, COUNT(*) FROM fact_trades "
             "WHERE source_order_id IS NOT NULL "
             "GROUP BY source_order_id HAVING COUNT(*) > 1",
             "duplicate orders in fact_trades"),
        ]
        for sql, label in checks:
            if conn.execute(sql).fetchall():
                raise TradingLoadError(f"Validation failed: {label}")

        print("  No duplicate keys detected.")
        print("VALIDATION PASSED")

    def print_summary(self, conn):
        print("\nLOAD SUMMARY")
        for table in ["dim_account", "dim_instrument", "dim_date", "fact_trades"]:
            count = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            print(f"  {table.upper():20} : {count} rows")

    # -- main entry point ------------------------------------------------------

    def load(self, transformed_folder=None, clear_existing=False):
        base = Path(__file__).resolve().parent.parent.parent
        folder = (
            Path(transformed_folder).resolve() if transformed_folder
            else base / "transformed"
        )

        if not folder.exists():
            raise FileNotFoundError(f"Transform folder missing: {folder}")

        csv_files = sorted(folder.glob("*.csv"))
        if not csv_files:
            raise TradingLoadError("No transformed CSV files found.")

        print(f"TRADING ETL LOAD START -- {len(csv_files)} file(s)")
        for f in csv_files:
            print(f"  - {f.name}")

        conn = self.connect()
        try:
            self.create_schema(conn)

            if clear_existing:
                print("  Clearing existing fact_trades table for fresh batch load...")
                conn.execute("DELETE FROM fact_trades")

            dataframes = []
            for file in csv_files:
                df = pd.read_csv(file)
                df = df.loc[:, ~df.columns.str.contains("^Unnamed", case=False)]
                df.columns = [str(c).strip().lower() for c in df.columns]
                if not df.empty:
                    dataframes.append((file, df))

            if not dataframes:
                print("No usable data in CSV files.")
                self.print_summary(conn)
                return

            self.load_dim_date(conn, dataframes)
            self.load_dim_instrument(conn, dataframes)
            self.load_dim_account(conn, dataframes)
            self.load_fact_trades(conn, dataframes)
            self.validate_database(conn)
            self.print_summary(conn)
        finally:
            conn.close()
            print("\nDuckDB connection closed.")
