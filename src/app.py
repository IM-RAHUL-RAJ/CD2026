import os
import logging
from flask import Flask, jsonify, request
from src.apiClient import FauxnanceClient, FauxnanceRateLimitError, FauxnanceClientError
from src.extract import extract_data, load_env

# Set up logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("ExtractAPI")

app = Flask(__name__)

# Initialize environment variables
load_env()


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
    """
    Endpoint to trigger bulk extraction for a list of symbols.
    Body format:
    {
        "symbols": ["INFY.NS", "RELIANCE.NS"],
        "from": "2026-07-01",
        "to": "2026-07-15"
    }
    """
    body = request.get_json() or {}
    symbols = body.get("symbols", [])
    start_date = body.get("from")
    end_date = body.get("to")

    if not symbols or not isinstance(symbols, list):
        return jsonify({"error": "Missing or invalid 'symbols' list in request body"}), 400

    try:
        logger.info(f"Flask API: Triggering batch extraction for symbols: {symbols}")
        results = extract_data(symbols, start_date=start_date, end_date=end_date)
        
        status_code = 200
        if results.get("status") == "interrupted":
            status_code = 429
            
        return jsonify(results), status_code
    except Exception as e:
        logger.error(f"Batch extraction trigger failed: {e}")
        return jsonify({"error": str(e)}), 500



def main():
    """Entry point for the fauxnance-serve CLI command."""
    port = int(os.getenv("FLASK_PORT", 5000))
    logger.info(f"Starting Fauxnance Extract API on port {port}...")
    app.run(host="0.0.0.0", port=port, debug=True)


if __name__ == "__main__":
    main()
