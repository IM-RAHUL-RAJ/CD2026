import sys
from pathlib import Path
import pandas as pd
import numpy as np
import pytest

# Ensure project root is in sys.path
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.transform.transform import (
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


TRANSFORMED_DIR = (
    Path(__file__).resolve().parent.parent / "transformed"
)


def get_transformed_files():
    files = sorted(TRANSFORMED_DIR.glob("*.csv"))
    if not files:
        from src.transform.transform import transform
        transform()
        files = sorted(TRANSFORMED_DIR.glob("*.csv"))
    return files


@pytest.mark.parametrize(
    "file",
    get_transformed_files(),
    ids=lambda file: file.name,
)
def test_transformed_file(file):

    df = pd.read_csv(file)
 
    assert not df.empty, (
        f"{file.name}: file is empty"
    )

    assert set(df.columns) == set(
        VALID_COLUMNS
    ), (
        f"{file.name}: incorrect columns"
    )


    validate_schema(
        df,
        file.name
    )
 
    validate_data(
        df,
        file.name
    )
 
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