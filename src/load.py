from __future__ import annotations

from pathlib import Path
from datetime import datetime

import duckdb
import pandas as pd



# Seed data for not available values


SEED_DATE = datetime(2025, 1, 1).date()

SEED_ACCOUNT = {
    "account_id": "ACC001",
    "holder_name": "Default Trading Account",
    "status": "ACTIVE",
    "effective_date": SEED_DATE,
}

SEED_INSTRUMENTS = {
    "AAPL": {
        "name": "Apple Inc.",
        "asset_class": "EQUITY",
        "currency": "USD",
    },
    "MSFT": {
        "name": "Microsoft Corporation",
        "asset_class": "EQUITY",
        "currency": "USD",
    },
    "GOOGL": {
        "name": "Alphabet Inc.",
        "asset_class": "EQUITY",
        "currency": "USD",
    },
    "AMZN": {
        "name": "Amazon.com Inc.",
        "asset_class": "EQUITY",
        "currency": "USD",
    },
    "TSLA": {
        "name": "Tesla Inc.",
        "asset_class": "EQUITY",
        "currency": "USD",
    },
}

DEFAULT_INSTRUMENT = {
    "name": "Unknown Instrument",
    "asset_class": "EQUITY",
    "currency": "USD",
}

SEED_FACT = {
    "side": "BUY",
    "quantity": 100,
    "price": 100.00,
    "status": "FILLED",
}

class TradingLoadError(Exception):
    pass


