import os
import time
import json
import logging
from pathlib import Path
from datetime import date, timedelta
import requests

logger = logging.getLogger("FauxnanceClient")
if not logger.handlers:
    handler = logging.StreamHandler()
    formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)


class FauxnanceAPIError(Exception):
    def __init__(self, message: str, status_code: int = None, error_code: str = None, details: dict = None):
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.error_code = error_code
        self.details = details or {}

    def __str__(self):
        return f"{self.__class__.__name__}(status_code={self.status_code}, error_code={self.error_code}, message={self.message})"


class FauxnanceRateLimitError(FauxnanceAPIError):
    def __init__(self, message: str, retry_after: int, details: dict = None):
        super().__init__(message, status_code=429, error_code="RATE_LIMITED", details=details)
        self.retry_after = retry_after

    def __str__(self):
        return f"FauxnanceRateLimitError(retry_after={self.retry_after}s, message={self.message})"


class FauxnanceClientError(FauxnanceAPIError):
    pass


class FauxnanceServerError(FauxnanceAPIError):
    pass


class FauxnanceConnectionError(FauxnanceAPIError):
    pass


class FauxnanceClient:

    def __init__(self, base_url: str = None, api_key: str = None, cache_dir: str = None):
        self.api_key = api_key or os.getenv("FAUXNANCE_API_KEY")
        if not self.api_key:
            raise ValueError("FAUXNANCE_API_KEY environment variable is not set.")

        self.base_url = base_url or os.getenv(
            "FAUXNANCE_BASE_URL", 
            "https://y4t9nq2bqf.execute-api.eu-west-2.amazonaws.com/v1"
        ).rstrip("/")

        cache_dir_name = cache_dir or os.getenv("FAUXNANCE_CACHE_DIR", ".cache")
        self.cache_dir = Path(cache_dir_name)
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def _sanitize_filename(self, filename: str) -> str:
        invalid_chars = '<>:"/\\|?*'
        for char in invalid_chars:
            filename = filename.replace(char, "_")
        return filename

    def _get_cache_path(self, symbol: str, start_date: str, end_date: str) -> Path:
        sanitized_symbol = self._sanitize_filename(symbol)
        filename = f"{sanitized_symbol}_{start_date}_{end_date}.json"
        return self.cache_dir / filename

    def _read_cache(self, path: Path) -> dict or None:
        if path.exists():
            try:
                with open(path, "r", encoding="utf-8") as f:
                    return json.load(f)
            except Exception as e:
                logger.warning(f"Error reading cache file at {path}: {e}. Treating as cache miss.")
        return None

    def _write_cache(self, path: Path, data: dict):
        try:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
        except Exception as e:
            logger.error(f"Failed to write cache file at {path}: {e}")

    def _mask_api_key(self, value: str) -> str:
        if not value:
            return ""
        if len(value) <= 8:
            return "*****"
        return f"{value[:4]}...{value[-4:]}"

    def _request(self, endpoint: str, params: dict = None) -> dict:
        url = f"{self.base_url}/{endpoint.lstrip('/')}"
        
        headers = {
            "X-Api-Key": self.api_key,
            "Accept": "application/json"
        }

        safe_headers = {k: (self._mask_api_key(v) if k.lower() == "x-api-key" else v) for k, v in headers.items()}
        logger.debug(f"Request: GET {url} with params: {params} and headers: {safe_headers}")

        retries = 3
        backoff_factor = 2.0
        
        for attempt in range(1, retries + 1):
            try:
                response = requests.get(url, headers=headers, params=params, timeout=10)
                
                if response.status_code == 429:
                    retry_after = response.headers.get("Retry-After")
                    try:
                        retry_after_sec = int(retry_after) if retry_after else 60
                    except ValueError:
                        retry_after_sec = 60
                        
                    err_msg = f"Rate limit exceeded. Resets at midnight or wait {retry_after_sec} seconds."
                    logger.error(err_msg)
                    raise FauxnanceRateLimitError(err_msg, retry_after=retry_after_sec)

                if 400 <= response.status_code < 500:
                    try:
                        err_json = response.json()
                        error_details = err_json.get("error", {})
                        error_code = error_details.get("code", "CLIENT_ERROR")
                        message = error_details.get("message", response.reason)
                    except Exception:
                        error_code = "CLIENT_ERROR"
                        message = response.text or response.reason
                        
                    err_msg = f"Client error {response.status_code}: {message}"
                    logger.error(err_msg)
                    raise FauxnanceClientError(err_msg, status_code=response.status_code, error_code=error_code)

                if response.status_code >= 500:
                    try:
                        err_json = response.json()
                        error_details = err_json.get("error", {})
                        error_code = error_details.get("code", "SERVER_ERROR")
                        message = error_details.get("message", response.reason)
                    except Exception:
                        error_code = "SERVER_ERROR"
                        message = response.text or response.reason
                        
                    err_msg = f"Server error {response.status_code}: {message}"
                    logger.error(err_msg)
                    raise FauxnanceServerError(err_msg, status_code=response.status_code, error_code=error_code)

                if response.status_code == 200:
                    try:
                        return response.json()
                    except json.JSONDecodeError as je:
                        logger.error(f"Failed to decode JSON response from {url}: {je}")
                        raise FauxnanceAPIError(
                            "Malformed JSON response from server.", 
                            status_code=200, 
                            error_code="MALFORMED_JSON"
                        ) from je

                raise FauxnanceAPIError(f"Unexpected HTTP status {response.status_code}", status_code=response.status_code)

            except (requests.exceptions.ConnectionError, requests.exceptions.Timeout) as ce:
                if attempt == retries:
                    err_msg = f"Fauxnance API request failed after {retries} attempts: {str(ce)}"
                    logger.critical(err_msg)
                    raise FauxnanceConnectionError(err_msg, status_code=503) from ce
                
                sleep_time = backoff_factor * (2 ** (attempt - 1))
                logger.warning(
                    f"Connection failure (attempt {attempt}/{retries}): {str(ce)}. "
                    f"Retrying in {sleep_time} seconds..."
                )
                time.sleep(sleep_time)

    def get_candles(self, symbol: str, start_date: str = None, end_date: str = None) -> dict:
        resolved_start = start_date or (date.today() - timedelta(days=30)).isoformat()
        resolved_end = end_date or date.today().isoformat()

        cache_path = self._get_cache_path(symbol, resolved_start, resolved_end)
        cached_data = self._read_cache(cache_path)
        
        if cached_data is not None:
            logger.info(f"Disk cache HIT for symbol: {symbol} in range {resolved_start} to {resolved_end}")
            return cached_data

        logger.info(f"Disk cache MISS for symbol: {symbol} in range {resolved_start} to {resolved_end}. Querying API.")
        params = {
            "from": resolved_start,
            "to": resolved_end,
            "interval": "1d"
        }
        
        raw_response = self._request(f"/candles/{symbol}", params=params)
        
        self._write_cache(cache_path, raw_response)
        return raw_response

    def get_usage(self) -> dict:
        logger.info("Querying daily quota status (/usage)")
        return self._request("/usage")

    def get_health(self) -> dict:
        logger.info("Querying API health (/health)")
        return self._request("/health")
