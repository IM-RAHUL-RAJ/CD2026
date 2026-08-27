from pathlib import Path
import random
import numpy as np
import pandas as pd


INPUT_DIR = Path.cwd() / "data"
OUTPUT_DIR = Path.cwd() / "transformed"


REQUIRED_COLUMNS = {
    "symbol",
    "date",
    "open",
    "high",
    "low",
    "close",
    "adjclose",
    "synthetic",
}

OPTIONAL_COLUMNS = {"volume"}

OUTPUT_COLUMNS = [
    "symbol",
    "date",
    "open",
    "high",
    "low",
    "close",
    "adjclose",
    "volume",
    "synthetic",
]


class ValidationError(ValueError):
    pass


COLUMN_MAP = {
    "Date": "date",
    "DATE": "date",
    "Symbol": "symbol",
    "SYMBOL": "symbol",
    "Open": "open",
    "OPEN": "open",
    "High": "high",
    "HIGH": "high",
    "Low": "low",
    "LOW": "low",
    "Close": "close",
    "CLOSE": "close",
    "Adj Close": "adjclose",
    "AdjClose": "adjclose",
    "Volume": "volume",
    "VOLUME": "volume",
    "Synthetic": "synthetic",
    "SYNTHETIC": "synthetic",
}


def clean_column_names(df):
    df.columns = df.columns.str.strip()
    df.rename(columns=COLUMN_MAP, inplace=True)
    return df


def clean_string_values(df):
    for column in df.select_dtypes(include=["object", "string"]).columns:
        df[column] = df[column].astype(str).str.strip()
    return df


def expected_symbol_from_filename(file):
    name = file.stem.upper()

    return name.replace("_", ".")


def fix_symbol(df, file):
    expected = expected_symbol_from_filename(file)

    if "symbol" not in df.columns:
        df["symbol"] = expected
        return df

    df["symbol"] = (
        df["symbol"]
        .astype(str)
        .str.strip()
        .str.upper()
    )

    df = df[df["symbol"] == expected]

    return df


def normalize_dates(df):
    df["date"] = pd.to_datetime(
        df["date"],
        errors="coerce",
        format="mixed"
    )

    df = df.dropna(subset=["date"])

    today = pd.Timestamp.today()

    df = df[df["date"] <= today]

    return df


def clean_numeric_columns(df):
    columns = [
        "open",
        "high",
        "low",
        "close",
        "adjclose",
        "volume",
    ]

    for col in columns:
        if col in df.columns:
            df[col] = (
                df[col]
                .replace(["", "NULL", "null", "N/A", "NA"], pd.NA)
                .astype(str)
                .str.replace("$", "", regex=False)
                .str.replace(",", "", regex=False)
            )

            df[col] = pd.to_numeric(
                df[col],
                errors="coerce"
            )

    return df


def add_missing_volume(df):
    if "volume" not in df.columns:
        df["volume"] = pd.NA

    return df


def normalize_synthetic(df):
    mapping = {
        "true": True,
        "false": False,
        "1": True,
        "0": False,
        "yes": True,
        "no": False,
        "y": True,
        "n": False,
    }

    df["synthetic"] = (
        df["synthetic"]
        .astype(str)
        .str.lower()
        .map(mapping)
    )

    return df


def repair_open_close(df):
    df = df.dropna(
        subset=["open", "close"],
        how="all"
    )

    for i, row in df.iterrows():
        if pd.isna(row["open"]):
            df.loc[i, "open"] = row["close"]

        if pd.isna(row["close"]):
            df.loc[i, "close"] = row["open"]

    return df


def repair_high_low(df):
    for i, row in df.iterrows():

        if pd.isna(row["open"]) or pd.isna(row["close"]):
            continue

        low_limit = min(row["open"], row["close"])
        high_limit = max(row["open"], row["close"])

        if pd.isna(row["high"]) or row["high"] < high_limit:
            df.loc[i, "high"] = round(
                random.uniform(high_limit, high_limit * 1.02),
                2
            )

        if pd.isna(row["low"]) or row["low"] > low_limit:
            df.loc[i, "low"] = round(
                random.uniform(low_limit * 0.98, low_limit),
                2
            )

    return df


def remove_negative_values(df):
    cols = ["open", "high", "low", "close", "volume"]

    for col in cols:
        if col in df.columns:
            df = df[(df[col].isna()) | (df[col] >= 0)]

    return df


def apply_transformations(df, file):
    df = clean_column_names(df)
    df = clean_string_values(df)
    df = clean_numeric_columns(df)
    df = add_missing_volume(df)
    df = fix_symbol(df, file)
    df = normalize_dates(df)
    df = repair_open_close(df)
    df = repair_high_low(df)
    df = remove_negative_values(df)
    df = normalize_synthetic(df)

    return df


def validate_schema(df, filename):
    missing = REQUIRED_COLUMNS - set(df.columns)

    if missing:
        raise ValidationError(
            f"{filename}: Missing columns {missing}"
        )


def validate_data(df, filename):

    if df.empty:
        raise ValidationError(
            f"{filename}: No valid rows after transformation"
        )

    if df["symbol"].nunique() != 1:
        raise ValidationError(
            f"{filename}: Multiple symbols found"
        )

    if (df["high"] < df["low"]).any():
        raise ValidationError(
            f"{filename}: high < low"
        )


def transform_file(file):

    df = pd.read_csv(file)

    df = apply_transformations(
        df,
        file
    )

    validate_schema(df, file.name)
    validate_data(df, file.name)

    df["date"] = df["date"].dt.strftime("%Y-%m-%d")

    df = (
        df.sort_values("date")
        .reset_index(drop=True)
    )

    df = df[OUTPUT_COLUMNS]

    OUTPUT_DIR.mkdir(
        exist_ok=True
    )

    output = OUTPUT_DIR / file.name

    df.to_csv(
        output,
        index=False
    )

    print(f"Transformed: {file.name}")



def transform():

    files = list(INPUT_DIR.glob("*.csv"))

    for file in files:
        try:
            transform_file(file)

        except ValidationError as e:
            print("FAILED:", e)

