import os
import logging
from dotenv import load_dotenv
from pathlib import Path
import pandas as pd
from src.apiClient import (
    FauxnanceClient, 
    FauxnanceRateLimitError, 
    FauxnanceClientError, 
    FauxnanceAPIError, 
    FauxnanceConnectionError
)


# Set up logging. We must never log the API key.
logger = logging.getLogger("Extract")
if not logger.handlers:
    handler = logging.StreamHandler()
    formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)


logger = logging.getLogger(__name__)

def load_env(path: str = ".env"):
    """Load environment variables from .env file using python-dotenv."""
    dotenv_file = Path(path)

    if dotenv_file.exists():
        logger.info(f"Loading environment variables from {dotenv_file.resolve()}")
        try:
            load_dotenv(dotenv_path=dotenv_file, override=False)
        except Exception as e:
            logger.error(f"Failed to load .env file: {e}")


def extract_data(symbols: list, start_date: str = None, end_date: str = None, cache_dir: str = None) -> dict:
    """
    Orchestrates extraction of daily stock candle data from Fauxnance API.
    
    Args:
        symbols: List of ticker symbols to query (e.g. ['INFY.NS', 'RELIANCE.NS'])
        start_date: Start date string (YYYY-MM-DD), optional
        end_date: End date string (YYYY-MM-DD), optional
        cache_dir: Custom directory for caching raw JSON data, optional
        
    Returns:
        dict: A dictionary containing:
            - 'success': dict of {symbol: raw_response}
            - 'failed': dict of {symbol: error_message}
            - 'status': 'completed' or 'interrupted'
    """
    # Ensure environment variables are loaded
    load_env()

    logger.info(f"Starting extraction for {len(symbols)} symbols...")
    
    try:
        client = FauxnanceClient(cache_dir=cache_dir)
    except ValueError as ve:
        logger.critical(f"Client initialization failed: {ve}")
        return {
            "success": {},
            "failed": {s: "Configuration error (API key missing)" for s in symbols},
            "status": "failed"
        }

    # Verify API health first (uncached)
    try:
        health = client.get_health()
        status = health.get("data", {}).get("status", "unknown")
        logger.info(f"Fauxnance API status: {status}")
    except Exception as e:
        logger.warning(f"Failed to check Fauxnance API health: {e}. Attempting connection anyway.")

    results = {
        "success": {},
        "failed": {},
        "status": "completed"
    }

    for symbol in symbols:
        symbol = symbol.strip().upper()
        if not symbol:
            continue
            
        try:
            logger.info(f"Processing symbol: {symbol}")
            raw_data = client.get_candles(symbol, start_date=start_date, end_date=end_date)
            
            # Simple structure validation of the raw response
            if not isinstance(raw_data, dict) or "data" not in raw_data:
                logger.error(f"Response for {symbol} is empty or invalid structure.")
                results["failed"][symbol] = "Invalid response envelope (missing 'data' field)"
                continue
                
            results["success"][symbol] = raw_data
            candles_count = len(raw_data.get("data", {}).get("candles", []))
            logger.info(f"Successfully extracted {candles_count} candles for {symbol}")

        except FauxnanceRateLimitError as rle:
            # 429 rate limit errors: Stop the run immediately, report Retry-After
            logger.critical(
                f"RATE LIMIT ERROR on symbol {symbol}. Stopping extraction immediately! "
                f"Suggested wait: {rle.retry_after} seconds."
            )
            results["status"] = "interrupted"
            results["failed"][symbol] = f"Rate limit exceeded. Retry after {rle.retry_after}s."
            # Fail fast: do not request any more symbols
            break

        except FauxnanceClientError as ce:
            # Other 4xx errors: Mark this symbol as failed, do NOT retry, continue processing others
            logger.error(f"Client error on symbol {symbol}: {ce.message}")
            results["failed"][symbol] = f"Client error ({ce.status_code}): {ce.message}"

        except FauxnanceConnectionError as cone:
            # Connection error after retries: Mark this symbol as failed and continue processing others
            logger.error(f"Connection failure on symbol {symbol}: {cone.message}")
            results["failed"][symbol] = f"Connection failure: {cone.message}"

        except FauxnanceAPIError as ae:
            # General API error (e.g. server error, malformed JSON): Log and proceed to next symbol
            logger.error(f"API error on symbol {symbol}: {ae.message}")
            results["failed"][symbol] = f"API error ({ae.status_code}): {ae.message}"

        except Exception as e:
            # Catch-all: Log and continue
            logger.error(f"Unexpected error on symbol {symbol}: {e}")
            results["failed"][symbol] = f"Unexpected error: {str(e)}"

    logger.info(
        f"Extraction run complete. "
        f"Success: {len(results['success'])} symbols. "
        f"Failed: {len(results['failed'])} symbols."
    )
    return results


def candles_to_dataframe(candles_response: dict) -> pd.DataFrame:
    """
    Helper to convert Fauxnance candles JSON response to a Pandas DataFrame.
    Returns a sorted DataFrame with standard column types.
    """
    if not candles_response or "data" not in candles_response or "candles" not in candles_response["data"]:
        logger.warning("Empty or invalid candles response provided. Returning empty DataFrame.")
        return pd.DataFrame()
    
    candles = candles_response["data"]["candles"]
    df = pd.DataFrame(candles)
    
    if not df.empty:
        # Cast and sort columns for downstream Transform phase consistency
        df["date"] = pd.to_datetime(df["date"])
        df = df.sort_values("date").reset_index(drop=True)
        # Ensure numerical values are correctly typed
        numeric_cols = ["open", "high", "low", "close", "adjclose", "volume"]
        for col in numeric_cols:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors="coerce")
                
    return df

def save_extracted_data(success_data, output_dir="data"):
    """
    Convert extracted candle data to CSV files.

    Returns:
        {
            "files": [...],
            "total_rows": int
        }
    """

    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    all_frames = []
    saved_files = []
    total_rows = 0

    for sym, raw_response in success_data.items():

        df = candles_to_dataframe(raw_response)

        if df.empty:
            continue

        # Add symbol column
        df.insert(0, "symbol", sym)

        all_frames.append(df)
        total_rows += len(df)

        # INFY.NS -> INFY_NS.csv
        safe_sym = sym.replace(".", "_")

        file_path = output_path / f"{safe_sym}.csv"

        df.to_csv(file_path, index=False)

        saved_files.append({
            "symbol": sym,
            "file": str(file_path),
            "rows": len(df)
        })

    # Combined CSV
    if all_frames:
        combined_df = pd.concat(
            all_frames,
            ignore_index=True
        )

        combined_path = output_path / "all_symbols.csv"

        combined_df.to_csv(
            combined_path,
            index=False
        )

        saved_files.append({
            "symbol": "ALL",
            "file": str(combined_path),
            "rows": len(combined_df)
        })

    return {
        "files": saved_files,
        "total_rows": total_rows
    }
    
