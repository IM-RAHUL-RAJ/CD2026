import os
import logging
from datetime import date, timedelta
from flask import Flask, jsonify, request, render_template
from src.apiClient import (
    FauxnanceClient,
    FauxnanceRateLimitError,
    FauxnanceClientError
)
from src.extract import (
    extract_data,
    load_env,
    save_extracted_data
)

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ExtractAPI")

app = Flask(__name__)

# Initialize environment variables
load_env()

@app.route("/")
def index():
    return render_template("index.html")

@app.route("/health", methods=["GET"])
def health():
    """Endpoint to check Fauxnance API health."""
    try:
        client = FauxnanceClient()
        health_data = client.get_health()
        return jsonify(health_data), 200
    except Exception as e:
        logger.error(f"Health check failed: {e}")
        return jsonify({"error": "Failed to connect to Fauxnance API", "details": str(e)}), 503


@app.route("/usage", methods=["GET"])
def usage():
    """Endpoint to check the daily quota status of the API key."""
    try:
        client = FauxnanceClient()
        usage_data = client.get_usage()
        return jsonify(usage_data), 200
    except FauxnanceClientError as ce:
        return jsonify({"error": ce.message, "code": ce.error_code}), ce.status_code or 400
    except Exception as e:
        logger.error(f"Usage check failed: {e}")
        return jsonify({"error": str(e)}), 500


@app.route("/candles/<symbol>", methods=["GET"])
def get_symbol_candles(symbol):
    """Endpoint to retrieve candles for a specific symbol (with caching)."""
    start_date = request.args.get("from")
    end_date = request.args.get("to")
    
    try:
        client = FauxnanceClient()
        logger.info(f"Flask API: Requesting candles for {symbol} (from: {start_date}, to: {end_date})")
        data = client.get_candles(symbol, start_date=start_date, end_date=end_date)
        return jsonify(data), 200
    except FauxnanceRateLimitError as rle:
        return jsonify({"error": rle.message, "retry_after": rle.retry_after}), 429
    except FauxnanceClientError as ce:
        return jsonify({"error": ce.message, "code": ce.error_code}), ce.status_code or 400
    except Exception as e:
        logger.error(f"Candles retrieval failed for {symbol}: {e}")
        return jsonify({"error": str(e)}), 500


@app.route("/extract", methods=["POST"])
def trigger_extraction():

    body = request.get_json(silent=True) or {}

    symbols = body.get("symbols", [])

    # Validate symbols
    if not isinstance(symbols, list) or not symbols:
        return jsonify({
            "error": "'symbols' must be a non-empty list"
        }), 400

    # Clean symbols
    symbols = [
        symbol.strip().upper()
        for symbol in symbols
        if isinstance(symbol, str) and symbol.strip()
    ]

    if not symbols:
        return jsonify({
            "error": "No valid symbols provided"
        }), 400

    try:
        logger.info(
            f"Starting 30-day extraction for: {symbols}"
        )

        # extract_data already has optional dates.
        # We explicitly calculate last 30 days here.
        from datetime import date, timedelta

        end_date = date.today()
        start_date = end_date - timedelta(days=30)

        results = extract_data(
            symbols=symbols,
            start_date=start_date.isoformat(),
            end_date=end_date.isoformat()
        )

        # Save successful extracted data to CSV
        csv_result = {
            "files": [],
            "total_rows": 0
        }

        if results["success"]:
            csv_result = save_extracted_data(
                success_data=results["success"],
                output_dir="data"
            )

        # Add CSV information to response
        results["csv"] = csv_result

        # Add date range for UI
        results["date_range"] = {
            "from": start_date.isoformat(),
            "to": end_date.isoformat()
        }

        status_code = 200

        if results.get("status") == "interrupted":
            status_code = 429

        return jsonify(results), status_code

    except Exception as e:

        logger.exception(
            f"Batch extraction failed: {e}"
        )

        return jsonify({
            "error": "Extraction failed",
            "details": str(e)
        }), 500

def main():
    """Entry point for the Fauxnance Extract API."""
    port = int(os.getenv("FLASK_PORT", 5000))

    logger.info(
        f"Starting Fauxnance Extract API on port {port}..."
    )

    app.run(
        host="0.0.0.0",
        port=port,
        debug=True
    )


if __name__ == "__main__":
    main()

if __name__ == "__main__":
    main()
