import os
import sys
import json
import logging
import unittest
import tempfile
import shutil
from pathlib import Path
from unittest.mock import patch, MagicMock

# Ensure project root is in sys.path
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from src.api.apiClient import (
    FauxnanceClient,
    FauxnanceAPIError,
    FauxnanceRateLimitError,
    FauxnanceClientError,
    FauxnanceServerError,
    FauxnanceConnectionError,
)
from src.extract.extract import extract_data



class TestFauxnanceClient(unittest.TestCase):

    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp())
        self.api_key = "fnx_test_key_1234567890"
        os.environ["FAUXNANCE_API_KEY"] = self.api_key
        os.environ["FAUXNANCE_BASE_URL"] = "https://api.test.fauxnance/v1"

    def tearDown(self):
        shutil.rmtree(self.test_dir)
        if "FAUXNANCE_API_KEY" in os.environ:
            del os.environ["FAUXNANCE_API_KEY"]

    def test_missing_api_key_raises_value_error(self):
        del os.environ["FAUXNANCE_API_KEY"]
        with self.assertRaises(ValueError) as ctx:
            FauxnanceClient()
        self.assertIn("FAUXNANCE_API_KEY environment variable is not set", str(ctx.exception))

    @patch("requests.get")
    def test_get_candles_caching(self, mock_get):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "data": {
                "symbol": "INFY.NS",
                "interval": "1d",
                "currency": "INR",
                "candles": [{"date": "2026-07-01", "open": 1500.0, "close": 1510.0, "high": 1520.0, "low": 1495.0, "adjclose": 1510.0, "volume": 100000, "synthetic": False}]
            },
            "meta": {"asOf": "2026-07-01T16:00:00Z", "disclaimer": "Educational data. Not for investment use.", "symbol": "INFY.NS", "source": "stored"}
        }
        mock_get.return_value = mock_response

        client = FauxnanceClient(cache_dir=str(self.test_dir))
        
        symbol = "INFY.NS"
        start_date = "2026-07-01"
        end_date = "2026-07-02"
        
        res1 = client.get_candles(symbol, start_date=start_date, end_date=end_date)
        self.assertEqual(res1["data"]["symbol"], "INFY.NS")
        self.assertEqual(mock_get.call_count, 1)
        
        cache_file = self.test_dir / f"INFY.NS_{start_date}_{end_date}.json"
        self.assertTrue(cache_file.exists())
        
        res2 = client.get_candles(symbol, start_date=start_date, end_date=end_date)
        self.assertEqual(res2["data"]["symbol"], "INFY.NS")
        self.assertEqual(mock_get.call_count, 1)  # Call count remains 1

    @patch("requests.get")
    def test_rate_limit_429(self, mock_get):
        mock_response = MagicMock()
        mock_response.status_code = 429
        mock_response.headers = {"Retry-After": "45"}
        mock_get.return_value = mock_response

        client = FauxnanceClient(cache_dir=str(self.test_dir))
        
        with self.assertRaises(FauxnanceRateLimitError) as ctx:
            client.get_candles("RELIANCE.NS", start_date="2026-07-01", end_date="2026-07-02")
            
        self.assertEqual(ctx.exception.retry_after, 45)
        self.assertEqual(ctx.exception.status_code, 429)

    @patch("requests.get")
    def test_client_error_404(self, mock_get):
        mock_response = MagicMock()
        mock_response.status_code = 404
        mock_response.json.return_value = {
            "error": {
                "code": "SYMBOL_NOT_FOUND",
                "message": "Symbol not found in Fauxnance registry",
                "details": {}
            }
        }
        mock_get.return_value = mock_response

        client = FauxnanceClient(cache_dir=str(self.test_dir))
        
        with self.assertRaises(FauxnanceClientError) as ctx:
            client.get_candles("INVALID_TICKER", start_date="2026-07-01", end_date="2026-07-02")
            
        self.assertEqual(ctx.exception.status_code, 404)
        self.assertEqual(ctx.exception.error_code, "SYMBOL_NOT_FOUND")

    @patch("time.sleep")  
    @patch("requests.get")
    def test_connection_error_retry(self, mock_get, mock_sleep):
        import requests.exceptions
        mock_get.side_effect = requests.exceptions.Timeout("Connection timed out")
        
        client = FauxnanceClient(cache_dir=str(self.test_dir))
        
        with self.assertRaises(FauxnanceConnectionError):
            client.get_candles("RELIANCE.NS", start_date="2026-07-01", end_date="2026-07-02")
            
        self.assertEqual(mock_get.call_count, 3)
        self.assertEqual(mock_sleep.call_count, 2)

    @patch("requests.get")
    def test_malformed_json_on_200(self, mock_get):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.side_effect = json.JSONDecodeError("Expecting value", "", 0)
        mock_get.return_value = mock_response

        client = FauxnanceClient(cache_dir=str(self.test_dir))
        
        with self.assertRaises(FauxnanceAPIError) as ctx:
            client.get_candles("RELIANCE.NS", start_date="2026-07-01", end_date="2026-07-02")
            
        self.assertEqual(ctx.exception.status_code, 200)
        self.assertEqual(ctx.exception.error_code, "MALFORMED_JSON")


