import pandas as pd
import numpy as np
import pytest

from pathlib import Path

from transform import (
    validate_schema,
    validate_data,
    expected_symbol_from_filename,
)


VALID_COLUMNS = [
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


# transformed is outside src
TRANSFORMED_DIR = (
    Path(__file__).resolve().parent.parent / "transformed"
)


def get_transformed_files():

    files = sorted(
        TRANSFORMED_DIR.glob("*.csv")
    )

    assert len(files) == 5, (
        f"Expected 5 transformed CSV files, "
        f"found {len(files)}"
    )

    return files


@pytest.mark.parametrize(
    "file",
    get_transformed_files(),
    ids=lambda file: file.name,
)
def test_transformed_file(file):

    """
    Validates the actual transformed CSV files.
    Does not create fake dataframes.
    """

    df = pd.read_csv(file)


     # Basic file checks
 
    assert not df.empty, (
        f"{file.name}: file is empty"
    )


    assert set(df.columns) == set(
        VALID_COLUMNS
    ), (
        f"{file.name}: incorrect columns"
    )


     # Schema validation
 
    validate_schema(
        df,
        file.name
    )


     # Data validation
 
    validate_data(
        df,
        file.name
    )


     # Symbol validation
 
    expected_symbol = (
        expected_symbol_from_filename(file)
    )


    assert (
        df["symbol"]
        .eq(expected_symbol)
        .all()
    ), (
        f"{file.name}: "
        f"expected symbol {expected_symbol}"
    )


     # Date validation
 
    dates = pd.to_datetime(
        df["date"],
        format="%Y-%m-%d",
        errors="coerce",
    )


    assert dates.notna().all(), (
        f"{file.name}: invalid dates"
    )


    assert not df["date"].duplicated().any(), (
        f"{file.name}: duplicate dates"
    )


    assert dates.is_monotonic_increasing, (
        f"{file.name}: dates are not sorted"
    )


     # Price validation
 
    price_columns = [
        "open",
        "high",
        "low",
        "close",
        "adjclose",
    ]


    for column in price_columns:

        values = pd.to_numeric(
            df[column],
            errors="coerce",
        )


        assert values.notna().all(), (
            f"{file.name}: "
            f"invalid or missing {column}"
        )


        assert np.isfinite(values).all(), (
            f"{file.name}: "
            f"infinite value in {column}"
        )


        assert (
            values >= 0
        ).all(), (
            f"{file.name}: "
            f"negative value in {column}"
        )


     # OHLC validation
 
    assert (
        df["high"] >= df["low"]
    ).all(), (
        f"{file.name}: high < low"
    )


    assert (
        (df["open"] >= df["low"]) &
        (df["open"] <= df["high"])
    ).all(), (
        f"{file.name}: "
        "open outside low/high range"
    )


    assert (
        (df["close"] >= df["low"]) &
        (df["close"] <= df["high"])
    ).all(), (
        f"{file.name}: "
        "close outside low/high range"
    )


     # Volume validation
 
    volume = pd.to_numeric(
        df["volume"],
        errors="coerce",
    )


    volume = volume.dropna()


    assert np.isfinite(volume).all(), (
        f"{file.name}: infinite volume"
    )


    assert (
        volume >= 0
    ).all(), (
        f"{file.name}: negative volume"
    )


     # Synthetic validation
 
    assert df["synthetic"].notna().all(), (
        f"{file.name}: missing synthetic value"
    )


    synthetic = (
        df["synthetic"]
        .astype(str)
        .str.strip()
        .str.lower()
    )


    assert synthetic.isin(
        [
            "true",
            "false"
        ]
    ).all(), (
        f"{file.name}: invalid synthetic value"
    )