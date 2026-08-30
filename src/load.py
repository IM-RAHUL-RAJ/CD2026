from __future__ import annotations

from pathlib import Path
from datetime import datetime

import duckdb
import pandas as pd


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
        database_path: str | Path,
        schema_path: str | Path,
    ):
        self.database_path = Path(database_path)
        self.schema_path = Path(schema_path)

    # ================================================================
    # CONNECTION
    # ================================================================

    def connect(self):

        self.database_path.parent.mkdir(
            parents=True,
            exist_ok=True
        )

        return duckdb.connect(
            str(self.database_path)
        )

    # ================================================================
    # MAIN LOAD
    # ================================================================

    def load(
        self,
        transformed_folder: str | Path,
    ):

        transformed_folder = Path(transformed_folder)

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

            # --------------------------------------------------------
            # 1. CREATE SCHEMA FROM analytics-schema.sql
            # --------------------------------------------------------

            self.create_schema(conn)

            # --------------------------------------------------------
            # 2. READ ALL CSV FILES
            # --------------------------------------------------------

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

            # --------------------------------------------------------
            # 3. LOAD DIM_DATE
            # --------------------------------------------------------

            self.load_dim_date(
                conn,
                dataframes
            )

            # --------------------------------------------------------
            # 4. LOAD DIM_INSTRUMENT
            # --------------------------------------------------------

            self.load_dim_instrument(
                conn,
                dataframes
            )

            # --------------------------------------------------------
            # 5. LOAD DIM_ACCOUNT
            # --------------------------------------------------------

            self.load_dim_account(
                conn,
                dataframes
            )

            # --------------------------------------------------------
            # 6. LOAD FACT_TRADES
            # --------------------------------------------------------

            self.load_fact_trades(
                conn,
                dataframes
            )

            # --------------------------------------------------------
            # 7. VALIDATE DATABASE
            # --------------------------------------------------------

            self.validate_database(
                conn
            )

            # --------------------------------------------------------
            # 8. SUMMARY
            # --------------------------------------------------------

            self.print_summary(
                conn
            )

        finally:

            conn.close()

            print(
                "\nDuckDB connection closed."
            )

    # ================================================================
    # CREATE SCHEMA
    # ================================================================

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

    # ================================================================
    # PREPARE CSV
    # ================================================================

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

    # ================================================================
    # COLUMN HELPERS
    # ================================================================

    def find_column(
        self,
        df: pd.DataFrame,
        possible_names: list[str]
    ):

        for name in possible_names:

            if name.lower() in df.columns:
                return name.lower()

        return None

    # ================================================================
    # DIM_DATE
    # ================================================================

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
                "No usable dates found."
            )
            return

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

            day_of_week = (
                current_date.weekday() + 1
            )

            day_name = current_date.strftime(
                "%A"
            )

            month_name = current_date.strftime(
                "%B"
            )

            is_weekday = (
                current_date.weekday() < 5
            )

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
                    quarter,
                    day_of_week,
                    day_name,
                    month_name,
                    is_weekday
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    date_key,
                    current_date,
                    day,
                    month,
                    year,
                    quarter,
                    day_of_week,
                    day_name,
                    month_name,
                    is_weekday
                ]
            )

            inserted += 1

        print(
            f"Inserted: {inserted}"
        )

        print(
            f"Skipped duplicates: {skipped}"
        )

    # ================================================================
    # DIM_INSTRUMENT
    # ================================================================

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
                    f"{file.name}: "
                    "No instrument/symbol column found. "
                    "Skipping."
                )

                continue

            # --------------------------------------------------------
            # The schema requires:
            #
            # symbol
            # name
            # asset_class
            # currency
            # tradable
            #
            # If the CSV does not provide these required values,
            # we do NOT invent them.
            # --------------------------------------------------------

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

            tradable_column = self.find_column(
                df,
                [
                    "tradable"
                ]
            )

            required_missing = []

            if name_column is None:
                required_missing.append("name")

            if asset_class_column is None:
                required_missing.append("asset_class")

            if currency_column is None:
                required_missing.append("currency")

            if tradable_column is None:
                required_missing.append("tradable")

            if required_missing:

                print(
                    f"{file.name}: Cannot populate DIM_INSTRUMENT."
                )

                print(
                    "  Missing required CSV columns: "
                    + ", ".join(required_missing)
                )

                print(
                    "  No rows inserted into DIM_INSTRUMENT."
                )

                continue

            for _, row in df.iterrows():

                symbol = row[symbol_column]

                if pd.isna(symbol):
                    continue

                symbol = str(symbol).strip()

                if not symbol:
                    continue

                name = row[name_column]
                asset_class = row[asset_class_column]
                currency = row[currency_column]
                tradable = row[tradable_column]

                if (
                    pd.isna(name)
                    or pd.isna(asset_class)
                    or pd.isna(currency)
                    or pd.isna(tradable)
                ):
                    total_skipped += 1
                    continue

                existing = conn.execute(
                    """
                    SELECT 1
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

                exchange = None

                conn.execute(
                    """
                    INSERT INTO dim_instrument
                    (
                        instrument_key,
                        symbol,
                        name,
                        asset_class,
                        currency,
                        exchange,
                        tradable,
                        loaded_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    [
                        next_key,
                        symbol,
                        str(name),
                        str(asset_class),
                        str(currency),
                        exchange,
                        bool(tradable),
                        datetime.now()
                    ]
                )

                total_inserted += 1

        print(
            f"Inserted: {total_inserted}"
        )

        print(
            f"Skipped duplicates/incomplete rows: "
            f"{total_skipped}"
        )

    # ================================================================
    # DIM_ACCOUNT
    # ================================================================

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

            source_id_column = self.find_column(
                df,
                [
                    "source_id",
                    "account_source_id"
                ]
            )

            required_missing = []

            if account_id_column is None:
                required_missing.append("account_id")

            if holder_name_column is None:
                required_missing.append("holder_name")

            if status_column is None:
                required_missing.append("status")

            if effective_date_column is None:
                required_missing.append("effective_date")

            if source_id_column is None:
                required_missing.append("source_id")

            if required_missing:

                print(
                    f"{file.name}: Cannot populate DIM_ACCOUNT."
                )

                print(
                    "  Missing required CSV columns: "
                    + ", ".join(required_missing)
                )

                print(
                    "  No rows inserted into DIM_ACCOUNT."
                )

                continue

            for _, row in df.iterrows():

                account_id = row[account_id_column]
                holder_name = row[holder_name_column]
                status = row[status_column]
                effective_date = row[effective_date_column]
                source_id = row[source_id_column]

                if (
                    pd.isna(account_id)
                    or pd.isna(holder_name)
                    or pd.isna(status)
                    or pd.isna(effective_date)
                    or pd.isna(source_id)
                ):
                    total_skipped += 1
                    continue

                effective_date = pd.to_datetime(
                    effective_date,
                    errors="coerce"
                )

                if pd.isna(effective_date):
                    total_skipped += 1
                    continue

                effective_date = (
                    effective_date.date()
                )

                account_id = str(
                    account_id
                ).strip()

                # ----------------------------------------------------
                # Natural key for this Type 2 dimension:
                #
                # account_id + effective_date
                # ----------------------------------------------------

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

                conn.execute(
                    """
                    INSERT INTO dim_account
                    (
                        account_key,
                        account_id,
                        holder_name,
                        status,
                        effective_date,
                        end_date,
                        is_current,
                        source_id,
                        loaded_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    [
                        next_key,
                        account_id,
                        str(holder_name),
                        str(status),
                        effective_date,
                        None,
                        True,
                        int(source_id),
                        datetime.now()
                    ]
                )

                total_inserted += 1

        print(
            f"Inserted: {total_inserted}"
        )

        print(
            f"Skipped duplicates/incomplete rows: "
            f"{total_skipped}"
        )

    # ================================================================
    # FACT_TRADES
    # ================================================================

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
                [
                    "side"
                ]
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
                [
                    "status"
                ]
            )

            executed_price_column = self.find_column(
                df,
                [
                    "executed_price",
                    "execution_price"
                ]
            )

            required_missing = []

            if source_order_id_column is None:
                required_missing.append(
                    "source_order_id"
                )

            if symbol_column is None:
                required_missing.append(
                    "symbol"
                )

            if date_column is None:
                required_missing.append(
                    "created_at/date"
                )

            if account_id_column is None:
                required_missing.append(
                    "account_id"
                )

            if side_column is None:
                required_missing.append(
                    "side"
                )

            if quantity_column is None:
                required_missing.append(
                    "quantity"
                )

            if price_column is None:
                required_missing.append(
                    "price"
                )

            if status_column is None:
                required_missing.append(
                    "status"
                )

            if required_missing:

                print(
                    f"{file.name}: Cannot populate FACT_TRADES."
                )

                print(
                    "  Missing required CSV columns: "
                    + ", ".join(required_missing)
                )

                print(
                    "  No rows inserted into FACT_TRADES."
                )

                continue

            for _, row in df.iterrows():

                source_order_id = row[
                    source_order_id_column
                ]

                symbol = row[
                    symbol_column
                ]

                account_id = row[
                    account_id_column
                ]

                created_at = pd.to_datetime(
                    row[date_column],
                    errors="coerce"
                )

                if (
                    pd.isna(source_order_id)
                    or pd.isna(symbol)
                    or pd.isna(account_id)
                    or pd.isna(created_at)
                ):
                    total_skipped += 1
                    continue

                source_order_id = str(
                    source_order_id
                ).strip()

                symbol = str(
                    symbol
                ).strip()

                account_id = str(
                    account_id
                ).strip()

                # ----------------------------------------------------
                # Check if fact already exists.
                #
                # source_order_id is UNIQUE in the schema.
                # This makes the load idempotent.
                # ----------------------------------------------------

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

                # ----------------------------------------------------
                # Find instrument key
                # ----------------------------------------------------

                instrument_result = conn.execute(
                    """
                    SELECT instrument_key
                    FROM dim_instrument
                    WHERE symbol = ?
                    """,
                    [symbol]
                ).fetchone()

                if instrument_result is None:

                    print(
                        f"Skipping order {source_order_id}: "
                        f"instrument {symbol} not found."
                    )

                    total_skipped += 1
                    continue

                instrument_key = (
                    instrument_result[0]
                )

                # ----------------------------------------------------
                # Find date key
                # ----------------------------------------------------

                trade_date = created_at.date()

                date_key = int(
                    trade_date.strftime("%Y%m%d")
                )

                date_result = conn.execute(
                    """
                    SELECT date_key
                    FROM dim_date
                    WHERE full_date = ?
                    """,
                    [trade_date]
                ).fetchone()

                if date_result is None:

                    print(
                        f"Skipping order {source_order_id}: "
                        f"date {trade_date} not found."
                    )

                    total_skipped += 1
                    continue

                date_key = date_result[0]

                # ----------------------------------------------------
                # Find account key
                # ----------------------------------------------------

                account_result = conn.execute(
                    """
                    SELECT account_key
                    FROM dim_account
                    WHERE account_id = ?
                      AND is_current = TRUE
                    ORDER BY effective_date DESC
                    LIMIT 1
                    """,
                    [account_id]
                ).fetchone()

                if account_result is None:

                    print(
                        f"Skipping order {source_order_id}: "
                        f"account {account_id} not found."
                    )

                    total_skipped += 1
                    continue

                account_key = (
                    account_result[0]
                )

                # ----------------------------------------------------
                # Read fact values
                # ----------------------------------------------------

                side = row[
                    side_column
                ]

                quantity = row[
                    quantity_column
                ]

                price = row[
                    price_column
                ]

                status = row[
                    status_column
                ]

                if (
                    pd.isna(side)
                    or pd.isna(quantity)
                    or pd.isna(price)
                    or pd.isna(status)
                ):
                    total_skipped += 1
                    continue

                quantity = int(
                    quantity
                )

                price = float(
                    price
                )

                side = str(
                    side
                ).upper().strip()

                status = str(
                    status
                ).upper().strip()

                # ----------------------------------------------------
                # Validation
                # ----------------------------------------------------

                if quantity <= 0:
                    total_skipped += 1
                    continue

                if price <= 0:
                    total_skipped += 1
                    continue

                if side not in {
                    "BUY",
                    "SELL"
                }:
                    total_skipped += 1
                    continue

                if status not in {
                    "FILLED",
                    "CANCELLED",
                    "REJECTED",
                    "PENDING"
                }:
                    total_skipped += 1
                    continue

                # ----------------------------------------------------
                # Executed price
                # ----------------------------------------------------

                executed_price = None

                if executed_price_column is not None:

                    value = row[
                        executed_price_column
                    ]

                    if not pd.isna(value):
                        executed_price = float(
                            value
                        )

                # ----------------------------------------------------
                # Calculate trade value
                # ----------------------------------------------------

                if (
                    status == "FILLED"
                    and executed_price is not None
                ):

                    trade_value = (
                        quantity * executed_price
                    )

                else:

                    trade_value = (
                        quantity * price
                    )

                # ----------------------------------------------------
                # Generate surrogate trade key
                # ----------------------------------------------------

                trade_key = conn.execute(
                    """
                    SELECT COALESCE(
                        MAX(trade_key),
                        0
                    ) + 1
                    FROM fact_trades
                    """
                ).fetchone()[0]

                # ----------------------------------------------------
                # INSERT FACT
                # ----------------------------------------------------

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
                        executed_price,
                        trade_value,
                        source_order_id,
                        created_at,
                        loaded_at
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?
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
                        executed_price,
                        trade_value,
                        source_order_id,
                        created_at.to_pydatetime(),
                        datetime.now()
                    ]
                )

                total_inserted += 1

        print(
            f"Inserted: {total_inserted}"
        )

        print(
            f"Skipped duplicates/unresolved rows: "
            f"{total_skipped}"
        )

    # ================================================================
    # VALIDATION
    # ================================================================

    def validate_database(
        self,
        conn
    ):

        print(
            "\n========== VALIDATION =========="
        )

        # ------------------------------------------------------------
        # Check required tables
        # ------------------------------------------------------------

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

        print(
            "✓ All required tables exist."
        )

        # ------------------------------------------------------------
        # Check duplicate instruments
        # ------------------------------------------------------------

        duplicate_instruments = conn.execute(
            """
            SELECT symbol, COUNT(*)
            FROM dim_instrument
            GROUP BY symbol
            HAVING COUNT(*) > 1
            """
        ).fetchall()

        if duplicate_instruments:

            raise TradingLoadError(
                "Duplicate instruments detected."
            )

        print(
            "✓ DIM_INSTRUMENT has no duplicate symbols."
        )

        # ------------------------------------------------------------
        # Check duplicate dates
        # ------------------------------------------------------------

        duplicate_dates = conn.execute(
            """
            SELECT full_date, COUNT(*)
            FROM dim_date
            GROUP BY full_date
            HAVING COUNT(*) > 1
            """
        ).fetchall()

        if duplicate_dates:

            raise TradingLoadError(
                "Duplicate dates detected."
            )

        print(
            "✓ DIM_DATE has no duplicate dates."
        )

        # ------------------------------------------------------------
        # Check duplicate account versions
        # ------------------------------------------------------------

        duplicate_accounts = conn.execute(
            """
            SELECT
                account_id,
                effective_date,
                COUNT(*)
            FROM dim_account
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

        print(
            "✓ DIM_ACCOUNT has no overlapping versions."
        )

        # ------------------------------------------------------------
        # Check duplicate source orders
        # ------------------------------------------------------------

        duplicate_orders = conn.execute(
            """
            SELECT
                source_order_id,
                COUNT(*)
            FROM fact_trades
            GROUP BY source_order_id
            HAVING COUNT(*) > 1
            """
        ).fetchall()

        if duplicate_orders:

            raise TradingLoadError(
                "Duplicate FACT_TRADES orders detected."
            )

        print(
            "✓ FACT_TRADES has no duplicate orders."
        )

        # ------------------------------------------------------------
        # Check unresolved foreign keys
        # ------------------------------------------------------------

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

            WHERE a.account_key IS NULL
               OR i.instrument_key IS NULL
               OR d.date_key IS NULL
            """
        ).fetchone()[0]

        if unresolved:

            raise TradingLoadError(
                f"{unresolved} FACT_TRADES rows "
                "have unresolved dimension keys."
            )

        print(
            "✓ All FACT_TRADES foreign keys resolve."
        )

        # ------------------------------------------------------------
        # Check positive quantity and price
        # ------------------------------------------------------------

        invalid_values = conn.execute(
            """
            SELECT COUNT(*)
            FROM fact_trades
            WHERE quantity <= 0
               OR price <= 0
            """
        ).fetchone()[0]

        if invalid_values:

            raise TradingLoadError(
                f"{invalid_values} FACT_TRADES rows "
                "have invalid quantity/price."
            )

        print(
            "✓ FACT_TRADES quantity and price are valid."
        )

        # ------------------------------------------------------------
        # Check trade value
        # ------------------------------------------------------------

        invalid_trade_values = conn.execute(
            """
            SELECT COUNT(*)
            FROM fact_trades
            WHERE trade_value <>
                CASE
                    WHEN status = 'FILLED'
                         AND executed_price IS NOT NULL
                    THEN quantity * executed_price
                    ELSE quantity * price
                END
            """
        ).fetchone()[0]

        if invalid_trade_values:

            raise TradingLoadError(
                f"{invalid_trade_values} FACT_TRADES rows "
                "have invalid trade_value."
            )

        print(
            "✓ FACT_TRADES trade_value is valid."
        )

        print(
            "================================"
        )
        print(
            "VALIDATION PASSED"
        )
        print(
            "================================"
        )

    # ================================================================
    # SUMMARY
    # ================================================================

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

    loader = TradingLoader(
        database_path="analytics.duckdb",
        schema_path="../contracts/analytics-schema.sql"
    )

    loader.load(
        "../transformed"
    )