class TestExtractCoordination(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.api_key = "fnx_test_key_1234567890"
        os.environ["FAUXNANCE_API_KEY"] = self.api_key
        os.environ["FAUXNANCE_BASE_URL"] = "https://api.test.fauxnance/v1"

    def tearDown(self):
        shutil.rmtree(self.test_dir)
        if "FAUXNANCE_API_KEY" in os.environ:
            del os.environ["FAUXNANCE_API_KEY"]

    @patch("requests.get")
    def test_extract_data_success_and_failures(self, mock_get):
        def side_effect(url, headers, params=None, timeout=None):
            resp = MagicMock()
            if "/health" in url:
                resp.status_code = 200
                resp.json.return_value = {"data": {"status": "ok", "markets": []}}
            elif "/candles/GOOD_STOCK" in url:
                resp.status_code = 200
                resp.json.return_value = {
                    "data": {"symbol": "GOOD_STOCK", "candles": []}
                }
            elif "/candles/BAD_STOCK" in url:
                resp.status_code = 404
                resp.json.return_value = {
                    "error": {"code": "SYMBOL_NOT_FOUND", "message": "Not found"}
                }
            elif "/candles/LIMIT_STOCK" in url:
                resp.status_code = 429
                resp.headers = {"Retry-After": "30"}
            return resp

        mock_get.side_effect = side_effect

        res = extract_data(
            symbols=["GOOD_STOCK", "BAD_STOCK"], 
            start_date="2026-07-01", 
            end_date="2026-07-15",
            cache_dir=self.test_dir
        )
        
        self.assertEqual(res["status"], "completed")
        self.assertIn("GOOD_STOCK", res["success"])
        self.assertIn("BAD_STOCK", res["failed"])
        self.assertIn("Client error (404)", res["failed"]["BAD_STOCK"])

    @patch("requests.get")
    def test_extract_data_aborts_on_429(self, mock_get):
        def side_effect(url, headers, params=None, timeout=None):
            resp = MagicMock()
            if "/health" in url:
                resp.status_code = 200
                resp.json.return_value = {"data": {"status": "ok", "markets": []}}
            elif "/candles/LIMIT_STOCK" in url:
                resp.status_code = 429
                resp.headers = {"Retry-After": "10"}
            elif "/candles/GOOD_STOCK" in url:
                resp.status_code = 200
                resp.json.return_value = {"data": {"symbol": "GOOD_STOCK", "candles": []}}
            return resp

        mock_get.side_effect = side_effect

        res = extract_data(
            symbols=["LIMIT_STOCK", "GOOD_STOCK"], 
            start_date="2026-07-01", 
            end_date="2026-07-15",
            cache_dir=self.test_dir
        )
        
        self.assertEqual(res["status"], "interrupted")
        self.assertIn("LIMIT_STOCK", res["failed"])
        self.assertIn("Rate limit exceeded", res["failed"]["LIMIT_STOCK"])
        self.assertNotIn("GOOD_STOCK", res["success"])
        self.assertNotIn("GOOD_STOCK", res["failed"])

    def test_candles_to_dataframe(self):
        from src.extract.extract import candles_to_dataframe
        import pandas as pd
        
        valid_response = {
            "data": {
                "symbol": "INFY.NS",
                "interval": "1d",
                "currency": "INR",
                "candles": [
                    {"date": "2026-07-02", "open": 1500.0, "close": 1510.0, "high": 1520.0, "low": 1495.0, "adjclose": 1510.0, "volume": 100000, "synthetic": False},
                    {"date": "2026-07-01", "open": 1490.0, "close": 1495.0, "high": 1505.0, "low": 1485.0, "adjclose": 1495.0, "volume": 95000, "synthetic": False}
                ]
            }
        }
        
        df = candles_to_dataframe(valid_response)
        
        self.assertIsInstance(df, pd.DataFrame)
        self.assertEqual(len(df), 2)
        self.assertEqual(df.iloc[0]["date"], pd.Timestamp("2026-07-01"))
        self.assertEqual(df.iloc[1]["date"], pd.Timestamp("2026-07-02"))
        self.assertEqual(df.iloc[0]["close"], 1495.0)
        
        empty_df = candles_to_dataframe({})
        self.assertTrue(empty_df.empty)


class TestFlaskRoutes(unittest.TestCase):

    def setUp(self):
        from src.api.app import app
        app.testing = True
        self.client = app.test_client()

    def test_index_route(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"Akatsuki Stock Analyzer", response.data)


if __name__ == "__main__":
    unittest.main()

