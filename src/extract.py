import os
import logging
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


def load_env(path: str = ".env"):
    """Manually parse .env file to load variables into environment without external packages."""
    dotenv_file = Path(path)
    if dotenv_file.exists():
        logger.info(f"Loading environment variables from {dotenv_file.resolve()}")
        try:
            with open(dotenv_file, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, val = line.split("=", 1)
                    key = key.strip()
                    val = val.strip().strip("'\"")
                    # Set environment variable if not already set by shell
                    if key and key not in os.environ:
                        os.environ[key] = val
        except Exception as e:
            logger.error(f"Failed to read .env file: {e}")


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


if __name__ == "__main__":
    # Test script entry point / CLI interface
    import sys
    import argparse
    logging.basicConfig(level=logging.INFO)

    # 1. Parse command line arguments
    # Usage examples:
    #   python -m src.extract INFY.NS TCS.NS --days 7
    #   python -m src.extract INFY.NS TCS.NS --from 2026-08-01 --to 2026-08-20
    parser = argparse.ArgumentParser(description="Fauxnance data extractor")
    parser.add_argument("symbols", nargs="*", help="Ticker symbols to extract (e.g. INFY.NS TCS.NS)")
    parser.add_argument("--from", dest="from_date", default=None, help="Start date YYYY-MM-DD")
    parser.add_argument("--to",   dest="to_date",   default=None, help="End date YYYY-MM-DD")
    parser.add_argument("--days", dest="days", type=int, default=None,
                        help="Number of past days to fetch (e.g. --days 7 for 1 week). Overrides --from/--to.")
    args = parser.parse_args()

    # Resolve symbols
    test_symbols = args.symbols if args.symbols else ["INFY.NS", "RELIANCE.NS", "INVALID_STOCK"]
    logger.info(f"Symbols: {test_symbols}")

    # Resolve date range — default is last 30 days (1 month) if nothing is specified
    from datetime import date, timedelta
    if args.days:
        # --days flag: e.g. --days 7 for 1 week
        start_date = (date.today() - timedelta(days=args.days)).isoformat()
        end_date   = date.today().isoformat()
    elif args.from_date:
        # --from / --to flags: explicit date range
        start_date = args.from_date
        end_date   = args.to_date or date.today().isoformat()
    else:
        # Default: last 30 days
        start_date = (date.today() - timedelta(days=30)).isoformat()
        end_date   = date.today().isoformat()

    logger.info(f"Date range: {start_date} -> {end_date}")

    res = extract_data(test_symbols, start_date=start_date, end_date=end_date)
    
    print("\n" + "=" * 60)
    print("EXTRACTION RUN SUMMARY")
    print("=" * 60)
    print(f"Overall Run Status: {res['status'].upper()}")
    print(f"Successfully Extracted ({len(res['success'])}): {list(res['success'].keys())}")
    
    if res['failed']:
        print(f"Failed to Extract ({len(res['failed'])}):")
        for sym, error in res['failed'].items():
            print(f"  - {sym}: {error}")
    print("=" * 60)
    
    # 2. Leverage Pandas to show a sample table of successful extracts
    if res['success']:
        print("\nSAMPLE EXTRACTED DATA (PANDAS DATAFRAMES):")
        for sym, raw_response in res['success'].items():
            df = candles_to_dataframe(raw_response)
            print(f"\nTicker: {sym} (First 5 rows):")
            if not df.empty:
                print(df.head(5).to_string(index=False))
            else:
                print("[Empty DataFrame]")
        print("=" * 60)

    # 3. Save extracted data to CSV files in data/ folder
    if res['success']:
        output_dir = Path("data")
        output_dir.mkdir(parents=True, exist_ok=True)

        all_frames = []
        print("\nSAVING CSV FILES:")

        for sym, raw_response in res['success'].items():
            df = candles_to_dataframe(raw_response)
            if not df.empty:
                # Add symbol column so we know which stock each row belongs to
                df.insert(0, "symbol", sym)
                all_frames.append(df)

                # Save one CSV per symbol  e.g. data/INFY_NS.csv
                safe_sym = sym.replace(".", "_")
                per_symbol_path = output_dir / f"{safe_sym}.csv"
                df.to_csv(per_symbol_path, index=False)
                print(f"  Saved {sym:20s} -> {per_symbol_path}  ({len(df)} rows)")

        # Save one combined CSV with all symbols stacked
        if all_frames:
            combined_df = pd.concat(all_frames, ignore_index=True)
            combined_path = output_dir / "all_symbols.csv"
            combined_df.to_csv(combined_path, index=False)
            print(f"\n  Combined file      -> {combined_path}  ({len(combined_df)} total rows)")

        print("=" * 60)
