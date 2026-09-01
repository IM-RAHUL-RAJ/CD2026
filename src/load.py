from __future__ import annotations

from pathlib import Path
import duckdb
import pandas as pd


class TradingLoader:

    def __init__(
        self,
        database_path: str | Path | None = None,
    ):
        # ============================================================
        # PROJECT PATHS
        # ============================================================

        # Directory containing this load.py file.
        #
        # Example:
        #
        # project/
        # ├── src/
        # │   ├── load.py
        # │   └── analytics-schema.sql
        # └── transformed/
        #
        # self.src_dir =
        # project/src
        #
        self.src_dir = Path(__file__).resolve().parent


        # ------------------------------------------------------------
        # Transformed folder
        # ------------------------------------------------------------
        #
        # "transformed" is a SIBLING of "src", therefore:
        #
        # self.src_dir.parent / "transformed"
        #

        self.transformed_folder = (
            self.src_dir.parent / "transformed"
        )


        # ------------------------------------------------------------
        # Analytics schema
        # ------------------------------------------------------------
        #
        # analytics-schema.sql is INSIDE src.
        #

        self.schema_path = (
            self.src_dir / "analytics-schema.sql"
        )


        # ------------------------------------------------------------
        # Database
        # ------------------------------------------------------------

        if database_path is None:

            self.database_path = (
                self.src_dir / "trading.duckdb"
            )

        else:

            self.database_path = Path(
                database_path
            ).resolve()


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
    # LOAD
    # ================================================================

    def load(self):

        # ------------------------------------------------------------
        # Validate transformed folder
        # ------------------------------------------------------------

        if not self.transformed_folder.exists():

            raise FileNotFoundError(
                f"Transform folder missing: "
                f"{self.transformed_folder}"
            )


        if not self.transformed_folder.is_dir():

            raise NotADirectoryError(
                f"Transform path is not a directory: "
                f"{self.transformed_folder}"
            )


        # ------------------------------------------------------------
        # Find all CSV files
        # ------------------------------------------------------------

        csv_files = sorted(
            self.transformed_folder.glob("*.csv")
        )


        if not csv_files:

            raise ValueError(
                f"No transformed CSV files found in: "
                f"{self.transformed_folder}"
            )


        print(
            "\n========== ETL LOAD =========="
        )

        print(
            f"Source directory : {self.src_dir}"
        )

        print(
            f"Schema file      : {self.schema_path}"
        )

        print(
            f"Transformed      : {self.transformed_folder}"
        )

        print(
            f"Database         : {self.database_path}"
        )

        print(
            f"CSV files found  : {len(csv_files)}"
        )

        print(
            "=============================="
        )


        # ------------------------------------------------------------
        # Validate schema
        # ------------------------------------------------------------

        if not self.schema_path.exists():

            raise FileNotFoundError(
                f"Analytics schema not found: "
                f"{self.schema_path}"
            )


        conn = self.connect()


        try:

            # --------------------------------------------------------
            # Load analytics schema
            # --------------------------------------------------------

            self.load_schema(
                conn
            )


            # --------------------------------------------------------
            # Process every transformed CSV
            # --------------------------------------------------------

            for file in csv_files:

                print(
                    f"\nProcessing {file.name}"
                )


                df = pd.read_csv(
                    file
                )


                if df.empty:

                    print(
                        f"Skipping empty file: "
                        f"{file.name}"
                    )

                    continue


                df = self.prepare_dataframe(
                    df
                )


                self.create_table_if_not_exists(
                    conn,
                    df
                )


                self.insert_unique_rows(
                    conn,
                    df
                )


            self.print_summary(
                conn
            )


        finally:

            conn.close()


    # ================================================================
    # LOAD SQL SCHEMA
    # ================================================================

    def load_schema(
        self,
        conn
    ):

        """
        Read and execute analytics-schema.sql.

        The schema path is resolved relative to load.py,
        so the pipeline does not depend on the current
        working directory.
        """

        schema_sql = self.schema_path.read_text(
            encoding="utf-8"
        )


        if not schema_sql.strip():

            raise ValueError(
                f"Analytics schema is empty: "
                f"{self.schema_path}"
            )


        conn.execute(
            schema_sql
        )


        print(
            f"Loaded schema: "
            f"{self.schema_path.name}"
        )


    # ================================================================
    # CLEAN INPUT
    # ================================================================

    def prepare_dataframe(
        self,
        df: pd.DataFrame
    ):

        df = df.copy()


        # Remove automatically generated CSV index columns.

        df = df.loc[
            :,
            ~df.columns.astype(str).str.contains(
                "^Unnamed"
            )
        ]


        return df


    # ================================================================
    # CREATE TABLE
    # ================================================================

    def create_table_if_not_exists(
        self,
        conn,
        df: pd.DataFrame
    ):

        columns = []


        for col in df.columns:

            columns.append(
                f'"{col}" VARCHAR'
            )


        sql = f"""
            CREATE TABLE IF NOT EXISTS trading_raw
            (
                {",".join(columns)}
            )
        """


        conn.execute(
            sql
        )


    # ================================================================
    # INSERT ONLY UNIQUE DATA
    # ================================================================

    def insert_unique_rows(
        self,
        conn,
        df: pd.DataFrame
    ):

        # ------------------------------------------------------------
        # Detect instrument column
        # ------------------------------------------------------------

        symbol_column = None


        for col in [
            "symbol",
            "instrument",
            "ticker",
            "Symbol"
        ]:

            if col in df.columns:

                symbol_column = col
                break


        # ------------------------------------------------------------
        # Detect date/time column
        # ------------------------------------------------------------

        datetime_column = None


        for col in [
            "datetime",
            "timestamp",
            "date_time",
            "created_at",
            "Date",
            "date"
        ]:

            if col in df.columns:

                datetime_column = col
                break


        if symbol_column is None:

            raise ValueError(
                "No instrument symbol column found"
            )


        if datetime_column is None:

            raise ValueError(
                "No date/time column found"
            )


        # ------------------------------------------------------------
        # Register dataframe
        # ------------------------------------------------------------

        conn.register(
            "incoming_data",
            df
        )


        columns = [
            f'"{c}"'
            for c in df.columns
        ]


        sql = f"""
            INSERT INTO trading_raw
            (
                {",".join(columns)}
            )

            SELECT
                {",".join(columns)}

            FROM incoming_data incoming

            WHERE NOT EXISTS
            (
                SELECT 1

                FROM trading_raw existing

                WHERE existing."{symbol_column}"
                    =
                    incoming."{symbol_column}"

                AND existing."{datetime_column}"
                    =
                    incoming."{datetime_column}"
            )
        """


        before = conn.execute(
            """
            SELECT COUNT(*)
            FROM trading_raw
            """
        ).fetchone()[0]


        conn.execute(
            sql
        )


        after = conn.execute(
            """
            SELECT COUNT(*)
            FROM trading_raw
            """
        ).fetchone()[0]


        inserted = after - before


        skipped = len(df) - inserted


        print(
            f"Inserted           : {inserted}"
        )

        print(
            f"Skipped duplicates : {skipped}"
        )


        conn.unregister(
            "incoming_data"
        )


    # ================================================================
    # SUMMARY
    # ================================================================

    def print_summary(
        self,
        conn
    ):

        count = conn.execute(
            """
            SELECT COUNT(*)
            FROM trading_raw
            """
        ).fetchone()[0]


        print(
            "\n========== SUMMARY =========="
        )

        print(
            f"Total rows : {count}"
        )

        print(
            "=============================\n"
        )


# ====================================================================
# RUN LOADER
# ====================================================================

if __name__ == "__main__":

    loader = TradingLoader()

    loader.load()