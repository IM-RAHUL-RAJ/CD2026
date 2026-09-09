import argparse
import sys
import logging
from datetime import date, timedelta
from pathlib import Path

# Ensure project root is in sys.path
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.extract.extract import extract_data, save_extracted_data
from src.transform.transform import transform
from src.load.load import TradingLoader
from src.eda.generate_dashboard import create_dashboard

logger = logging.getLogger(__name__)

DEFAULT_SYMBOLS = ["INFY.NS", "TATASTEEL.NS", "RELIANCE.NS", "MSFT", "GOOGL"]


def run_pipeline(symbols=None, start_date=None, end_date=None, skip_extract=False):
    """Run full ETL + EDA pipeline: Extract → Transform → Load → EDA dashboard."""
    target_symbols = symbols
    if not skip_extract:
        target_symbols = symbols or DEFAULT_SYMBOLS
        logger.info(f"Pipeline step 1/4: Extracting data for {target_symbols}")
        
        if not end_date:
            end_date = date.today().isoformat()
        if not start_date:
            start_date = (date.today() - timedelta(days=30)).isoformat()
            
        results = extract_data(symbols=target_symbols, start_date=start_date, end_date=end_date)
        if results.get("success"):
            save_extracted_data(results["success"], output_dir="data")
        else:
            logger.warning("Extraction produced no successful data. Using existing files in data/ if available.")
    else:
        logger.info("Pipeline step 1/4: Skipping extraction (using existing data in data/)")

    logger.info("Pipeline step 2/4: Transform")
    transform()

    logger.info("Pipeline step 3/4: Load into analytics.duckdb")
    loader = TradingLoader()
    loader.load(clear_existing=True)

    logger.info("Pipeline step 4/4: Generate EDA dashboard")
    create_dashboard(symbols=target_symbols)

    logger.info("Pipeline finished successfully.")


def start_etl(symbols=None):
    """Alias for running the pipeline."""
    run_pipeline(symbols=symbols)


def main():
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")

    parser = argparse.ArgumentParser(description="Run the Fauxnance ETL & EDA pipeline.")
    parser.add_argument(
        "--symbols",
        nargs="+",
        help="List of ticker symbols to extract (e.g. INFY.NS MSFT GOOGL).",
    )
    parser.add_argument(
        "--skip-extract",
        action="store_true",
        help="Skip extraction and use data already in data/ folder.",
    )
    args = parser.parse_args()

    run_pipeline(symbols=args.symbols, skip_extract=args.skip_extract)


if __name__ == "__main__":
    main()