class TradingLoader:

    EXPECTED_TABLES = {
        "dim_account",
        "dim_instrument",
        "dim_date",
        "fact_trades",
    }

    def __init__(
        self,
        database_path=None,
        schema_path=None
    ):
        BASE_DIR = Path(__file__).resolve().parent.parent

        self.database_path = (
            BASE_DIR / "analytics.duckdb"
            if database_path is None
            else Path(database_path).resolve()
        )

        self.schema_path = (
            Path(__file__).resolve().parent / "analytics-schema.sql"
            if schema_path is None
            else Path(schema_path).resolve()
        )  

    

    #Connecting to the duckdb database
    def connect(self):

        self.database_path.parent.mkdir(
            parents=True,
            exist_ok=True
        )

        return duckdb.connect(
            str(self.database_path)
        )

    
    #load function - main
    def load(
        self,
        transformed_folder: str | Path = None,
    ):

        BASE_DIR = Path(__file__).resolve().parent.parent

        if transformed_folder is None:
            transformed_folder = BASE_DIR / "transformed"
        else:
            transformed_folder = Path(transformed_folder).resolve()

        if not transformed_folder.exists():
            raise FileNotFoundError(
                f"Transform folder missing: {transformed_folder}"
            )

        csv_files = sorted(
            transformed_folder.glob("*.csv")
        )

        if not csv_files:
            raise TradingLoadError(
                "No transformed CSV files found."
            )

        print("\n========================================")
        print("        TRADING ETL LOAD START")
        print("========================================")

        print(f"\nFound {len(csv_files)} CSV file(s):")

        for file in csv_files:
            print(f"  - {file.name}")

        conn = self.connect()

        try:
            self.create_schema(conn)

            dataframes = []

            for file in csv_files:

                print(
                    f"\nReading CSV: {file.name}"
                )

                df = pd.read_csv(file)

                df = self.prepare_dataframe(df)

                if df.empty:
                    print("  File is empty. Skipping.")
                    continue

                print(
                    f"  Rows found: {len(df)}"
                )

                print(
                    f"  Columns: {', '.join(df.columns)}"
                )

                dataframes.append(
                    (file, df)
                )

            if not dataframes:
                print(
                    "\nNo usable data found in CSV files."
                )
                self.print_summary(conn)
                return

            self.load_dim_date(
                conn,
                dataframes
            )

            self.load_dim_instrument(
                conn,
                dataframes
            )

            self.load_dim_account(
                conn,
                dataframes
            )

            self.load_fact_trades(
                conn,
                dataframes
            )

            self.validate_database(
                conn
            )

            self.print_summary(
                conn
            )

        finally:

            conn.close()

            print(
                "\nDuckDB connection closed."
            )

    
    #Schema from the analytics-schema file
    def create_schema(
        self,
        conn
    ):

        if not self.schema_path.exists():
            raise FileNotFoundError(
                f"Schema file missing: {self.schema_path}"
            )

        print(
            "\nCreating/verifying analytical schema..."
        )

        schema_sql = self.schema_path.read_text(
            encoding="utf-8"
        )

        conn.execute(
            schema_sql
        )

        print(
            "Schema created/verified successfully."
        )


    def prepare_dataframe(
        self,
        df: pd.DataFrame
    ):

        df = df.copy()

        # Remove unnamed CSV index columns
        df = df.loc[
            :,
            ~df.columns.str.contains(
                "^Unnamed",
                case=False
            )
        ]

        # Normalize column names
        df.columns = [
            str(column).strip().lower()
            for column in df.columns
        ]

        return df
    

    def find_column(
        self,
        df: pd.DataFrame,
        possible_names: list[str]
    ):

        for name in possible_names:

            if name.lower() in df.columns:
                return name.lower()

        return None


    def get_value(
        self,
        row: pd.Series,
        column: str | None,
    ):

        if column is None or column not in row.index:
            return None

        value = row[column]

        if pd.isna(value):
            return None

        if isinstance(value, str):
            value = value.strip()
            if value == "":
                return None

        return value

    def to_int_or_none(self, value):
        if value is None:
            return None
        try:
            if pd.isna(value):
                return None
            return int(float(value))
        except (TypeError, ValueError, OverflowError):
            return None

    def to_float_or_none(self, value):
        """Convert a value to float, returning None when unavailable/invalid."""
        if value is None:
            return None
        try:
            if pd.isna(value):
                return None
            return float(value)
        except (TypeError, ValueError, OverflowError):
            return None

    def clean_text_or_none(self, value):
        if value is None:
            return None
        if isinstance(value, str):
            value = value.strip()
            return value if value else None
        if pd.isna(value):
            return None
        return str(value).strip() or None


    #seed data for other tables
    def seed_instrument_for_symbol(self, symbol):
        symbol = self.clean_text_or_none(symbol)

        if symbol is None:
            return DEFAULT_INSTRUMENT.copy()

        return {
            **DEFAULT_INSTRUMENT,
            **SEED_INSTRUMENTS.get(symbol.upper(), {}),
        }

    def infer_symbol_from_file(self, file):
        symbol = file.stem.strip().upper()
        return symbol if symbol else "AAPL"

    def get_or_create_seed_instrument(self, conn, symbol):
        symbol = self.clean_text_or_none(symbol) or "AAPL"
        symbol = symbol.upper()
        seed = self.seed_instrument_for_symbol(symbol)

        existing = conn.execute(
            """
            SELECT instrument_key
            FROM dim_instrument
            WHERE symbol = ?
            """,
            [symbol],
        ).fetchone()

        if existing:
            return existing[0]

        next_key = conn.execute(
            """
            SELECT COALESCE(MAX(instrument_key), 0) + 1
            FROM dim_instrument
            """
        ).fetchone()[0]

        conn.execute(
            """
            INSERT INTO dim_instrument
            (
                instrument_key,
                symbol,
                name,
                asset_class,
                currency
            )
            VALUES (?, ?, ?, ?, ?)
            """,
            [
                next_key,
                symbol,
                seed["name"],
                seed["asset_class"],
                seed["currency"],
            ],
        )

        print(f"  Seed instrument created: {symbol}")
        return next_key

    def get_or_create_seed_account(self, conn):
        """Create the fallback account if it is not already present."""
        account_id = SEED_ACCOUNT["account_id"]
        effective_date = SEED_ACCOUNT["effective_date"]

        existing = conn.execute(
            """
            SELECT account_key
            FROM dim_account
            WHERE account_id = ?
              AND effective_date = ?
            """,
            [account_id, effective_date],
        ).fetchone()

        if existing:
            return existing[0]

        next_key = conn.execute(
            """
            SELECT COALESCE(MAX(account_key), 0) + 1
            FROM dim_account
            """
        ).fetchone()[0]

        conn.execute(
            """
            INSERT INTO dim_account
            (
                account_key,
                account_id,
                holder_name,
                status,
                effective_date
            )
            VALUES (?, ?, ?, ?, ?)
            """,
            [
                next_key,
                account_id,
                SEED_ACCOUNT["holder_name"],
                SEED_ACCOUNT["status"],
                effective_date,
            ],
        )

        print(f"  Seed account created: {account_id}")
        return next_key

    def get_or_create_date(self, conn, current_date):
        current_date = current_date or SEED_DATE

        existing = conn.execute(
            """
            SELECT date_key
            FROM dim_date
            WHERE full_date = ?
            """,
            [current_date],
        ).fetchone()

        if existing:
            return existing[0]

        date_key = int(current_date.strftime("%Y%m%d"))
        day = current_date.day
        month = current_date.month
        year = current_date.year
        quarter = ((month - 1) // 3) + 1

        conn.execute(
            """
            INSERT INTO dim_date
            (
                date_key,
                full_date,
                day,
                month,
                year,
                quarter
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            [
                date_key,
                current_date,
                day,
                month,
                year,
                quarter,
            ],
        )

        return date_key

    def load_dim_date(
        self,
        conn,
        dataframes
    ):

        print(
            "\n---------- DIM_DATE ----------"
        )

        all_dates = []

        for file, df in dataframes:

            date_column = self.find_column(
                df,
                [
                    "date",
                    "datetime",
                    "timestamp",
                    "date_time",
                    "created_at",
                    "created_on"
                ]
            )

            if date_column is None:

                print(
                    f"{file.name}: "
                    "No date column found. Skipping."
                )

                continue

            dates = pd.to_datetime(
                df[date_column],
                errors="coerce"
            ).dt.date

            dates = dates.dropna()

            all_dates.extend(
                dates.tolist()
            )

        if not all_dates:
            print(
                f"No usable dates found. Using seed date: {SEED_DATE}"
            )
            all_dates.append(SEED_DATE)

        unique_dates = sorted(
            set(all_dates)
        )

        inserted = 0
        skipped = 0

        for current_date in unique_dates:

            date_key = int(
                current_date.strftime("%Y%m%d")
            )

            day = current_date.day
            month = current_date.month
            year = current_date.year

            quarter = (
                (month - 1) // 3
            ) + 1


            existing = conn.execute(
                """
                SELECT 1
                FROM dim_date
                WHERE full_date = ?
                """,
                [current_date]
            ).fetchone()

            if existing:
                skipped += 1
                continue

            conn.execute(
                """
                INSERT INTO dim_date
                (
                    date_key,
                    full_date,
                    day,
                    month,
                    year,
                    quarter
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                [
                    date_key,
                    current_date,
                    day,
                    month,
                    year,
                    quarter
                ]
            )

            inserted += 1

        print(
            f"Inserted: {inserted}"
        )

        print(
            f"Skipped duplicates: {skipped}"
        )


    def load_dim_instrument(
        self,
        conn,
        dataframes
    ):

        print(
            "\n------ DIM_INSTRUMENT ------"
        )

        total_inserted = 0
        total_skipped = 0

        for file, df in dataframes:

            symbol_column = self.find_column(
                df,
                [
                    "symbol",
                    "ticker",
                    "instrument"
                ]
            )

            if symbol_column is None:
                print(
                    f"{file.name}: No symbol/instrument column found. "
                    "Using the CSV filename as the instrument symbol."
                )

            name_column = self.find_column(
                df,
                [
                    "name",
                    "instrument_name",
                    "company_name"
                ]
            )

            asset_class_column = self.find_column(
                df,
                [
                    "asset_class",
                    "asset_type"
                ]
            )

            currency_column = self.find_column(
                df,
                [
                    "currency",
                    "currency_code"
                ]
            )

            for _, row in df.iterrows():

                symbol = self.clean_text_or_none(
                    self.get_value(row, symbol_column)
                )

                if symbol is None:
                    symbol = self.infer_symbol_from_file(file)

                symbol = symbol.upper()
                seed = self.seed_instrument_for_symbol(symbol)

                name = self.clean_text_or_none(
                    self.get_value(row, name_column)
                )
                if name is None:
                    name = seed["name"]

                asset_class = self.clean_text_or_none(
                    self.get_value(row, asset_class_column)
                )
                if asset_class is None:
                    asset_class = seed["asset_class"]

                currency = self.clean_text_or_none(
                    self.get_value(row, currency_column)
                )
                if currency is None:
                    currency = seed["currency"]

                existing = conn.execute(
                    """
                    SELECT instrument_key
                    FROM dim_instrument
                    WHERE symbol = ?
                    """,
                    [symbol]
                ).fetchone()

                if existing:
                    total_skipped += 1
                    continue

                next_key = conn.execute(
                    """
                    SELECT COALESCE(
                        MAX(instrument_key),
                        0
                    ) + 1
                    FROM dim_instrument
                    """
                ).fetchone()[0]

                try:
                    conn.execute(
                        """
                        INSERT INTO dim_instrument
                        (
                            instrument_key,
                            symbol,
                            name,
                            asset_class,
                            currency
                        )
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        [
                            next_key,
                            symbol,
                            name,
                            asset_class,
                            currency
                        ]
                    )
                    total_inserted += 1

                except Exception as exc:
                    print(
                        f"  {file.name}: could not insert instrument "
                        f"{symbol!r}: {exc}. Continuing."
                    )
                    total_skipped += 1

        print(f"Inserted: {total_inserted}")
        print(
            f"Skipped duplicates/unusable rows: {total_skipped}"
        )


    def load_dim_account(
        self,
        conn,
        dataframes
    ):

        print(
            "\n--------- DIM_ACCOUNT ---------"
        )

        total_inserted = 0
        total_skipped = 0

        for file, df in dataframes:

            account_id_column = self.find_column(
                df,
                [
                    "account_id",
                    "account"
                ]
            )

            if account_id_column is None:
                print(
                    f"{file.name}: No account_id/account column found. "
                    "Using the seed account."
                )

            holder_name_column = self.find_column(
                df,
                [
                    "holder_name",
                    "account_name",
                    "name"
                ]
            )

            status_column = self.find_column(
                df,
                [
                    "status",
                    "account_status"
                ]
            )

            effective_date_column = self.find_column(
                df,
                [
                    "effective_date",
                    "date",
                    "created_at",
                    "created_on"
                ]
            )

            for _, row in df.iterrows():

                account_id = self.clean_text_or_none(
                    self.get_value(row, account_id_column)
                )
                if account_id is None:
                    account_id = SEED_ACCOUNT["account_id"]

                holder_name = self.clean_text_or_none(
                    self.get_value(row, holder_name_column)
                )
                if holder_name is None:
                    holder_name = SEED_ACCOUNT["holder_name"]

                status = self.clean_text_or_none(
                    self.get_value(row, status_column)
                )
                if status is None:
                    status = SEED_ACCOUNT["status"]

                effective_date_value = self.get_value(
                    row,
                    effective_date_column
                )
                effective_date = None

                if effective_date_value is not None:
                    parsed_date = pd.to_datetime(
                        effective_date_value,
                        errors="coerce"
                    )
                    if not pd.isna(parsed_date):
                        effective_date = parsed_date.date()

                if effective_date is None:
                    effective_date = SEED_ACCOUNT["effective_date"]

                existing = None
                if effective_date is not None:
                    existing = conn.execute(
                        """
                        SELECT 1
                        FROM dim_account
                        WHERE account_id = ?
                          AND effective_date = ?
                        """,
                        [
                            account_id,
                            effective_date
                        ]
                    ).fetchone()

                if existing:
                    total_skipped += 1
                    continue

                next_key = conn.execute(
                    """
                    SELECT COALESCE(
                        MAX(account_key),
                        0
                    ) + 1
                    FROM dim_account
                    """
                ).fetchone()[0]

                try:
                    conn.execute(
                        """
                        INSERT INTO dim_account
                        (
                            account_key,
                            account_id,
                            holder_name,
                            status,
                            effective_date
                        )
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        [
                            next_key,
                            account_id,
                            holder_name,
                            status,
                            effective_date
                        ]
                    )
                    total_inserted += 1

                except Exception as exc:
                    print(
                        f"  {file.name}: could not insert account "
                        f"{account_id!r}: {exc}. Continuing."
                    )
                    total_skipped += 1

        print(f"Inserted: {total_inserted}")
        print(
            f"Skipped duplicates/unusable rows: {total_skipped}"
        )


    def load_fact_trades(
        self,
        conn,
        dataframes
    ):

        print(
            "\n---------- FACT_TRADES ----------"
        )

        total_inserted = 0
        total_skipped = 0

        for file, df in dataframes:

            source_order_id_column = self.find_column(
                df,
                [
                    "source_order_id",
                    "order_id",
                    "trade_id",
                    "id"
                ]
            )

            symbol_column = self.find_column(
                df,
                [
                    "symbol",
                    "ticker",
                    "instrument"
                ]
            )

            date_column = self.find_column(
                df,
                [
                    "date",
                    "datetime",
                    "timestamp",
                    "created_at",
                    "created_on"
                ]
            )

            account_id_column = self.find_column(
                df,
                [
                    "account_id",
                    "account"
                ]
            )

            side_column = self.find_column(
                df,
                ["side"]
            )

            quantity_column = self.find_column(
                df,
                [
                    "quantity",
                    "qty",
                    "volume"
                ]
            )

            price_column = self.find_column(
                df,
                [
                    "price",
                    "close"
                ]
            )

            status_column = self.find_column(
                df,
                ["status"]
            )


            for row_number, (_, row) in enumerate(
                df.iterrows(),
                start=1
            ):

                source_order_id = self.clean_text_or_none(
                    self.get_value(row, source_order_id_column)
                )

                if source_order_id is None:
                    source_order_id = (
                        f"SEED-{file.stem.upper()}-{row_number:06d}"
                    )

                symbol = self.clean_text_or_none(
                    self.get_value(row, symbol_column)
                )

                if symbol is None:
                    symbol = self.infer_symbol_from_file(file)

                symbol = symbol.upper()

                account_id = self.clean_text_or_none(
                    self.get_value(row, account_id_column)
                )

                if account_id is None:
                    account_id = SEED_ACCOUNT["account_id"]

                created_at = None
                created_at_value = self.get_value(row, date_column)

                if created_at_value is not None:
                    parsed_datetime = pd.to_datetime(
                        created_at_value,
                        errors="coerce"
                    )
                    if not pd.isna(parsed_datetime):
                        created_at = parsed_datetime.to_pydatetime()

                existing_fact = None
                if source_order_id is not None:
                    existing_fact = conn.execute(
                        """
                        SELECT 1
                        FROM fact_trades
                        WHERE source_order_id = ?
                        """,
                        [source_order_id]
                    ).fetchone()

                if existing_fact:
                    total_skipped += 1
                    continue


                instrument_key = None
                if symbol is not None:
                    instrument_result = conn.execute(
                        """
                        SELECT instrument_key
                        FROM dim_instrument
                        WHERE symbol = ?
                        """,
                        [symbol]
                    ).fetchone()

                    if instrument_result is not None:
                        instrument_key = instrument_result[0]
                    else:
                        instrument_key = self.get_or_create_seed_instrument(
                            conn,
                            symbol
                        )

                if instrument_key is None:
                    instrument_key = self.get_or_create_seed_instrument(
                        conn,
                        "AAPL"
                    )

                date_key = None
                if created_at is not None:
                    trade_date = created_at.date()

                    date_result = conn.execute(
                        """
                        SELECT date_key
                        FROM dim_date
                        WHERE full_date = ?
                        """,
                        [trade_date]
                    ).fetchone()

                    if date_result is not None:
                        date_key = date_result[0]
                    else:
                        date_key = self.get_or_create_date(
                            conn,
                            trade_date
                        )
                else:
                    created_at = datetime.combine(
                        SEED_DATE,
                        datetime.min.time()
                    )
                    date_key = self.get_or_create_date(
                        conn,
                        SEED_DATE
                    )

                if date_key is None:
                    date_key = self.get_or_create_date(
                        conn,
                        SEED_DATE
                    )

                account_key = None
                if account_id is not None:
                    account_result = conn.execute(
                        """
                        SELECT account_key
                        FROM dim_account
                        WHERE account_id = ?
                        ORDER BY effective_date DESC NULLS LAST
                        LIMIT 1
                        """,
                        [account_id]
                    ).fetchone()

                    if account_result is not None:
                        account_key = account_result[0]
                    else:
                        account_key = self.get_or_create_seed_account(conn)

                if account_key is None:
                    account_key = self.get_or_create_seed_account(conn)


                side = self.clean_text_or_none(
                    self.get_value(row, side_column)
                )
                if side is None:
                    side = SEED_FACT["side"]
                else:
                    side = side.upper()

                status = self.clean_text_or_none(
                    self.get_value(row, status_column)
                )
                if status is None:
                    status = SEED_FACT["status"]
                else:
                    status = status.upper()

                quantity = self.to_int_or_none(
                    self.get_value(row, quantity_column)
                )
                if quantity is None:
                    quantity = SEED_FACT["quantity"]

                price = self.to_float_or_none(
                    self.get_value(row, price_column)
                )
                if price is None:
                    price = SEED_FACT["price"]


                if quantity is not None and quantity <= 0:
                    quantity = None

                if price is not None and price <= 0:
                    price = None


                trade_key = conn.execute(
                    """
                    SELECT COALESCE(
                        MAX(trade_key),
                        0
                    ) + 1
                    FROM fact_trades
                    """
                ).fetchone()[0]

                try:
                    conn.execute(
                        """
                        INSERT INTO fact_trades
                        (
                            trade_key,
                            account_key,
                            instrument_key,
                            date_key,
                            side,
                            quantity,
                            price,
                            status,
                            source_order_id
                        )
                        VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?
                        )
                        """,
                        [
                            trade_key,
                            account_key,
                            instrument_key,
                            date_key,
                            side,
                            quantity,
                            price,
                            status,
                            source_order_id
                        ]
                    )

                    total_inserted += 1

                except Exception as exc:
                    print(
                        f"  {file.name}: could not insert fact row "
                        f"{source_order_id!r}: {exc}. Continuing."
                    )
                    total_skipped += 1

        print(f"Inserted: {total_inserted}")
        print(
            f"Skipped duplicates/uninsertable rows: {total_skipped}"
        )



    #validations - db side
    def validate_database(
        self,
        conn
    ):

        print(
            "\n========== VALIDATION =========="
        )

        #check required tables
        tables = conn.execute(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'main'
            """
        ).fetchall()

        existing_tables = {
            row[0].lower()
            for row in tables
        }

        missing_tables = (
            self.EXPECTED_TABLES
            - existing_tables
        )

        if missing_tables:
            raise TradingLoadError(
                "Missing analytical tables: "
                + ", ".join(
                    sorted(missing_tables)
                )
            )

        print("✓ All required tables exist.")

        #duplicate checks
        duplicate_instruments = conn.execute(
            """
            SELECT symbol, COUNT(*)
            FROM dim_instrument
            WHERE symbol IS NOT NULL
            GROUP BY symbol
            HAVING COUNT(*) > 1
            """
        ).fetchall()

        if duplicate_instruments:
            raise TradingLoadError(
                "Duplicate instruments detected."
            )

        print("✓ DIM_INSTRUMENT has no duplicate non-blank symbols.")

        duplicate_dates = conn.execute(
            """
            SELECT full_date, COUNT(*)
            FROM dim_date
            WHERE full_date IS NOT NULL
            GROUP BY full_date
            HAVING COUNT(*) > 1
            """
        ).fetchall()

        if duplicate_dates:
            raise TradingLoadError(
                "Duplicate dates detected."
            )

        print("✓ DIM_DATE has no duplicate non-blank dates.")

        duplicate_accounts = conn.execute(
            """
            SELECT
                account_id,
                effective_date,
                COUNT(*)
            FROM dim_account
            WHERE account_id IS NOT NULL
            GROUP BY
                account_id,
                effective_date
            HAVING COUNT(*) > 1
            """
        ).fetchall()

        if duplicate_accounts:
            raise TradingLoadError(
                "Duplicate account versions detected."
            )

        print("✓ DIM_ACCOUNT has no duplicate account versions.")

        duplicate_orders = conn.execute(
            """
            SELECT
                source_order_id,
                COUNT(*)
            FROM fact_trades
            WHERE source_order_id IS NOT NULL
            GROUP BY source_order_id
            HAVING COUNT(*) > 1
            """
        ).fetchall()

        if duplicate_orders:
            raise TradingLoadError(
                "Duplicate FACT_TRADES orders detected."
            )

        print("✓ FACT_TRADES has no duplicate non-blank orders.")


        unresolved = conn.execute(
            """
            SELECT COUNT(*)
            FROM fact_trades f

            LEFT JOIN dim_account a
                ON f.account_key = a.account_key

            LEFT JOIN dim_instrument i
                ON f.instrument_key = i.instrument_key

            LEFT JOIN dim_date d
                ON f.date_key = d.date_key

            WHERE (f.account_key IS NOT NULL AND a.account_key IS NULL)
               OR (f.instrument_key IS NOT NULL AND i.instrument_key IS NULL)
               OR (f.date_key IS NOT NULL AND d.date_key IS NULL)
            """
        ).fetchone()[0]

        if unresolved:
            raise TradingLoadError(
                f"{unresolved} FACT_TRADES rows "
                "have unresolved non-NULL dimension keys."
            )

        print("✓ All non-blank FACT_TRADES foreign keys resolve.")


        blank_counts = conn.execute(
            """
            SELECT
                COUNT(*) FILTER (WHERE side IS NULL) AS blank_side,
                COUNT(*) FILTER (WHERE quantity IS NULL) AS blank_quantity,
                COUNT(*) FILTER (WHERE price IS NULL) AS blank_price,
                COUNT(*) FILTER (WHERE status IS NULL) AS blank_status,
                COUNT(*) FILTER (WHERE account_key IS NULL) AS blank_account_key,
                COUNT(*) FILTER (WHERE instrument_key IS NULL) AS blank_instrument_key,
                COUNT(*) FILTER (WHERE date_key IS NULL) AS blank_date_key,
                COUNT(*) FILTER (WHERE source_order_id IS NULL) AS blank_source_order_id
            FROM fact_trades
            """
        ).fetchone()

        labels = [
            "side",
            "quantity",
            "price",
            "status",
            "account_key",
            "instrument_key",
            "date_key",
            "source_order_id",
        ]

        print("Blank FACT_TRADES fields:")
        for label, count in zip(labels, blank_counts):
            print(f"  {label:18} : {count}")

        invalid_values = conn.execute(
            """
            SELECT COUNT(*)
            FROM fact_trades
            WHERE quantity <= 0
               OR price <= 0
            """
        ).fetchone()[0]

        print(
            f"Non-positive quantity/price rows: "
            f"{invalid_values}"
        )

        print("================================")
        print("VALIDATION PASSED")
        print("Seed fallback data was used only where CSV values were missing.")
        print("================================")


    def print_summary(
        self,
        conn
    ):

        print(
            "\n========== LOAD SUMMARY =========="
        )

        for table in [
            "dim_account",
            "dim_instrument",
            "dim_date",
            "fact_trades"
        ]:

            count = conn.execute(
                f"""
                SELECT COUNT(*)
                FROM {table}
                """
            ).fetchone()[0]

            print(
                f"{table.upper():20} : {count} rows"
            )

        print(
            "==================================\n"
        )


# ====================================================================
# EXAMPLE USAGE
# ====================================================================

if __name__ == "__main__":

    loader = TradingLoader()

    loader.load()